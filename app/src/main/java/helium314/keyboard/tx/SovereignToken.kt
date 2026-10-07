// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: the red banner, shown in the keyboard (over the suggestion strip) and along the bottom
// of the settings screens: "Enter your access token" while no token is saved or the server rejects the
// saved one; "Your access token has been changed" for a few seconds after a new token checks out; and
// "Update available — tap to install" when a newer build is out. The token messages win.
package helium314.keyboard.tx

import android.content.Context
import android.content.Intent
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
    UPDATE("Update available — tap to install"),
}

/**
 * Whether the access token needs the user: none saved, or the server answered 401 / 403 to it (from
 * dictation, from ✨, or from the light check here). The "rejected" flag lives in [DictationSettings].
 */
object SovereignToken {
    const val RED = 0xFFD81B3C.toInt()
    const val BANNER_TEXT = "Enter your access token"
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

    private val _changedFlash = MutableStateFlow(false)

    /** Which banner shows now, if any: token trouble first, then the token-changed flash, then updates. */
    val banner: StateFlow<SovereignBanner?> =
        combine(_problem, _changedFlash, SovereignUpdates.available) { problem, changed, update ->
            when {
                problem -> SovereignBanner.TOKEN
                changed -> SovereignBanner.TOKEN_CHANGED
                update -> SovereignBanner.UPDATE
                else -> null
            }
        }.stateIn(scope, SharingStarted.Eagerly, null)

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
        val s = DictationSettings(context)
        if (s.tokenChangedPending) {
            s.tokenChangedPending = false
            if (!_problem.value) flashChanged()
        }
        SovereignUpdates.checkIfDue(context)
    }

    private fun flashChanged() {
        scope.launch {
            _changedFlash.value = true
            delay(CHANGED_FLASH_MS)
            _changedFlash.value = false
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

    /** Opens the SovereignBoard settings screen with the access token field focused. */
    fun openSettings(context: Context) {
        requestTokenFocus()
        val intent = Intent(context, SettingsActivity2::class.java)
            .putExtra(EXTRA_FOCUS_TOKEN, true)
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
            SovereignBanner.TOKEN_CHANGED, null -> Unit
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
        view.contentDescription = if (kind == SovereignBanner.TOKEN_CHANGED) kind.text else "${kind.text}. Opens settings."
    }
}

/**
 * The settings app's banner, along the bottom of a screen. [onOpenSovereign] goes to the SovereignBoard
 * screen (a no-op when already there).
 */
@Composable
fun SovereignTokenBanner(onOpenSovereign: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        SovereignToken.refresh(ctx)
        SovereignUpdates.checkIfDue(ctx)
    }
    val kind by SovereignToken.banner.collectAsState()
    val shown = kind ?: return
    Box(
        Modifier.fillMaxWidth()
            .background(androidx.compose.ui.graphics.Color(SovereignToken.RED))
            .clickable {
                when (shown) {
                    SovereignBanner.TOKEN -> { SovereignToken.requestTokenFocus(); onOpenSovereign() }
                    SovereignBanner.UPDATE -> { SovereignUpdates.requestInstall(); onOpenSovereign() }
                    SovereignBanner.TOKEN_CHANGED -> Unit
                }
            }
            .navigationBarsPadding()
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

/** [content] with the red banner along its bottom while there is something to say. */
@Composable
fun WithTokenBanner(onOpenSovereign: () -> Unit, content: @Composable () -> Unit) {
    val kind by SovereignToken.banner.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f)
                .then(if (kind != null) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)
        ) { content() }
        SovereignTokenBanner(onOpenSovereign)
    }
}
