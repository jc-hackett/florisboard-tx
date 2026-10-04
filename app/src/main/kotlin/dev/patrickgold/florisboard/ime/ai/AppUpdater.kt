/*
 * Copyright (C) 2026 The florisboard-tx Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dev.patrickgold.florisboard.ime.ai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dev.patrickgold.florisboard.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * In-app updates from the user's own server.
 *
 * The server (tx-server/apkpub) signs every build with one fixed key and publishes it at
 * `<server>/app/florisboard-tx.apk` with `<server>/app/latest.json` describing it. This checks
 * that file, and if its build differs from the one running, downloads the APK, checks its SHA-256
 * against latest.json, and hands it to Android's installer, which asks the user to confirm.
 * Nothing installs without that confirmation, and Android refuses an APK signed with any other key.
 */
class AppUpdater(context: Context) {
    private val appContext = context.applicationContext

    data class Release(val build: String, val short: String, val sha256: String, val apkUrl: String)

    val currentBuild: String get() = BuildConfig.BUILD_COMMIT_HASH

    /** Returns the newer release, or null when this is already the latest. Throws on failure. */
    suspend fun check(serverUrl: String): Release? = withContext(Dispatchers.IO) {
        // Two lanes: the test lane (<server>/app/beta/) gets every build; everyone else gets only
        // versions released on purpose (<server>/app/).
        val base = serverUrl.trim().trimEnd('/') + if (DictationSettings(appContext).testLane) "/app/beta" else "/app"
        require(base.startsWith("https://")) { "server address must start with https://" }
        val json = get("$base/latest.json").toString(Charsets.UTF_8)
        val o = JSONObject(json)
        val build = o.getString("build")
        if (build.equals(currentBuild, ignoreCase = true)) return@withContext null
        Release(
            build = build,
            short = o.optString("short", build.take(8)),
            sha256 = o.getString("sha256"),
            apkUrl = "$base/" + o.optString("apk", "florisboard-tx.apk"),
        )
    }

    /** Downloads and verifies the APK; returns the file ready to install. */
    suspend fun download(release: Release): File = withContext(Dispatchers.IO) {
        val dir = File(appContext.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "florisboard-tx-${release.short}.apk")
        val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
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
        if (!got.equals(release.sha256, ignoreCase = true)) {
            file.delete()
            error("download was damaged, try again")
        }
        file
    }

    /** True if Android lets this app offer installs; otherwise [openInstallPermission] first. */
    fun canInstall(): Boolean = appContext.packageManager.canRequestPackageInstalls()

    fun openInstallPermission() {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
            .setData(Uri.parse("package:${appContext.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        appContext.startActivity(intent)
    }

    /** Opens Android's installer on the downloaded APK; the user confirms there. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.provider.file", apk)
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
}
