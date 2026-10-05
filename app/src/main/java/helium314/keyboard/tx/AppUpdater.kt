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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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
        val o = JSONObject(get("$BASE/latest.json").toString(Charsets.UTF_8))
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

    private fun get(url: String): ByteArray {
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

    companion object {
        const val BASE = "https://dictate.limn.dev/app/beta-h"
    }
}
