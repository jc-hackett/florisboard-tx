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

package dev.patrickgold.florisboard.app.settings.dictation

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.patrickgold.florisboard.ime.ai.DictationSettings
import dev.patrickgold.florisboard.ime.ai.EditLog
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.Preference
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * florisboard-tx: "Recent edits" - each dictation and AI cleanup as before and after, with a note
 * box, so the cleanup can be tuned from real examples. On this phone only; off unless switched on;
 * entries older than seven days are dropped. Export shares everything as plain text.
 */
@Composable
fun EditsScreen() = FlorisScreen {
    title = "Recent edits"

    val context = LocalContext.current
    val settings = remember { DictationSettings(context) }
    var enabled by remember { mutableStateOf(settings.editLog) }
    var entries by remember { mutableStateOf(EditLog.load(context).take(60)) }
    val fmt = remember { SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault()) }

    content {
        Preference(
            title = "Keep a log of edits",
            summary = if (enabled) {
                "On. Kept on this phone only and wiped after 7 days. It can hold client details, so turn it off when you're done. Tap to turn off."
            } else {
                "Off. Tap to start keeping before-and-after examples."
            },
            onClick = { enabled = !enabled; settings.editLog = enabled },
        )
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Button(onClick = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Keyboard edits")
                    .putExtra(Intent.EXTRA_TEXT, EditLog.exportText(context))
                context.startActivity(Intent.createChooser(send, "Export edits").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Export") }
            OutlinedButton(
                onClick = { EditLog.clear(context); entries = emptyList() },
                modifier = Modifier.padding(start = 12.dp),
            ) { Text("Clear all") }
        }
        if (entries.isEmpty()) {
            Text(
                "Nothing yet. With the log on, dictations and AI cleanups show up here.",
                modifier = Modifier.padding(16.dp),
            )
        }
        for (e in entries) {
            HorizontalDivider()
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("${e.kind} · ${fmt.format(Date(e.time))}", fontWeight = FontWeight.Bold)
                Text("Before: ${e.before}", modifier = Modifier.padding(top = 4.dp))
                Text("After: ${e.after}", modifier = Modifier.padding(top = 4.dp))
                var note by remember(e.id) { mutableStateOf(e.note) }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it; EditLog.setNote(context, e.id, it) },
                    label = { Text("Your note") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                )
            }
        }
    }
}
