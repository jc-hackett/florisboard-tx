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

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.ai.DictationSettings
import dev.patrickgold.florisboard.lib.compose.FlorisScreen
import dev.patrickgold.jetpref.datastore.ui.Preference
import org.florisboard.lib.compose.stringRes

@Composable
fun DictationScreen() = FlorisScreen {
    title = stringRes(R.string.dictation__title)

    val context = LocalContext.current
    val settings = remember { DictationSettings(context) }
    var serverUrl by remember { mutableStateOf(settings.serverUrl) }
    var token by remember { mutableStateOf(settings.token) }
    var saved by remember { mutableStateOf(false) }
    var micGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> micGranted = granted }

    content {
        Text(
            text = stringRes(R.string.dictation__intro),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Preference(
            title = stringRes(R.string.dictation__microphone__title),
            summary = if (micGranted) {
                stringRes(R.string.dictation__microphone__granted)
            } else {
                stringRes(R.string.dictation__microphone__not_granted)
            },
            onClick = { if (!micGranted) micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        )
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it; saved = false },
            label = { Text(stringRes(R.string.dictation__server_url)) },
            placeholder = { Text(stringRes(R.string.dictation__server_url_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        OutlinedTextField(
            value = token,
            onValueChange = { token = it; saved = false },
            label = { Text(stringRes(R.string.dictation__token)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Button(
            onClick = {
                settings.serverUrl = serverUrl
                settings.token = token
                saved = true
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringRes(if (saved) R.string.dictation__saved else R.string.dictation__save))
        }
    }
}
