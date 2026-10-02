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

package dev.patrickgold.florisboard.ime.media.emoji

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.patrickgold.florisboard.ime.theme.FlorisImeUi
import dev.patrickgold.florisboard.keyboardManager
import dev.patrickgold.florisboard.ime.keyboard.FlorisImeSizing
import dev.patrickgold.florisboard.subtypeManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.florisboard.lib.snygg.ui.rememberSnyggThemeQuery

/**
 * florisboard-tx: emoji search. The magnifier in the emoji panel switches back to the letter keys
 * with a search strip above them; letters typed while the search is open go into the query (not
 * the app), and the strip shows matching emoji by name and keyword. Tap one to insert it.
 */
object EmojiSearch {
    private val _query = MutableStateFlow<String?>(null) // null = search closed
    val query: StateFlow<String?> = _query.asStateFlow()

    val isActive: Boolean get() = _query.value != null

    fun start() { _query.value = "" }

    fun stop() { _query.value = null }

    fun type(text: String) {
        val q = _query.value ?: return
        if (q.length < 40) _query.value = q + text
    }

    /** Backspace: removes a letter, or closes the search when the query is already empty. */
    fun backspace() {
        val q = _query.value ?: return
        _query.value = if (q.isEmpty()) null else q.dropLast(1)
    }

    fun search(data: EmojiData, query: String, limit: Int = 40): List<Emoji> {
        val q = query.trim().lowercase()
        val all = data.bySkinTone[EmojiSkinTone.DEFAULT].orEmpty()
        if (q.isEmpty()) return emptyList()
        return all.asSequence()
            .mapNotNull { e ->
                val name = e.name.lowercase()
                val rank = when {
                    e.keywords.any { it.equals(q, ignoreCase = true) } || name == q -> 0
                    name.startsWith(q) || name.split(' ').any { it.startsWith(q) } -> 1
                    e.keywords.any { it.lowercase().startsWith(q) } -> 2
                    name.contains(q) -> 3
                    else -> null
                }
                rank?.let { it to e }
            }
            .sortedBy { it.first }
            .take(limit)
            .map { it.second }
            .toList()
    }
}

@Composable
fun EmojiSearchBar(query: String) {
    val context = LocalContext.current
    val keyboardManager by context.keyboardManager()
    val subtypeManager by context.subtypeManager()
    val color = rememberSnyggThemeQuery(FlorisImeUi.SmartbarCandidateWord.elementName).foreground()
    var results by remember { mutableStateOf(emptyList<Emoji>()) }
    LaunchedEffect(query) {
        val data = EmojiData.get(context, subtypeManager.activeSubtype.primaryLocale)
        results = EmojiSearch.search(data, query)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FlorisImeSizing.smartbarHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Close,
            contentDescription = "Close emoji search",
            tint = color,
            modifier = Modifier
                .clickable { EmojiSearch.stop() }
                .padding(horizontal = 10.dp),
        )
        Text(
            text = if (query.isEmpty()) "Search emoji…" else query,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .widthIn(max = 110.dp)
                .padding(end = 8.dp),
        )
        LazyRow(verticalAlignment = Alignment.CenterVertically) {
            items(results) { emoji ->
                Text(
                    text = emoji.value,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .clickable {
                            keyboardManager.inputEventDispatcher.sendDownUp(emoji)
                            EmojiSearch.stop()
                        }
                        .padding(horizontal = 6.dp),
                )
            }
        }
    }
}
