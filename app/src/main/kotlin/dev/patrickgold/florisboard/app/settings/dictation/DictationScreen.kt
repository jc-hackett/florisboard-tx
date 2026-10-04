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
import dev.patrickgold.florisboard.app.LocalNavController
import dev.patrickgold.florisboard.app.Routes
import dev.patrickgold.florisboard.ime.ai.AiCleanup
import dev.patrickgold.florisboard.ime.ai.AppUpdater
import dev.patrickgold.florisboard.ime.ai.ButtonColors
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
    var autoCleanup by remember { mutableStateOf(settings.autoCleanupOnPeriod) }
    var touchGuess by remember { mutableStateOf(settings.touchGuess) }
    var testLane by remember { mutableStateOf(settings.testLane) }
    var enterColor by remember { mutableStateOf(settings.enterColor) }
    var micColor by remember { mutableStateOf(settings.micColor) }
    var sparkleColor by remember { mutableStateOf(settings.sparkleColor) }
    val navController = LocalNavController.current
    var saved by remember { mutableStateOf(false) }
    val autoCorrector = remember { AutoCorrector(context) }
    var spellStatus by remember {
        mutableStateOf(
            autoCorrector.spellCheckerStatus() + "\nLast word typed: " + AutoCorrector.lastEvent +
                "\nTap to test with \"teh\" and \"spellchdcker\"."
        )
    }
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
        // florisboard-tx: a first-run checklist. Each line ticks when done; a tap opens the next
        // missing step.
        val keyboardOn = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.enabledInputMethodList?.any { it.packageName == context.packageName } == true
        val connected = token.isNotBlank()
        val tick = { done: Boolean -> if (done) "✓" else "○" }
        Preference(
            title = if (keyboardOn && micGranted && connected) "All set" else "Getting started",
            summary = "${tick(keyboardOn)} Keyboard turned on\n" +
                "${tick(micGranted)} Microphone allowed\n" +
                "${tick(connected)} Connected" + (if (connected) "" else " (open your setup link, or paste your access token below)") +
                (if (!keyboardOn || !micGranted) "\nTap to do the next step." else ""),
            onClick = {
                when {
                    !keyboardOn -> context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    !micGranted -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
        )
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
            title = stringRes(R.string.dictation__auto_cleanup__title),
            summary = stringRes(
                if (autoCleanup) R.string.dictation__auto_cleanup__on else R.string.dictation__auto_cleanup__off
            ) + "\nLast sentence: " + AiCleanup.lastAutoEvent,
            onClick = { autoCleanup = !autoCleanup; settings.autoCleanupOnPeriod = autoCleanup },
        )
        Preference(
            title = "Test versions",
            summary = if (testLane) {
                "On. You get every new build first, before it's released to everyone. Tap to switch back to releases."
            } else {
                "Off. You get tested releases. Tap to get test versions early (for testers)."
            },
            onClick = { testLane = !testLane; settings.testLane = testLane; release = null },
        )
        Preference(
            title = "Smarter tapping",
            summary = if (touchGuess) {
                "On. A tap near the edge of a key goes to the letter that best fits the word you're typing. Tap to turn off."
            } else {
                "Off. Every tap counts exactly where it lands. Tap to turn on."
            },
            onClick = { touchGuess = !touchGuess; settings.touchGuess = touchGuess },
        )
        Preference(
            title = "Last copied image",
            summary = dev.patrickgold.florisboard.ime.clipboard.ClipboardManager.lastImageEvent,
        )
        Preference(
            title = "Recent edits",
            summary = "Before and after for each dictation and cleanup, with notes, to tune the cleanup.",
            onClick = { navController.navigate(Routes.Settings.Edits) },
        )
        Preference(
            title = "Enter key colour",
            summary = ButtonColors.label(enterColor) + ". Tap to change.",
            onClick = { enterColor = ButtonColors.next(enterColor); settings.enterColor = enterColor },
        )
        Preference(
            title = "Mic button colour",
            summary = ButtonColors.label(micColor) + ". Tap to change.",
            onClick = { micColor = ButtonColors.next(micColor); settings.micColor = micColor },
        )
        Preference(
            title = "Sparkle button colour",
            summary = ButtonColors.label(sparkleColor) + ". Tap to change.",
            onClick = { sparkleColor = ButtonColors.next(sparkleColor); settings.sparkleColor = sparkleColor },
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
                        "Last word typed: " + AutoCorrector.lastEvent + "\n" +
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
