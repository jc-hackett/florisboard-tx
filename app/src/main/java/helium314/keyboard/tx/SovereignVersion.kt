// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: our release number ("0.3.2") for the About screen, looked up from versions.json.
package helium314.keyboard.tx

import android.content.Context
import androidx.core.content.edit
import helium314.keyboard.latin.BuildConfig
import helium314.keyboard.latin.utils.prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Release numbers are given at publish time and listed in [AppUpdater.BASE]/versions.json as
 * {version, build, date, published}. A found number is remembered per build, so it shows at once
 * next time; a build not listed yet (or no network) is asked again on the next visit.
 */
object SovereignVersion {
    const val HELP_URL = "https://dictate.limn.dev/app/help-h.html"
    const val WHATS_NEW_URL = "https://dictate.limn.dev/app/whatsnew-h.html"
    const val SOURCE_URL = "https://github.com/jc-hackett/florisboard-tx/tree/heliboard"

    private const val PREF_BUILD = "sovereign_version_build"
    private const val PREF_VERSION = "sovereign_version_name"

    val build: String get() = BuildConfig.BUILD_COMMIT_HASH
    val shortBuild: String get() = build.take(8)

    /** The remembered release number for this build, or null. */
    fun cached(context: Context): String? {
        val p = context.prefs()
        if (!p.getString(PREF_BUILD, "").equals(build, ignoreCase = true)) return null
        return p.getString(PREF_VERSION, null)?.takeIf { it.isNotBlank() }
    }

    /** Looks this build up in versions.json; null if not listed or offline. */
    suspend fun lookup(context: Context): String? = withContext(Dispatchers.IO) {
        try {
            val arr = JSONArray(AppUpdater.fetch("${AppUpdater.BASE}/versions.json").toString(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (!o.optString("build").equals(build, ignoreCase = true)) continue
                val v = o.optString("version").takeIf { it.isNotBlank() } ?: continue
                context.prefs().edit { putString(PREF_BUILD, build); putString(PREF_VERSION, v) }
                return@withContext v
            }
            null
        } catch (_: Exception) {
            null
        }
    }
}
