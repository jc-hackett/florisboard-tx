// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: "AI training & privacy" settings screen — the voice-training switch, saved recordings,
// and a plain summary of what leaves the phone. Also registers SovereignBoard's entries in settings search.
package helium314.keyboard.tx

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import helium314.keyboard.latin.R
import helium314.keyboard.latin.utils.NextScreenIcon
import helium314.keyboard.settings.SearchSettingsScreen
import helium314.keyboard.settings.Setting
import helium314.keyboard.settings.SettingsDestination
import helium314.keyboard.settings.preferences.Preference
import kotlinx.coroutines.launch

private const val PRIVACY_PAGE = "https://dictate.limn.dev/app/privacy.html"

@Composable
fun PrivacyScreen(onClickBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { DictationSettings(ctx) }
    val scope = rememberCoroutineScope()

    // "Save my recordings": opt-in kept recordings on the server (moved here from SovereignScreen).
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

    LaunchedEffect(Unit) { refreshKept() }

    SearchSettingsScreen(
        onClickBack = onClickBack,
        title = ctx.getString(R.string.sovereign_privacy_title),
        settings = emptyList(),
    ) {
        Scaffold(contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)) { innerPadding ->
            Column(
                Modifier.verticalScroll(rememberScrollState())
                    .then(Modifier.padding(innerPadding))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
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

                Text("What leaves your phone", style = MaterialTheme.typography.titleMedium)
                LeavesItem(
                    "Dictation",
                    "Your voice goes to Jeremiah's server and is turned into text. It is not kept, " +
                        "unless the switch above is on.",
                )
                LeavesItem(
                    "✨ cleanup",
                    "The text goes to Claude to be tidied. Names are hidden first.",
                )
                LeavesItem(
                    "Fact check",
                    "The text goes to Claude, which searches the web. Names are NOT hidden, so don't " +
                        "use it on client details.",
                )
                LeavesItem(
                    "Your typing",
                    "What the keyboard learns from your typing stays on your phone.",
                )

                OutlinedButton(
                    onClick = { openPrivacyPage(ctx) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Read the full privacy page") }
            }
        }
    }
}

@Composable
private fun LeavesItem(title: String, text: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun openPrivacyPage(ctx: Context) {
    runCatching {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * SovereignBoard's entries in HeliBoard's settings search: each one opens the screen it lives on,
 * so searching "recordings", "privacy", "training", "voice" or "dictation" finds them.
 */
fun createSovereignSettings(context: Context) = listOf(
    searchEntry(context, "sovereign_screen", R.string.sovereign_screen_title, R.string.sovereign_screen_summary,
        R.drawable.sym_keyboard_voice_rounded, SettingsDestination.Sovereign),
    searchEntry(context, "sovereign_privacy", R.string.sovereign_privacy_title, R.string.sovereign_privacy_summary,
        R.drawable.ic_settings_privacy, SettingsDestination.Privacy),
    searchEntry(context, "sovereign_keep_recordings", R.string.sovereign_keep_recordings,
        R.string.sovereign_keep_recordings_summary, R.drawable.ic_settings_privacy, SettingsDestination.Privacy),
    searchEntry(context, "sovereign_delete_recordings", R.string.sovereign_delete_recordings,
        R.string.sovereign_delete_recordings_summary, R.drawable.ic_settings_privacy, SettingsDestination.Privacy),
)

private fun searchEntry(context: Context, key: String, titleId: Int, descriptionId: Int, icon: Int, target: String) =
    Setting(context, key, titleId, descriptionId) {
        Preference(
            name = it.title,
            description = it.description,
            onClick = { SettingsDestination.navigateTo(target) },
            icon = icon,
        ) { NextScreenIcon() }
    }
