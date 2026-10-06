// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: the red "Enter your access token" banner, shown in the keyboard and in settings
// while no token is saved or the server rejects the saved one.
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/**
 * Whether the access token needs the user: none saved, or the server answered 401 / 403 to it (from
 * dictation, from ✨, or from the light check here). The "rejected" flag lives in [DictationSettings].
 */
object SovereignToken {
    const val RED = 0xFFD81B3C.toInt()
    const val BANNER_TEXT = "Enter your access token"
    /** Intent extra for the settings activity: open the SovereignBoard screen, token field focused. */
    const val EXTRA_FOCUS_TOKEN = "sovereign_focus_token"
    private const val CHECK_EVERY_MS = 60 * 60 * 1000L

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    @Volatile private var checking = false

    private val _problem = MutableStateFlow(false)
    /** True while the banner should show. */
    val problem: StateFlow<Boolean> = _problem.asStateFlow()

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

    /** Settings saved: a new token isn't known to be rejected; check it with the server right away. */
    fun onTokenSaved(context: Context, tokenChanged: Boolean) {
        if (tokenChanged) DictationSettings(context).tokenRejected = false
        refresh(context)
        check(context)
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
    private fun check(context: Context) {
        val appContext = context.applicationContext
        val s = DictationSettings(appContext)
        val token = s.token
        val server = s.serverUrl
        if (token.isBlank() || server.isBlank() || checking) return
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
                        in 200..299 -> if (DictationSettings(appContext).token == token) onAccepted(appContext)
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

    /** The keyboard's banner: covers the suggestion strip while the problem lasts ([SovereignToolbar.observe] shows it). */
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
        setOnClickListener { openSettings(it.context) }
    }
}

/** The settings app's banner, along the bottom of a screen. */
@Composable
fun SovereignTokenBanner(onClick: () -> Unit) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { SovereignToken.refresh(ctx) }
    val problem by SovereignToken.problem.collectAsState()
    if (!problem) return
    Box(
        Modifier.fillMaxWidth()
            .background(androidx.compose.ui.graphics.Color(SovereignToken.RED))
            .clickable(onClick = onClick)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            SovereignToken.BANNER_TEXT,
            color = androidx.compose.ui.graphics.Color.White,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
        )
    }
}

/** [content] with the token banner along its bottom while the token needs the user. */
@Composable
fun WithTokenBanner(onBannerClick: () -> Unit, content: @Composable () -> Unit) {
    val problem by SovereignToken.problem.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.weight(1f)
                .then(if (problem) Modifier.consumeWindowInsets(WindowInsets.navigationBars) else Modifier)
        ) { content() }
        SovereignTokenBanner(onBannerClick)
    }
}
