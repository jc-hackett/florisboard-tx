// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: the red banner, shown in the keyboard (over the suggestion strip) and along the bottom
// of the settings screens: "Enter your access token" while no token is saved or the server rejects the
// saved one; "Your access token has been changed" for a few seconds after a new token checks out;
// "Key saved — you're ready" when a key copied from the setup page was picked up from the clipboard by itself;
// "Microphone not allowed — tap to allow" while the microphone permission is missing (in the keyboard only
// after the mic was tapped; at the top of the settings screens); and "Update available — tap to install"
// when a newer build is out. The token messages win, then the microphone, then updates.
package helium314.keyboard.tx

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.view.isVisible
import helium314.keyboard.settings.SettingsActivity2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/** What the red banner says, in order of priority. */
enum class SovereignBanner(val text: String) {
    TOKEN(SovereignToken.BANNER_TEXT),
    TOKEN_CHANGED("Your access token has been changed"),
    KEY_SAVED("Key saved — you're ready"),
    MIC(SovereignToken.MIC_TEXT),
    UPDATE("Update available — tap to install"),
}

/**
 * Whether the access token needs the user: none saved, or the server answered 401 / 403 to it (from
 * dictation, from ✨, or from the light check here). The "rejected" flag lives in [DictationSettings].
 */
object SovereignToken {
    const val RED = 0xFFD81B3C.toInt()
    const val BANNER_TEXT = "Enter your access token"
    const val MIC_TEXT = "Microphone not allowed — tap to allow"
    /** Intent extra for the settings activity: open the SovereignBoard screen. */
    const val EXTRA_OPEN_SOVEREIGN = "sovereign_open"
    /** Intent extra for the settings activity: open the SovereignBoard screen and ask for photo access. */
    const val EXTRA_PHOTO_ACCESS = "sovereign_photo_access"
    /** Intent extra for the settings activity: open the SovereignBoard screen, token field focused. */
    const val EXTRA_FOCUS_TOKEN = "sovereign_focus_token"
    /** Intent extra for the settings activity: open the SovereignBoard screen and install the update. */
    const val EXTRA_INSTALL_UPDATE = "sovereign_install_update"
    private const val CHECK_EVERY_MS = 60 * 60 * 1000L
    private const val CHANGED_FLASH_MS = 3_000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var checking = false

    private val _problem = MutableStateFlow(false)
    /** True while the banner should show. */
    val problem: StateFlow<Boolean> = _problem.asStateFlow()

    /** A short-lived message (token changed / key saved), shown for a few seconds. */
    private val _changedFlash = MutableStateFlow<SovereignBanner?>(null)

    private val _autoSaved = MutableStateFlow(0)
    /** Goes up each time a key was picked up from the clipboard and saved: the settings screen re-reads it. */
    val autoSaved: StateFlow<Int> = _autoSaved.asStateFlow()

    private val _micMissing = MutableStateFlow(false)
    /** True while the app lacks the microphone permission (re-read with [refreshMic]). */
    val micMissing: StateFlow<Boolean> = _micMissing.asStateFlow()
    /** The mic key was tapped without the permission: the keyboard's banner says so until it is granted. */
    private val _micAsked = MutableStateFlow(false)

