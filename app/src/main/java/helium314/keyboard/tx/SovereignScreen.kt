// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: settings screen for server dictation.
package helium314.keyboard.tx

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import helium314.keyboard.settings.SearchSettingsScreen
import kotlinx.coroutines.launch

@Composable
fun SovereignScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { DictationSettings(ctx) }
    var server by remember { mutableStateOf(settings.serverUrl) }
    var token by remember { mutableStateOf(settings.token) }
    var words by remember { mutableStateOf(settings.words) }
    var autoSparkle by remember { mutableStateOf(settings.autoCleanupOnPeriod) }
    var copyDictation by remember { mutableStateOf(settings.addToClipboardHistory) }
    var micGranted by remember { mutableStateOf(hasMic(ctx)) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micGranted = granted || hasMic(ctx)
        if (!micGranted) openAppSettings(ctx)
    }

    // Keyboard updates (same flow as the FlorisBoard edition's AppUpdater)
    val updater = remember { AppUpdater(ctx) }
    val scope = rememberCoroutineScope()
    var release by remember { mutableStateOf<AppUpdater.Release?>(null) }
    var updateBusy by remember { mutableStateOf(false) }
    var updateStatus by remember { mutableStateOf("This version: ${updater.currentBuild.take(8)}. Tap to check.") }

    fun checkForUpdate() {
        if (updateBusy) return
        updateBusy = true
        updateStatus = "Checking…"
        scope.launch {
            try {
                release = updater.check()
                updateStatus = release?.let { "Version ${it.short} is ready." }
                    ?: "You have the latest version (${updater.currentBuild.take(8)})."
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

    // "Help it learn your voice": opt-in kept recordings on the server.
    var keepRecordings by remember { mutableStateOf(settings.keepRecordings) }
    var keptStatus by remember { mutableStateOf("") }
    var keepBusy by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun keptText(n: Int, fixes: Int) =
        "${if (n == 1) "1 recording" else "$n recordings"} · ${if (fixes == 1) "1 fix" else "$fixes fixes"} saved"

    fun refreshKept() {
        if (settings.token.isBlank()) { keptStatus = ""; return }
        scope.launch {
            keptStatus = try {
                KeptRecordings.count(ctx).let { (n, fixes) -> keptText(n, fixes) }
            } catch (e: DictationException) {
                "Couldn't check saved recordings: ${e.userMessage}"
            }
        }
    }

    fun deleteKept() {
        if (keepBusy) return
        keepBusy = true
        keptStatus = "Deleting…"
        scope.launch {
            try {
                val n = KeptRecordings.deleteAll(ctx)
                keptStatus = if (n == 1) "Deleted 1 recording. ${keptText(0, 0)}."
                    else "Deleted $n recordings. ${keptText(0, 0)}."
            } catch (e: DictationException) {
                keptStatus = "Couldn't delete: ${e.userMessage}"
            } finally {
                keepBusy = false
            }
        }
    }

    LaunchedEffect(Unit) {
        SovereignToken.checkIfDue(ctx)
        checkForUpdate()
        refreshKept()
    }

    // Opened from the red token banner: put the cursor in the Access token field, keyboard up.
    val tokenFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusTokenRequested by SovereignToken.focusToken.collectAsState()
    LaunchedEffect(focusTokenRequested) {
        if (!focusTokenRequested) return@LaunchedEffect
        delay(350) // let the screen finish sliding in
        runCatching { tokenFocus.requestFocus() }
        keyboard?.show()
        SovereignToken.tokenFocused()
    }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = "SovereignBoard",
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier.verticalScroll(rememberScrollState())
                    .then(Modifier.padding(innerPadding))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Keyboard updates", style = MaterialTheme.typography.titleMedium)
                val r = release
                Button(
                    onClick = { if (r != null) installUpdate(r) else checkForUpdate() },
                    enabled = !updateBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        when {
                            updateBusy -> "Working…"
                            r != null -> "Update available — install"
                            updateStatus.startsWith("You have") -> "Up to date — check again"
                            else -> "Check for updates"
                        }
                    )
                }
                Text(updateStatus, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://dictate.limn.dev/app/whatsnew-h.html"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("What's new") }

                Text("Dictation", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = server,
                    onValueChange = { server = it },
                    label = { Text("Server address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Access token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().focusRequester(tokenFocus),
                )
                OutlinedTextField(
                    value = words,
                    onValueChange = { words = it },
                    label = { Text("Words to spell right (one per line)") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        val tokenChanged = token.trim() != settings.token
                        settings.serverUrl = server
                        settings.token = token
                        settings.words = words
                        server = settings.serverUrl
                        SovereignToken.onTokenSaved(ctx, tokenChanged)
                        Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save") }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Add dictation to clipboard history", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Each dictation is also added to the keyboard's clipboard history, without " +
                                "copying it. Never in password fields or incognito mode.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(
                        checked = copyDictation,
                        onCheckedChange = { copyDictation = it; settings.addToClipboardHistory = it },
                    )
                }

                // Clipboard check: what happened to the last copy (privacy-safe, see ClipboardCheck)
                Text("Clipboard check", style = MaterialTheme.typography.titleMedium)
                var clipCheck by remember { mutableStateOf(ClipboardCheck.summary(ctx)) }
                Text(clipCheck, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { clipCheck = ClipboardCheck.summary(ctx) }) { Text("Refresh") }

                Text("Help it learn your voice", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Save my recordings to train on my voice", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Your recordings are kept encrypted on Jeremiah's server and used only to make " +
                                "recognition better for you. Turn off any time.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(
                        checked = keepRecordings,
                        onCheckedChange = { keepRecordings = it; settings.keepRecordings = it },
                    )
                }
                if (keptStatus.isNotEmpty()) {
                    Text(keptStatus, style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedButton(
                    onClick = { confirmDelete = true },
                    enabled = !keepBusy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete my recordings") }
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("Delete my recordings?") },
                        text = { Text("This deletes every recording saved on the server for you. It can't be undone.") },
                        confirmButton = {
                            TextButton(onClick = { confirmDelete = false; deleteKept() }) { Text("Delete") }
                        },
                        dismissButton = {
                            TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
                        },
                    )
                }

                Text("AI cleanup", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.weight(1f)) {
                        Text("Auto-sparkle", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "After a space following . ? ! : ; … or a dash, tidy the paragraph you just finished. " +
                                "The ✨ key in the toolbar tidies a selection or the whole box on demand.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Switch(
                        checked = autoSparkle,
                        onCheckedChange = { autoSparkle = it; settings.autoCleanupOnPeriod = it },
                    )
                }

                Text("Microphone", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (micGranted) "Microphone permission: granted"
                    else "Microphone permission: not granted. Dictation needs it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!micGranted) {
                    OutlinedButton(
                        onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Allow microphone") }
                }
                OutlinedButton(
                    onClick = { openAppSettings(ctx) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Open app permissions") }
            }
        }
    }
}

private fun hasMic(ctx: Context) =
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(intent) }
}
