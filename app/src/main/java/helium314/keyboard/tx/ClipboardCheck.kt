// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: remembers what happened to the last clipboard change, for the "Clipboard check" in
// SovereignScreen. Privacy-safe on purpose: no clip text, no labels, no file paths. Only the time, the
// mime types, whether the item had text and/or a uri, the uri's scheme + authority, and the outcome.
package helium314.keyboard.tx

import android.content.ClipData
import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import helium314.keyboard.latin.utils.prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ClipboardCheck {
    private const val PREF_TIME = "sovereign_clip_check_time"
    private const val PREF_INFO = "sovereign_clip_check_info"
    private const val PREF_LISTENER_TIME = "sovereign_clip_check_listener_time"

    /** source: "copy" (change listener), "keyboard-open" (late read), "start" (keyboard started) */
    fun record(context: Context, source: String, clip: ClipData?, outcome: String) {
        if (source == "start") return // keyboard (re)start re-reads an old clip; keep the real last event
        val parts = mutableListOf<String>()
        if (clip != null) {
            val d = clip.description
            val types = if (d == null) "" else (0 until d.mimeTypeCount).joinToString(",") { d.getMimeType(it) }
            parts.add(types.ifEmpty { "no type" })
            val item = if (clip.itemCount > 0) clip.getItemAt(0) else null
            val has = listOfNotNull(
                if (item?.text != null) "text" else null,
                if (item?.uri != null) "uri" else null,
                if (item?.htmlText != null) "html" else null,
                if (item?.intent != null) "intent" else null,
            )
            parts.add(if (has.isEmpty()) "item empty" else "item has " + has.joinToString("+"))
            if (clip.itemCount > 1) parts.add("${clip.itemCount} items")
            item?.uri?.let { parts.add(shortUri(it)) }
        }
        parts.add(outcome)
        val now = System.currentTimeMillis()
        runCatching {
            context.prefs().edit {
                putLong(PREF_TIME, now)
                putString(PREF_INFO, "$source · " + parts.joinToString(" · "))
                if (source == "copy") putLong(PREF_LISTENER_TIME, now)
            }
        }
    }

    fun error(e: Throwable): String {
        val msg = scrub(e.message ?: "").take(90)
        return "error-${e.javaClass.simpleName}" + if (msg.isEmpty()) "" else ": $msg"
    }

    /** Text for the settings screen. */
    fun summary(context: Context): String {
        val prefs = context.prefs()
        val time = prefs.getLong(PREF_TIME, 0L)
        if (time == 0L) return "Last copy: nothing seen yet. Copy something (an image, or text), then come back here."
        val info = prefs.getString(PREF_INFO, "") ?: ""
        val listener = prefs.getLong(PREF_LISTENER_TIME, 0L)
        val listenerLine = if (listener == 0L) "The keyboard has not yet been told about a copy as it happened."
            else "Last told about a copy as it happened: ${format(listener)}."
        return "Last copy: ${format(time)} · $info\n$listenerLine"
    }

    private fun format(t: Long): String {
        val sameDay = System.currentTimeMillis() - t < 20 * 60 * 60 * 1000L
        return SimpleDateFormat(if (sameDay) "HH:mm" else "d MMM HH:mm", Locale.getDefault()).format(Date(t))
    }

    private fun shortUri(uri: Uri): String {
        val auth = uri.authority
        return if (auth.isNullOrEmpty()) "${uri.scheme}:" else "${uri.scheme}://$auth"
    }

    // exception messages can quote the full uri or a file path; keep only scheme + authority
    private fun scrub(s: String): String = s
        .replace(Regex("""(\w+)://([^/\s]+)\S*"""), "$1://$2")
        .replace(Regex("""(?<![\w:])/[\w.\-]+(/[\w.\-]+)+"""), "<path>")
        .replace(Regex("""\s+"""), " ")
        .trim()
}
