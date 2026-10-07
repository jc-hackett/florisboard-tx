// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: in-app updates from our own server, ported from the FlorisBoard edition.
package helium314.keyboard.tx

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import helium314.keyboard.latin.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.math.abs

/**
 * Checks [BASE]/latest.json; if its build differs from the one running, downloads the APK,
 * checks its SHA-256 against latest.json, and hands it to Android's installer, which asks the
 * user to confirm. Android refuses an APK signed with a different key than the installed one.
 */
class AppUpdater(context: Context) {
    private val appContext = context.applicationContext

    data class Release(val build: String, val short: String, val sha256: String, val size: Long, val apkUrl: String)

    val currentBuild: String get() = BuildConfig.BUILD_COMMIT_HASH

    /** Returns the newer release, or null when this is already the latest. Throws on failure. */
    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        val o = JSONObject(fetch("$BASE/latest.json").toString(Charsets.UTF_8))
        val build = o.getString("build")
        if (build.equals(currentBuild, ignoreCase = true)) return@withContext null
        Release(
            build = build,
            short = o.optString("short", build.take(8)),
            sha256 = o.getString("sha256"),
            size = o.optLong("size", -1L),
            apkUrl = "$BASE/" + o.optString("apk", "SovereignBoard-H.apk"),
        )
    }

    /** Downloads and verifies the APK; returns the file ready to install. */
    suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "SovereignBoard-H-${release.short}.apk")
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.useCaches = false
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            if (conn.responseCode !in 200..299) error("download failed (${conn.responseCode})")
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        digest.update(buf, 0, n)
                        out.write(buf, 0, n)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        val got = digest.digest().joinToString("") { "%02x".format(it) }
        if (!got.equals(release.sha256, ignoreCase = true) || (release.size > 0 && file.length() != release.size)) {
            file.delete()
            error("download was damaged, try again")
        }
        file
    }

    /** True if Android lets this app offer installs; otherwise [openInstallPermission] first. */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || appContext.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { appContext.startActivity(intent) }
    }

    /** Opens Android's installer on the downloaded APK; the user confirms there. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.updates", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    companion object {
        const val BASE = "https://dictate.limn.dev/app/beta-h"

        /** Plain GET; also used by [SovereignVersion]. Throws on failure. */
        fun fetch(url: String): ByteArray {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.useCaches = false
            try {
                if (conn.responseCode !in 200..299) error("server answered ${conn.responseCode}")
                return conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }
    }
}

/**
 * Whether a newer build is out, for the red "Update available" banner (see [SovereignToken.banner]).
 * Asks the update server at most every few hours, when the keyboard opens or settings are shown; the
 * answer is remembered, so the banner shows at once next time and goes away when this build is current.
 */
object SovereignUpdates {
    private const val CHECK_EVERY_MS = 4 * 60 * 60 * 1000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var checking = false

    private val _available = MutableStateFlow(false)
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val _installRequested = MutableStateFlow(false)
    /** Set when the banner was tapped: the SovereignBoard screen starts the install and resets it. */
    val installRequested: StateFlow<Boolean> = _installRequested.asStateFlow()
    fun requestInstall() { _installRequested.value = true }
    fun installStarted() { _installRequested.value = false }

    /** Re-reads the remembered answer. */
    fun refresh(context: Context) {
        val offered = DictationSettings(context).updateBuild
        _available.value = offered.isNotBlank() && !offered.equals(BuildConfig.BUILD_COMMIT_HASH, ignoreCase = true)
    }

    /** What a check found (null: this build is the latest). Also called by the settings screen's own check. */
    fun onChecked(context: Context, release: AppUpdater.Release?) {
        val s = DictationSettings(context)
        s.updateBuild = release?.build ?: ""
        s.lastUpdateCheck = System.currentTimeMillis()
        refresh(context)
    }

    @JvmStatic
    fun checkIfDue(context: Context) {
        val appContext = context.applicationContext
        refresh(appContext)
        if (checking) return
        if (abs(System.currentTimeMillis() - DictationSettings(appContext).lastUpdateCheck) < CHECK_EVERY_MS) return
        checking = true
        scope.launch {
            try {
                onChecked(appContext, AppUpdater(appContext).check())
            } catch (_: Exception) {
                // offline or server down: keep the last answer, try again next time
            } finally {
                checking = false
            }
        }
    }
}
