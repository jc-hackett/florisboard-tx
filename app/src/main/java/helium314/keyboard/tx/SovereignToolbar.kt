// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: toolbar helpers for the ✨ key and the mic key's recording dot.
package helium314.keyboard.tx

import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.core.content.edit
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.min

object SovereignToolbar {
    private const val GREEN = 0xFF34C759.toInt() // like Android's mic-in-use indicator
    private const val GREY = 0xFF9E9E9E.toInt()
    private const val PREF_MIGRATED = "sovereign_ai_cleanup_toolbar_v1"

    /**
     * Keeps the mic and ✨ keys in [groups] showing what they are doing: a green dot on the mic
     * while recording, a grey dot while waiting for the server, a grey dot on ✨ while it works.
     * Returns the job to cancel when the views go away.
     */
    fun observe(groups: List<ViewGroup>): Job =
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            combine(DictationManager.current, AiCleanup.busy) { mic, busy -> mic to busy }.collect { (mic, busy) ->
                val micDot = when (mic) {
                    DictationState.RECORDING -> GREEN
                    DictationState.WORKING -> GREY
                    else -> null
                }
                for (group in groups) {
                    group.findViewWithTag<ImageButton>(ToolbarKey.VOICE)?.let { decorate(it, ToolbarKey.VOICE, micDot) }
                    group.findViewWithTag<ImageButton>(ToolbarKey.AI_CLEANUP)?.let { decorate(it, ToolbarKey.AI_CLEANUP, if (busy) GREY else null) }
                }
            }
        }

    private fun decorate(button: ImageButton, key: ToolbarKey, dot: Int?) {
        val colors: Colors = Settings.getValues().mColors
        button.setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(key.name, button.context))
        colors.setColor(button, ColorType.TOOL_BAR_KEY)
        if (dot != null) button.drawable?.let { button.setImageDrawable(DotDrawable(it, dot)) }
    }

    /**
     * One-time change for installs that existed before the ✨ key: put it right after the mic in
     * the toolbar, and in the pinned keys too if the mic is pinned there. Fresh installs get it
     * from the default toolbar list.
     */
    fun migrateToolbarPrefs(prefs: SharedPreferences) {
        if (prefs.getBoolean(PREF_MIGRATED, false)) return
        prefs.edit {
            insertAfterVoice(prefs, Settings.PREF_TOOLBAR_KEYS, always = true)?.let { putString(Settings.PREF_TOOLBAR_KEYS, it) }
            insertAfterVoice(prefs, Settings.PREF_PINNED_TOOLBAR_KEYS, always = false)?.let { putString(Settings.PREF_PINNED_TOOLBAR_KEYS, it) }
            putBoolean(PREF_MIGRATED, true)
        }
    }

    private fun insertAfterVoice(prefs: SharedPreferences, pref: String, always: Boolean): String? {
        val saved = prefs.getString(pref, null) ?: return null // not customised: the default list applies
        val entries = saved.split(Separators.ENTRY).filter { it.isNotEmpty() }.toMutableList()
        val voice = entries.indexOfFirst { it.startsWith(ToolbarKey.VOICE.name + Separators.KV) }
        val voiceOn = voice >= 0 && entries[voice].endsWith("true")
        if (!always && !voiceOn) return null
        entries.removeAll { it.startsWith(ToolbarKey.AI_CLEANUP.name + Separators.KV) }
        val at = entries.indexOfFirst { it.startsWith(ToolbarKey.VOICE.name + Separators.KV) }
        entries.add(if (at >= 0) at + 1 else 0, ToolbarKey.AI_CLEANUP.name + Separators.KV + "true")
        return entries.joinToString(Separators.ENTRY)
    }

    /** The key's own icon with a small coloured dot in the top corner; the dot ignores the icon tint. */
    private class DotDrawable(private val base: Drawable, color: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

        override fun draw(canvas: Canvas) {
            base.draw(canvas)
            val b = bounds
            val r = min(b.width(), b.height()) * 0.17f
            canvas.drawCircle(b.right - r, b.top + r, r, paint)
        }

        override fun onBoundsChange(bounds: Rect) {
            base.bounds = bounds
        }

        override fun getIntrinsicWidth() = base.intrinsicWidth
        override fun getIntrinsicHeight() = base.intrinsicHeight
        override fun setAlpha(alpha: Int) { base.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { base.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
