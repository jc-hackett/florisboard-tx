// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: the pink look from the FlorisBoard edition (floris_day.json + QuickActionButton.kt),
// stored as a HeliBoard user colour theme so it can still be changed in Settings > Appearance.
package helium314.keyboard.tx

import android.content.SharedPreferences
import androidx.core.content.edit
import helium314.keyboard.keyboard.ColorSetting
import helium314.keyboard.keyboard.KeyboardTheme
import helium314.keyboard.latin.settings.Settings
import kotlinx.serialization.json.Json

object SovereignTheme {
    /** Name of the user colour theme, as shown in the colours settings. */
    const val THEME_NAME = "SovereignBoard"
    private const val PREF_MIGRATED = "sovereign_pink_theme_v1"

    const val BACKGROUND = 0xFFEEF0F3.toInt() // Gboard-like light grey
    const val KEYS = 0xFFFFFFFF.toInt() // white letter keys
    const val FUNCTIONAL_KEYS = 0xFFF9DCE2.toInt() // light pink: shift, delete, ?123, emoji, ...
    const val FUNCTIONAL_KEYS_PRESSED = 0xFFF2B8C3.toInt() // deeper pink (badge while recording / working)
    const val ENTER = 0xFFD81B3C.toInt() // crown red; HeliBoard draws the action key in the accent colour
    const val TEXT = 0xFF000000.toInt()
    const val SUGGESTION_TEXT = 0xFF121212.toInt()

    private val colors = listOf(
        ColorSetting(KeyboardTheme.COLOR_ACCENT, false, ENTER),
        ColorSetting(KeyboardTheme.COLOR_BACKGROUND, false, BACKGROUND),
        ColorSetting(KeyboardTheme.COLOR_KEYS, false, KEYS),
        ColorSetting(KeyboardTheme.COLOR_FUNCTIONAL_KEYS, false, FUNCTIONAL_KEYS),
        ColorSetting(KeyboardTheme.COLOR_SPACEBAR, false, KEYS),
        ColorSetting(KeyboardTheme.COLOR_TEXT, false, TEXT),
        ColorSetting(KeyboardTheme.COLOR_SUGGESTION_TEXT, false, SUGGESTION_TEXT),
    )

    /** Colours JSON for the user theme, in the format KeyboardTheme.writeUserColors uses. */
    private fun colorsJson() = Json.encodeToString(colors)

    /**
     * One-time change, for fresh and existing installs alike: create the pink user theme, make it the
     * day theme, and turn on key borders so letter keys show as white keys (the night theme is left alone).
     */
    fun migrate(prefs: SharedPreferences) {
        if (prefs.getBoolean(PREF_MIGRATED, false)) return
        prefs.edit {
            putString(Settings.PREF_USER_COLORS_PREFIX + THEME_NAME, colorsJson())
            putInt(Settings.PREF_USER_MORE_COLORS_PREFIX + THEME_NAME, 0)
            putString(Settings.PREF_THEME_COLORS, THEME_NAME)
            putBoolean(Settings.PREF_THEME_KEY_BORDERS, true)
            putBoolean(PREF_MIGRATED, true)
        }
    }
}
