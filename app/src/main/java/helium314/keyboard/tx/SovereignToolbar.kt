// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: toolbar helpers for the ✨ key and the mic key's recording dot.
package helium314.keyboard.tx

import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.core.content.edit
import android.widget.TextView
import java.util.WeakHashMap
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Colors
import helium314.keyboard.latin.common.Constants.Separators
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.brightenOrDarken
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
    private const val UNDO_RED = SovereignTheme.ENTER // #D81B3C, the theme's accent / Enter colour
    private var sparkleDescription: CharSequence? = null
    private const val PREF_MIGRATED = "sovereign_ai_cleanup_toolbar_v1"
    private const val PREF_PINNED_MIGRATED = "sovereign_pinned_mic_sparkle_v1"
    private const val PREF_TRIM_MIGRATED = "sovereign_toolbar_trim_v1"
    private const val PREF_STRIP_CLEANUP_MIGRATED = "sovereign_strip_cleanup_v1"
    private const val PREF_CLIP_RETENTION_MIGRATED = "sovereign_clip_retention_60_v1"
    private const val PREF_TOOLBAR_NO_CLIPBOARD_MIGRATED = "sovereign_toolbar_no_clipboard_v1"

    /** Keys taken out of the expanded toolbar; mic and ✨ stay pinned in the suggestion strip. */
    private val TRIMMED = listOf(
        ToolbarKey.VOICE, ToolbarKey.AI_CLEANUP, ToolbarKey.SELECT_ALL, ToolbarKey.SELECT_WORD,
        ToolbarKey.COPY, ToolbarKey.REDO, ToolbarKey.PASTE,
    )

    /**
     * Keeps the mic and ✨ keys in [groups] showing what they are doing: a green dot on the mic
     * while recording, a grey dot while waiting for the server, a grey dot on ✨ while it works, and
     * ✨ turned into a white undo arrow on red while a cleanup can be undone.
     * Returns the job to cancel when the views go away.
     */
    fun observe(groups: List<ViewGroup>, tokenBanner: View? = null): Job =
        CoroutineScope(Dispatchers.Main + SupervisorJob()).launch {
            tokenBanner?.let { SovereignToken.checkIfDue(it.context) }
            combine(
                DictationManager.current, AiCleanup.busy, SovereignUndo.cleanupOffered, SovereignToken.banner
            ) { mic, busy, undo, banner -> Pair(Look(mic, busy, undo), banner) }
                .collect { (look, banner) ->
                    apply(groups, look, force = true)
                    (tokenBanner as? TextView)?.let { SovereignToken.showOnKeyboardBanner(it, banner) }
                }
        }

    /**
     * Brings the mic and ✨ keys in [groups] up to the current state, for key views HeliBoard has just
     * (re)created or redrawn (strip updates, pinning, toolbar shown or hidden). Views already showing
     * the current state are left alone, so this is cheap enough to call on every strip update.
     */
    fun refresh(groups: List<ViewGroup>) =
        apply(groups, Look(DictationManager.current.value, AiCleanup.busy.value, SovereignUndo.cleanupOffered.value), force = false)

    /** What the mic and ✨ keys should show. */
    private data class Look(val mic: DictationState, val busy: Boolean, val undo: Boolean)

    /** The look last drawn on each key view, so [refresh] only touches views that are out of date. */
    private val drawn = WeakHashMap<ImageButton, Any>()

    private fun apply(groups: List<ViewGroup>, look: Look, force: Boolean) {
        val micDot = when (look.mic) {
            DictationState.RECORDING -> GREEN
            DictationState.WORKING -> GREY
            else -> null
        }
        val micLook = Pair(micDot, look.mic == DictationState.RECORDING)
        val sparkleLook: Any = if (look.undo && !look.busy) "undo" else look.busy
        for (group in groups) {
            for (i in 0 until group.childCount) {
                val button = group.getChildAt(i) as? ImageButton ?: continue
                when (button.tag) {
                    ToolbarKey.VOICE -> {
                        if (!force && drawn[button] == micLook) continue
                        decorate(button, ToolbarKey.VOICE, micDot, strong = micLook.second)
                        drawn[button] = micLook
                    }
                    ToolbarKey.AI_CLEANUP -> {
                        if (!force && drawn[button] == sparkleLook) continue
                        if (sparkleLook == "undo") decorateUndo(button)
                        else decorate(button, ToolbarKey.AI_CLEANUP, if (look.busy) GREY else null, strong = look.busy)
                        drawn[button] = sparkleLook
                    }
                }
            }
        }
    }

    private fun decorate(button: ImageButton, key: ToolbarKey, dot: Int?, strong: Boolean) {
        val colors: Colors = Settings.getValues().mColors
        button.setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(key.name, button.context))
        colors.setColor(button, ColorType.TOOL_BAR_KEY)
        if (dot != null) button.drawable?.let { button.setImageDrawable(DotDrawable(it, dot)) }
        button.background = BadgeDrawable(badgeColor(colors, strong), badgeColor(colors, true))
        if (key == ToolbarKey.AI_CLEANUP) button.contentDescription = sparkleDescription ?: button.contentDescription
    }

    /** ✨ in its undo state: HeliBoard's undo arrow in white on a red (Enter-key colour) badge. */
    private fun decorateUndo(button: ImageButton) {
        if (sparkleDescription == null) sparkleDescription = button.contentDescription
        val icon = KeyboardIconsSet.instance.getNewDrawable(ToolbarKey.UNDO.name, button.context)?.mutate()
        icon?.setTintList(ColorStateList.valueOf(Color.WHITE))
        button.setImageDrawable(icon)
        button.background = BadgeDrawable(UNDO_RED, brightenOrDarken(UNDO_RED, true))
        button.contentDescription = "Undo cleanup"
    }

    /**
     * The round badge behind the mic and ✨, as in the FlorisBoard edition: the theme's function-key
     * colour (light pink in the SovereignBoard theme), deeper while recording or working.
     */
    private fun badgeColor(colors: Colors, strong: Boolean): Int {
        val functional = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND) or 0xFF000000.toInt()
        if (!strong) return functional
        return if (functional == SovereignTheme.FUNCTIONAL_KEYS) SovereignTheme.FUNCTIONAL_KEYS_PRESSED
        else brightenOrDarken(functional, true)
    }

    /**
     * One-time change, for existing installs: pin the mic and then ✨ in the suggestion strip, so they
     * show on the same row as the word suggestions. Fresh installs get this from the default pinned list.
     */
    fun migratePinnedKeys(prefs: SharedPreferences) {
        if (prefs.getBoolean(PREF_PINNED_MIGRATED, false)) return
        prefs.edit {
            prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, null)?.let { saved ->
                val entries = saved.split(Separators.ENTRY).filter { it.isNotEmpty() }.toMutableList()
                val ours = listOf(ToolbarKey.VOICE, ToolbarKey.AI_CLEANUP).map { it.name + Separators.KV }
                entries.removeAll { e -> ours.any { e.startsWith(it) } }
                // after the keys already pinned, so mic and ✨ end up at the outer edge of the strip
                val lastOn = entries.indexOfLast { it.endsWith("true") }
                entries.addAll(lastOn + 1, ours.map { it + "true" })
                putString(Settings.PREF_PINNED_TOOLBAR_KEYS, entries.joinToString(Separators.ENTRY))
            }
            putBoolean(PREF_PINNED_MIGRATED, true)
        }
    }

    /**
     * One-time change, for existing installs, so the mic and ✨ are always on screen:
     * - switch off "auto show toolbar": with it on, an empty box (or no suggestions) swaps the strip
     *   for the expanded toolbar, hiding the pinned keys. The toolbar stays one tap away on the caret.
     * - take the cursor left/right arrows out of the expanded toolbar (the spacebar moves the cursor).
     * - pin clipboard history in the strip, just left of the mic (it stays in the toolbar too).
     * Fresh installs get all of this from the defaults.
     */
    fun migrateStripCleanup(prefs: SharedPreferences) {
        if (prefs.getBoolean(PREF_STRIP_CLEANUP_MIGRATED, false)) return
        prefs.edit {
            putBoolean(Settings.PREF_AUTO_SHOW_TOOLBAR, false)
            prefs.getString(Settings.PREF_TOOLBAR_KEYS, null)?.let { saved ->
                val arrows = listOf(ToolbarKey.LEFT, ToolbarKey.RIGHT)
                val trimmed = saved.split(Separators.ENTRY).filter { it.isNotEmpty() }.joinToString(Separators.ENTRY) { e ->
                    val key = arrows.firstOrNull { e.startsWith(it.name + Separators.KV) }
                    if (key != null) key.name + Separators.KV + "false" else e
                }
                putString(Settings.PREF_TOOLBAR_KEYS, trimmed)
            }
            prefs.getString(Settings.PREF_PINNED_TOOLBAR_KEYS, null)?.let { saved ->
                val entries = saved.split(Separators.ENTRY).filter { it.isNotEmpty() }.toMutableList()
                val clip = ToolbarKey.CLIPBOARD.name + Separators.KV
                entries.removeAll { it.startsWith(clip) }
                val voice = entries.indexOfFirst { it.startsWith(ToolbarKey.VOICE.name + Separators.KV) }
                entries.add(if (voice >= 0) voice else entries.indexOfLast { it.endsWith("true") } + 1, clip + "true")
                putString(Settings.PREF_PINNED_TOOLBAR_KEYS, entries.joinToString(Separators.ENTRY))
            }
            putBoolean(PREF_STRIP_CLEANUP_MIGRATED, true)
        }
    }

    /**
     * One-time change, for existing installs: take clipboard history out of the expanded toolbar
     * (it stays pinned in the strip, next to the mic). Fresh installs get this from the default list.
     */
    fun migrateToolbarNoClipboard(prefs: SharedPreferences) {
        if (prefs.getBoolean(PREF_TOOLBAR_NO_CLIPBOARD_MIGRATED, false)) return
        prefs.edit {
            prefs.getString(Settings.PREF_TOOLBAR_KEYS, null)?.let { saved ->
                val clip = ToolbarKey.CLIPBOARD.name + Separators.KV
                val trimmed = saved.split(Separators.ENTRY).filter { it.isNotEmpty() }.joinToString(Separators.ENTRY) { e ->
                    if (e.startsWith(clip)) clip + "false" else e
                }
                putString(Settings.PREF_TOOLBAR_KEYS, trimmed)
            }
            putBoolean(PREF_TOOLBAR_NO_CLIPBOARD_MIGRATED, true)
        }
    }

    /**
     * One-time change, for existing installs: keep clipboard history for 60 minutes instead of 10, but
     * only if the old default of 10 is still set (a value the user picked is left alone).
     */
    fun migrateClipboardRetention(prefs: SharedPreferences) {
        if (prefs.getBoolean(PREF_CLIP_RETENTION_MIGRATED, false)) return
        prefs.edit {
            if (prefs.getInt(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, 10) == 10)
                putInt(Settings.PREF_CLIPBOARD_HISTORY_RETENTION_TIME, 60)
            putBoolean(PREF_CLIP_RETENTION_MIGRATED, true)
        }
    }

    /**
     * One-time change, for existing installs: switch off mic, ✨, select, copy, redo and paste in the
     * expanded toolbar (clipboard history stays). The pinned keys are a separate list and are left
     * alone, so mic and ✨ stay in the suggestion strip. Fresh installs get this from the default list.
     */
    fun migrateToolbarTrim(prefs: SharedPreferences) {
        if (prefs.getBoolean(PREF_TRIM_MIGRATED, false)) return
        prefs.edit {
            prefs.getString(Settings.PREF_TOOLBAR_KEYS, null)?.let { saved ->
                val trimmed = saved.split(Separators.ENTRY).filter { it.isNotEmpty() }.joinToString(Separators.ENTRY) { e ->
                    val key = TRIMMED.firstOrNull { e.startsWith(it.name + Separators.KV) }
                    if (key != null) key.name + Separators.KV + "false" else e
                }
                putString(Settings.PREF_TOOLBAR_KEYS, trimmed)
            }
            putBoolean(PREF_TRIM_MIGRATED, true)
        }
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

    /** A filled circle centred in the key, sized to the shorter side; [pressed] colour while touched. */
    private class BadgeDrawable(private val normal: Int, private val pressed: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = normal }

        override fun isStateful() = true
        override fun onStateChange(state: IntArray): Boolean {
            val color = if (android.R.attr.state_pressed in state) pressed else normal
            if (paint.color == color) return false
            paint.color = color
            invalidateSelf()
            return true
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val r = min(b.width(), b.height()) * 0.42f
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, paint)
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { } // keep the badge colour; the icon is tinted, not the badge
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
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