    /**
     * Which banner the keyboard shows now, if any: token trouble first, then the token-changed flash, then
     * the microphone (only after the mic key was tapped without permission), then updates.
     */
    val banner: StateFlow<SovereignBanner?> =
        combine(_problem, _changedFlash, _micMissing, _micAsked, SovereignUpdates.available) { problem, changed, mic, asked, update ->
            pick(problem, changed, mic && asked, update)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    /** The same for the settings screens, where a missing microphone permission always shows. */
    val settingsBanner: StateFlow<SovereignBanner?> =
        combine(_problem, _changedFlash, _micMissing, SovereignUpdates.available) { problem, changed, mic, update ->
            pick(problem, changed, mic, update)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    private fun pick(problem: Boolean, flash: SovereignBanner?, mic: Boolean, update: Boolean) = when {
        problem -> SovereignBanner.TOKEN
        flash != null -> flash
        mic -> SovereignBanner.MIC
        update -> SovereignBanner.UPDATE
        else -> null
    }

    fun hasMic(context: Context) = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

    /** Re-reads the microphone permission (settings resumed, keyboard opened, permission answered). */
    @JvmStatic
    fun refreshMic(context: Context) {
        val missing = !hasMic(context)
        _micMissing.value = missing
        if (!missing) _micAsked.value = false
    }

    /** The mic key was tapped without the permission: show the microphone banner in the keyboard. */
    @JvmStatic
    fun onMicWithoutPermission(context: Context) {
        refreshMic(context)
        _micAsked.value = _micMissing.value
    }

    private val _focusToken = MutableStateFlow(false)
    /** Set when the banner was tapped: the SovereignBoard screen focuses the token field and resets it. */
    val focusToken: StateFlow<Boolean> = _focusToken.asStateFlow()
    fun requestTokenFocus() { _focusToken.value = true }
    fun tokenFocused() { _focusToken.value = false }

    /** Re-reads the saved state. */
    fun refresh(context: Context) {
        val s = DictationSettings(context)
        _problem.value = s.token.isBlank() || s.tokenRejected
    }

    /** The server answered 401 / 403 to the saved token. */
    fun onRejected(context: Context) {
        val s = DictationSettings(context)
        if (!s.tokenRejected) s.tokenRejected = true
        _problem.value = true
    }

    /** A call with the saved token succeeded. */
    fun onAccepted(context: Context) {
        val s = DictationSettings(context)
        if (s.tokenRejected) s.tokenRejected = false
        s.lastTokenCheck = System.currentTimeMillis()
        _problem.value = s.token.isBlank()
    }

    /**
     * Settings saved: a new token isn't known to be rejected; check it with the server right away.
     * If a changed token checks out, the banner says so for a few seconds here, and once more the next
     * time the keyboard opens.
     */
    fun onTokenSaved(context: Context, tokenChanged: Boolean) {
        if (tokenChanged) DictationSettings(context).tokenRejected = false
        refresh(context)
        check(context, announceChange = tokenChanged)
    }

    /** The keyboard opened (not a restart in the same field): token check, pending flash, update check. */
    @JvmStatic
    fun onKeyboardOpened(context: Context) {
        checkIfDue(context)
        autoFillFromClipboard(context) // the keyboard, as the default input method, may read the clipboard
        refreshMic(context)
        val s = DictationSettings(context)
        if (s.tokenChangedPending) {
            s.tokenChangedPending = false
            if (!_problem.value) flashChanged()
        }
        SovereignUpdates.checkIfDue(context)
    }

    private fun flashChanged(kind: SovereignBanner = SovereignBanner.TOKEN_CHANGED) {
        scope.launch {
            _changedFlash.value = kind
            delay(CHANGED_FLASH_MS)
            if (_changedFlash.value == kind) _changedFlash.value = null
        }
    }

    /** Keyboard start or settings screen opened: a light check against the server, at most hourly. */
    @JvmStatic
    fun checkIfDue(context: Context) {
        refresh(context)
        val s = DictationSettings(context)
        if (s.token.isBlank()) return
        if (abs(System.currentTimeMillis() - s.lastTokenCheck) < CHECK_EVERY_MS) return
        check(context)
    }

    /** GET /v1/words with the token: 401 / 403 means rejected, 2xx accepted; anything else tells nothing. */
    private fun check(context: Context, announceChange: Boolean = false) {
        val appContext = context.applicationContext
        val s = DictationSettings(appContext)
        val token = s.token
        val server = s.serverUrl
        if (token.isBlank() || server.isBlank() || (checking && !announceChange)) return
        checking = true
        scope.launch {
            try {
                s.lastTokenCheck = System.currentTimeMillis()
                val conn = (URL(server.trimEnd('/') + "/v1/words").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 8_000
                    useCaches = false
                    setRequestProperty("Authorization", "Bearer $token")
                }
                try {
                    when (conn.responseCode) {
                        401, 403 -> if (DictationSettings(appContext).token == token) onRejected(appContext)
                        in 200..299 -> if (DictationSettings(appContext).token == token) {
                            onAccepted(appContext)
                            readServerWords(conn)?.let { DictationSettings(appContext).serverWords = it }
                            if (announceChange) {
                                DictationSettings(appContext).tokenChangedPending = true
                                flashChanged()
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                // offline or server down: says nothing about the token
            } finally {
                checking = false
            }
        }
    }

    /** The server's word list from a GET /v1/words reply, one per line; null if it can't be read. */
    private fun readServerWords(conn: HttpURLConnection): String? = runCatching {
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        val list = org.json.JSONObject(body).optJSONArray("words") ?: return null
        (0 until list.length()).mapNotNull { list.optString(it).trim().takeIf { w -> w.isNotEmpty() } }
            .take(2000).joinToString("\n")
    }.getOrNull()

    /**
     * Shape of a SovereignBoard key: "dt_" + secrets.token_urlsafe(32) on the server (make-invite.py,
     * add-token.py, the admin page), i.e. 43 url-safe characters after the prefix.
     */
    private val KEY_SHAPE = Regex("^dt_[A-Za-z0-9_-]{43}$")
    /** Clipboard keys already tried and refused by the server, so they aren't sent again and again. */
    private val refusedKeys = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    @Volatile private var autoFilling = false

    /** A SovereignBoard key on the clipboard, if that is what it holds (null if unreadable). */
    private fun clipboardKey(context: Context): String? = runCatching {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java) ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        clip.getItemAt(0).coerceToText(context)?.toString()?.trim()?.takeIf { KEY_SHAPE.matches(it) }
    }.getOrNull()

    /**
     * Key auto-fill. When no key is saved, or the server refused the saved one, and the clipboard holds a
     * SovereignBoard key: check it with the server (GET /v1/words) and, if it is good, save it and say
     * "Key saved — you're ready". A key that works is never replaced this way. Needs the caller to be
     * allowed to read the clipboard (Android 10+: the focused app, or the default keyboard).
     */
    @JvmStatic
    fun autoFillFromClipboard(context: Context) {
        val appContext = context.applicationContext
        val s = DictationSettings(appContext)
        if (s.token.isNotBlank() && !s.tokenRejected) return
        if (autoFilling) return
        val candidate = clipboardKey(context) ?: return
        if (candidate == s.token || candidate in refusedKeys) return
        val server = s.serverUrl
        if (!server.startsWith("https://")) return
        autoFilling = true
        scope.launch {
            try {
                val conn = (URL(server.trimEnd('/') + "/v1/words").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 5_000
                    readTimeout = 8_000
                    useCaches = false
                    setRequestProperty("Authorization", "Bearer $candidate")
                }
                try {
                    when (conn.responseCode) {
                        401, 403 -> refusedKeys.add(candidate)
                        in 200..299 -> {
                            val words = readServerWords(conn)
                            val now = DictationSettings(appContext)
                            // still needed? (a key may have been saved meanwhile)
                            if (now.token.isBlank() || now.tokenRejected) {
                                now.token = candidate
                                now.tokenRejected = false
                                now.lastTokenCheck = System.currentTimeMillis()
                                if (words != null) now.serverWords = words
                                _problem.value = false
                                _autoSaved.value += 1
                                flashChanged(SovereignBanner.KEY_SAVED)
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.widget.Toast.makeText(appContext, SovereignBanner.KEY_SAVED.text,
                                        android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                // offline: try again next time
            } finally {
                autoFilling = false
            }
        }
    }

    /** Opens the SovereignBoard settings screen with the access token field focused. */
    fun openSettings(context: Context) {
        requestTokenFocus()
        val intent = Intent(context, SettingsActivity2::class.java)
            .putExtra(EXTRA_FOCUS_TOKEN, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(intent) }
    }

    /** Opens the SovereignBoard settings screen, with [extra] (if any) set. */
    fun openSovereign(context: Context, extra: String = EXTRA_OPEN_SOVEREIGN) {
        val intent = Intent(context, SettingsActivity2::class.java)
            .putExtra(EXTRA_OPEN_SOVEREIGN, true)
            .putExtra(extra, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(intent) }
    }

    /** Opens the SovereignBoard settings screen and starts installing the update. */
    fun openUpdate(context: Context) {
        SovereignUpdates.requestInstall()
        val intent = Intent(context, SettingsActivity2::class.java)
            .putExtra(EXTRA_INSTALL_UPDATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        runCatching { context.startActivity(intent) }
    }

    /** A tap on the keyboard's banner. */
    private fun onKeyboardBannerClick(context: Context) {
        when (banner.value) {
            SovereignBanner.TOKEN -> openSettings(context)
            SovereignBanner.UPDATE -> openUpdate(context)
            SovereignBanner.MIC -> openSovereign(context) // the keyboard can't ask for a permission itself
            SovereignBanner.TOKEN_CHANGED, SovereignBanner.KEY_SAVED, null -> Unit
        }
    }

    /**
     * The keyboard's banner: covers the suggestion strip while [banner] has something to say
     * ([SovereignToolbar.observe] shows it and sets the text).
     */
    fun createKeyboardBanner(context: Context): TextView = TextView(context).apply {
        text = BANNER_TEXT
        setTextColor(Color.WHITE)
        setBackgroundColor(RED)
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTypeface(typeface, Typeface.BOLD)
        isClickable = true
        isFocusable = true
        translationZ = 100f // above the pinned keys
        contentDescription = "$BANNER_TEXT. Opens settings."
        isVisible = false
        setOnClickListener { onKeyboardBannerClick(it.context) }
    }

    /** Shows [kind] on a banner made by [createKeyboardBanner], or hides it. */
    fun showOnKeyboardBanner(view: TextView, kind: SovereignBanner?) {
        view.isVisible = kind != null
        if (kind == null) return
        view.text = kind.text
        view.contentDescription = if (kind == SovereignBanner.TOKEN_CHANGED || kind == SovereignBanner.KEY_SAVED) kind.text
            else "${kind.text}. Opens settings."
    }
}

/**
 * The settings app's banner: along the bottom of a screen for the token and update messages, at the top
 * for the microphone. [onOpenSovereign] goes to the SovereignBoard screen (a no-op when already there).
 */
@Composable
fun SovereignTokenBanner(onOpenSovereign: () -> Unit, atTop: Boolean) {
    val ctx = LocalContext.current
    val kind by SovereignToken.settingsBanner.collectAsState()
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        SovereignToken.refreshMic(ctx)
        // Refused for good: Android won't ask again, so open the app's permission page instead.
        val activity = ctx.findActivity()
        if (!granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.RECORD_AUDIO)) {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(intent) }
        }
    }
    val shown = kind ?: return
    if ((shown == SovereignBanner.MIC) != atTop) return
    Box(
        Modifier.fillMaxWidth()
            .background(androidx.compose.ui.graphics.Color(SovereignToken.RED))
            .clickable {
                when (shown) {
                    SovereignBanner.TOKEN -> { SovereignToken.requestTokenFocus(); onOpenSovereign() }
                    SovereignBanner.UPDATE -> { SovereignUpdates.requestInstall(); onOpenSovereign() }
                    SovereignBanner.MIC -> micLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    SovereignBanner.TOKEN_CHANGED, SovereignBanner.KEY_SAVED -> Unit
                }
            }
            .then(if (atTop) Modifier.statusBarsPadding() else Modifier.navigationBarsPadding())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            shown.text,
            color = androidx.compose.ui.graphics.Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** [content] with the red banner along its bottom (for the microphone: its top) while there is something to say. */
@Composable
fun WithTokenBanner(onOpenSovereign: () -> Unit, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        SovereignToken.refresh(ctx)
        SovereignUpdates.checkIfDue(ctx)
    }
    // Key auto-fill: whenever a settings screen has the focus (opened, resumed, back from another app),
    // look for a key on the clipboard. Android 10+ only lets the focused app read it, so wait for focus.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        if (windowFocused) SovereignToken.autoFillFromClipboard(ctx)
    }
    // the microphone banner goes as soon as the permission is granted, also from the system's settings page
    LifecycleResumeEffect(Unit) {
        SovereignToken.refreshMic(ctx)
        onPauseOrDispose { }
    }
    val kind by SovereignToken.settingsBanner.collectAsState()
    Column(Modifier.fillMaxSize()) {
        SovereignTokenBanner(onOpenSovereign, atTop = true)
        Box(
            Modifier.weight(1f)
                .then(if (kind == SovereignBanner.MIC) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier)
                .then(if (kind != null && kind != SovereignBanner.MIC) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)
        ) { content() }
        SovereignTokenBanner(onOpenSovereign, atTop = false)
    }
}
