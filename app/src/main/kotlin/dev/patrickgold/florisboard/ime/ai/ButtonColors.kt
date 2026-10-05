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

import androidx.compose.ui.graphics.Color

/**
 * The colour choices for the Enter key and the mic and sparkle buttons (Settings > Customization).
 * "theme" means: use whatever the keyboard theme says (the Enter key is red in the built-in themes).
 */
object ButtonColors {
    val choices: List<Pair<String, Color?>> = listOf(
        "theme" to null,
        "red" to Color(0xFFFF1744),
        "orange" to Color(0xFFFF6D00),
        "yellow" to Color(0xFFFFC400),
        "green" to Color(0xFF00C853),
        "teal" to Color(0xFF00BFA5),
        "blue" to Color(0xFF2962FF),
        "purple" to Color(0xFFAA00FF),
        "pink" to Color(0xFFF50057),
        "grey" to Color(0xFF616161),
        "black" to Color(0xFF000000),
    )

    fun color(name: String): Color? = choices.firstOrNull { it.first == name }?.second

    fun next(name: String): String {
        val i = choices.indexOfFirst { it.first == name }
        return choices[(i + 1) % choices.size].first
    }

    fun label(name: String): String =
        if (name == "theme") "Pink (default)" else name.replaceFirstChar { it.uppercaseChar() }
}
