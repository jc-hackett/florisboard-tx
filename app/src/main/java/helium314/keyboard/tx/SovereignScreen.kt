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
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
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

    LaunchedEffect(Unit) { checkForUpdate() }

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
                    modifier = Modifier.fillMaxWidth(),
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
                        settings.serverUrl = server
                        settings.token = token
                        settings.words = words
                        server = settings.serverUrl
                        Toast.makeText(ctx, "Saved", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save") }

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
