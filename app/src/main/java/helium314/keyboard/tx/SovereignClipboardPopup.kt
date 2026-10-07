// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: long-press on the pinned clipboard key opens a small bubble with two hotkeys.
package helium314.keyboard.tx

import android.content.Context
import android.content.SharedPreferences
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import androidx.core.content.edit
import helium314.keyboard.event.HapticEvent
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.AudioAndHapticFeedbackManager
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.dpToPx
import helium314.keyboard.latin.utils.getCodeForToolbarKey
import helium314.keyboard.latin.utils.getStringResourceOrName
import helium314.keyboard.latin.utils.prefs

object SovereignClipboardPopup {
    private const val PREF_FIRST = "sovereign_clipboard_longpress_1"
    private const val PREF_SECOND = "sovereign_clipboard_longpress_2"
    val DEFAULT_FIRST = ToolbarKey.SELECT_ALL
    val DEFAULT_SECOND = ToolbarKey.PASTE

    /** Toolbar actions the two hotkeys can be set to: everything but the strip's own keys. */
    val choices: List<ToolbarKey> = ToolbarKey.entries.filterNot {
        it in listOf(ToolbarKey.VOICE, ToolbarKey.AI_CLEANUP, ToolbarKey.FACT_CHECK, ToolbarKey.CLIPBOARD, ToolbarKey.CLOSE_HISTORY, ToolbarKey.BACKGROUND_GATHERING)
    }

    fun first(prefs: SharedPreferences) = read(prefs, PREF_FIRST, DEFAULT_FIRST)
    fun second(prefs: SharedPreferences) = read(prefs, PREF_SECOND, DEFAULT_SECOND)
    fun setFirst(prefs: SharedPreferences, key: ToolbarKey) = prefs.edit { putString(PREF_FIRST, key.name) }
    fun setSecond(prefs: SharedPreferences, key: ToolbarKey) = prefs.edit { putString(PREF_SECOND, key.name) }

    private fun read(prefs: SharedPreferences, pref: String, default: ToolbarKey): ToolbarKey =
        prefs.getString(pref, null)?.let { name -> choices.firstOrNull { it.name == name } } ?: default

    /** The toolbar's own name for [key] (e.g. "Select all"). */
    fun label(context: Context, key: ToolbarKey): String = key.name.lowercase().getStringResourceOrName("", context)

    private var shown: PopupWindow? = null

    fun dismiss() {
        shown?.dismiss()
        shown = null
    }

    /**
     * Shows the two hotkeys in a small rounded bubble just under [anchor] (over the top row of keys: the
     * keyboard window has no room above the strip). Tapping one runs that toolbar action through
     * [onCodeInput] and closes the bubble; a touch anywhere else closes it too.
     */
    fun show(anchor: View, onCodeInput: (Int) -> Unit) {
        dismiss()
        if (anchor.windowToken == null) return
        val context = anchor.context
        val colors = Settings.getValues().mColors
        val prefs = context.prefs()
        val keySize = anchor.height.takeIf { it > 0 } ?: 40.dpToPx(context.resources)
        val pad = 4.dpToPx(context.resources)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = 12f * context.resources.displayMetrics.density
                setColor(colors.get(ColorType.MAIN_BACKGROUND) or 0xFF000000.toInt())
                setStroke(1.dpToPx(context.resources), colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND) or 0xFF000000.toInt())
            }
        }
        val popup = PopupWindow(row, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, false)
        for (key in listOf(first(prefs), second(prefs))) {
            val button = ImageButton(context, null, R.attr.suggestionWordStyle).apply {
                scaleType = ImageView.ScaleType.CENTER
                // the same tint as the pinned strip icons (SovereignBoard settings, Colours)
                setImageDrawable(KeyboardIconsSet.instance.getNewDrawable(key.name, context)?.mutate()?.apply {
                    setTintList(android.content.res.ColorStateList.valueOf(SovereignTheme.hotkeyColor(prefs)))
                })
                colors.setBackground(this, ColorType.STRIP_BACKGROUND)
                contentDescription = label(context, key)
                setOnClickListener {
                    AudioAndHapticFeedbackManager.getInstance().performHapticFeedback(this, HapticEvent.KEY_PRESS)
                    dismiss()
                    onCodeInput(getCodeForToolbarKey(key))
                }
            }
            row.addView(button, LinearLayout.LayoutParams((keySize * 1.2f).toInt(), keySize))
        }
        popup.isOutsideTouchable = true
        popup.isTouchable = true
        popup.setBackgroundDrawable(ColorDrawable(0)) // needed for outside touches to dismiss on old Android
        popup.elevation = 6f * context.resources.displayMetrics.density
        popup.setOnDismissListener { if (shown === popup) shown = null }
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        // centred under the key, kept inside the keyboard's width
        val xoff = (anchor.width - row.measuredWidth) / 2
        runCatching {
            popup.showAsDropDown(anchor, xoff, 0, Gravity.NO_GRAVITY)
            shown = popup
        }
    }
}
