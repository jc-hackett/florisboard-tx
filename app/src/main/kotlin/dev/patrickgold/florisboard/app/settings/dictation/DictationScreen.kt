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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.patrickgold.florisboard.R
import dev.patrickgold.florisboard.ime.ai.AppUpdater
import dev.patrickgold.florisboard.ime.ai.AutoCorrector
import dev.patrickgold.florisboard.ime.ai.DictationSettings
import kotlinx.coroutines.launch
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
    var words by remember { mutableStateOf(settings.words) }
    var autocorrect by remember { mutableStateOf(settings.autocorrect) }
    var saved by remember { mutableStateOf(false) }
    val autoCorrector = remember { AutoCorrector(context) }
    var spellStatus by remember { mutableStateOf(autoCorrector.spellCheckerStatus() + " Tap to test with \"teh\" and \"spellchdcker\".") }
    val updater = remember { AppUpdater(context) }
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf("This version: ${updater.currentBuild.take(8)}. Tap to check.") }

    fun checkForUpdate() {
        if (updateBusy) return
        if (settings.serverUrl.isBlank()) {
            updateStatus = "Add the server address below first."
            return
        }
        updateBusy = true
        updateStatus = "Checking…"
        scope.launch {
            try {
                release = updater.check(settings.serverUrl)
                updateStatus = release?.let { "New version ${it.short} ready. Tap to install." }
                    ?: "Up to date (${updater.currentBuild.take(8)})."
            } catch (e: Exception) {
                updateStatus = "Couldn't check: ${e.message ?: "no connection"}. Tap to retry."
            } finally {
                updateBusy = false
            }
        }
    }

    fun installUpdate(r: AppUpdater.Release) {
        if (updateBusy) return
        if (!updater.canInstall()) {
            updateStatus = "Allow this keyboard to install updates, then come back and tap again."
            updater.openInstallPermission()
            return
        }
        updateBusy = true
        updateStatus = "Downloading ${r.short}…"
        scope.launch {
            try {
                val apk = updater.download(r)
                updateStatus = "Downloaded. Confirm the install on the next screen."
                updater.install(apk)
            } catch (e: Exception) {
                updateStatus = "Download failed: ${e.message ?: "no connection"}. Tap to retry."
            } finally {
                updateBusy = false
            }
        }
    }

    LaunchedEffect(Unit) { checkForUpdate() }
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
            title = stringRes(R.string.dictation__update__title),
            summary = updateStatus,
            onClick = { release?.let { installUpdate(it) } ?: checkForUpdate() },
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
        Preference(
            title = stringRes(R.string.dictation__autocorrect__title),
            summary = stringRes(
                if (autocorrect) R.string.dictation__autocorrect__on else R.string.dictation__autocorrect__off
            ),
            onClick = { autocorrect = !autocorrect; settings.autocorrect = autocorrect },
        )
        Preference(
            title = stringRes(R.string.dictation__spellcheck__title),
            summary = spellStatus,
            onClick = {
                spellStatus = "Testing…"
                scope.launch {
                    val locale = java.util.Locale.getDefault()
                    spellStatus = autoCorrector.spellCheckerStatus() + "\n" +
                        autoCorrector.test("teh", locale) + "\n" + autoCorrector.test("spellchdcker", locale)
                }
            },
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
        OutlinedTextField(
            value = words,
            onValueChange = { words = it; saved = false },
            label = { Text(stringRes(R.string.dictation__words)) },
            supportingText = { Text(stringRes(R.string.dictation__words_hint)) },
            minLines = 4,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Button(
            onClick = {
                settings.serverUrl = serverUrl
                settings.token = token
                settings.words = words
                saved = true
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(stringRes(if (saved) R.string.dictation__saved else R.string.dictation__save))
        }
    }
}
