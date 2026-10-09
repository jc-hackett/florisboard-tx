// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: the fact-check key and its panel.
package helium314.keyboard.tx

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Base64
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.InputTypeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * The fact-check toolbar key: sends the selected text (or, with nothing selected, the paragraph
 * around the cursor) to the user's own server (POST /v1/factcheck), which asks Claude with web search.
 * The answer shows in a panel that takes the place of the keys: a coloured verdict, a few short
 * bullets and the sources (tap to open). Insert puts a one-line summary at the cursor; Copy copies
 * the whole answer; Close, the X or Back brings the keys back.
 *
 * Screenshots (developer mode only, see [DeveloperMode]; otherwise fact check is text-only and shows
 * no screenshot choice or tip): if one was taken in the last three minutes (and photo access is granted), the panel
 * first asks "Check your screenshot or your text?" - the screenshot (downscaled to 1568 px, JPEG, in
 * memory only) lets Claude check the whole conversation, including what other people wrote. Without
 * one, the panel shows a tip to take a screenshot first.
 *
 * Names are NOT hidden for this feature (the server sends the text as it is, since checking a claim
 * needs the real names); the first use shows a one-time note saying so, and that screenshots show
 * other people's messages. Inert in incognito mode and in password fields.
 */
object FactCheck {
    private const val MAX_READ = 4000
    /** Must match the server's FACTCHECK_MAX_CHARS. */
    private const val SEND_MAX = 2000

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var panel: Panel? = null

    private val _busy = MutableStateFlow(false)
    /** True while a check is waiting for the server; the toolbar key shows a breathing dot. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun open(ime: LatinIME) {
        val settings = DictationSettings(ime)
        val inputType = ime.currentInputEditorInfo?.inputType ?: 0
        if (InputTypeUtils.isAnyPasswordInputType(inputType)) return toast(ime, "Fact check doesn't read password fields")
        if (Settings.getValues().mIncognitoModeEnabled) return toast(ime, "Fact check is off in incognito mode")
        if (settings.serverUrl.isBlank() || settings.token.isBlank())
            return toast(ime, "Fact check: add the server address and token in Settings, SovereignBoard")
        val ic = ime.currentInputConnection ?: return
        val selected = ic.getSelectedText(0)?.toString().orEmpty()
        val text = if (selected.isNotBlank()) selected.trim() else {
            val before = ic.getTextBeforeCursor(MAX_READ, 0)?.toString().orEmpty()
            val after = ic.getTextAfterCursor(MAX_READ, 0)?.toString().orEmpty()
            (before.substringAfterLast('\n') + after.substringBefore('\n')).trim()
        }
        // Screenshot checks are a developer feature (unlocked with the PIN in Settings, SovereignBoard;
        // the server enforces it). Without developer mode, fact check is the text-only version.
        val dev = DeveloperMode.isOn(settings)
        job?.cancel()
        job = scope.launch {
            val ctx = ime.applicationContext
            val shot = if (!dev) null
                else withContext(Dispatchers.IO) { runCatching { SovereignScreenshots.freshForFactCheck(ctx) }.getOrNull() }
            if (shot == null) {
                if (text.isEmpty()) return@launch toast(ime, "Fact check: nothing to check. Type or select some text first.")
                if (text.length > SEND_MAX)
                    return@launch toast(ime, "Fact check: that's too long (about 300 words at most). Select a part and tap again.")
            }
            val container = ime.sovereignShowFactCheckPanel() ?: return@launch
            val p = Panel(ime, container, settings)
            panel = p
            val proceed = {
                if (shot == null) {
                    if (dev) p.showTip(SovereignScreenshots.hasPhotoAccess(ctx))
                    start(p, text, null)
                } else {
                    val textOk = text.isNotEmpty() && text.length <= SEND_MAX
                    p.showChoice(shot.thumb, text, textOk,
                        onShot = { start(p, if (textOk) text else "", shot.uri) },
                        onText = { start(p, text, null) })
                }
            }
            if (!dev) {
                if (settings.factCheckNoteSeen) proceed()
                else p.showNote(withScreenshots = false) { settings.factCheckNoteSeen = true; proceed() }
            } else if (settings.factCheckNoteSeen && settings.factCheckShotNoteSeen) proceed()
            else p.showNote(withScreenshots = true) { settings.factCheckNoteSeen = true; settings.factCheckShotNoteSeen = true; proceed() }
        }
    }

    /** Called by the keyboard whenever the panel goes away (X, Close, Back, keyboard hidden). */
    fun onPanelHidden() {
        job?.cancel()
        job = null
        panel = null
        _busy.value = false
    }

    /** [shot]: check that screenshot (with [text], if any, as extra context); null checks [text]. */
    private fun start(p: Panel, text: String, shot: Uri?) {
        p.showLoading(shot != null)
        _busy.value = true
        job = scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val ctx = p.ime.applicationContext
                    val image = shot?.let {
                        SovereignScreenshots.jpegForUpload(ctx, it)
                            ?: throw CheckFailed("Couldn't read the screenshot. Try taking it again.")
                    }
                    request(ctx, p.settings, text, image)
                }
                if (panel === p) p.showResult(result)
            } catch (e: CheckFailed) {
                if (panel === p) p.showError(e.message ?: "Fact check failed.")
            } catch (e: IOException) {
                if (panel === p) p.showError("Couldn't reach the server. Check your connection and try again.")
            } catch (e: Exception) {
                if (panel === p) p.showError("Fact check failed. Try again.")
            } finally {
                if (panel === p || panel == null) _busy.value = false
            }
        }
        scope.launch { // the "N left this month" line, while the check runs
            val left = withContext(Dispatchers.IO) { runCatching { remaining(p.settings) }.getOrNull() }
            if (left != null && panel === p && !p.hasResult) p.showRemaining(left)
        }
    }

    class Result(val verdict: String, val answer: String, val sources: List<Pair<String, String>>, val remaining: Int?,
                 val claims: List<String> = emptyList())
    private class CheckFailed(message: String) : Exception(message)

    private fun connect(settings: DictationSettings, method: String): HttpURLConnection =
        (URL(settings.serverUrl.trimEnd('/') + "/v1/factcheck").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = 100_000 // searching the web can take a while; the server gives up after 90 s
            setRequestProperty("Authorization", "Bearer ${settings.token}")
        }

    private fun remaining(settings: DictationSettings): Int? {
        val conn = connect(settings, "GET")
        try {
            if (conn.responseCode !in 200..299) return null
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() }).optInt("remaining", -1).takeIf { it >= 0 }
        } finally {
            conn.disconnect()
        }
    }

    private fun request(context: Context, settings: DictationSettings, text: String, image: ByteArray?): Result {
        val payload = JSONObject().put("text", text)
        if (image != null) payload.put("image", Base64.encodeToString(image, Base64.NO_WRAP)).put("media_type", "image/jpeg")
        val bytes = payload.toString().toByteArray()
        val conn = connect(settings, "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setFixedLengthStreamingMode(bytes.size)
            if (image != null) setRequestProperty("X-Dev-Token", settings.devToken)
        }
        try {
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val reason = runCatching {
                    JSONObject(conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "{}").optString("detail")
                }.getOrDefault("")
                // 403 on a screenshot = the server no longer accepts developer mode here (bad key is 401)
                if (code == 403 && image != null) {
                    settings.devToken = ""
                    throw CheckFailed("Screenshot check is a developer feature. Developer mode is now locked; " +
                        "unlock it again in Settings, SovereignBoard.")
                }
                throw CheckFailed(when (code) {
                    401, 403 -> { SovereignToken.onRejected(context); "The server didn't accept your access token." }
                    413 -> if (image != null) "That screenshot is too big to check."
                        else "That's too long to check (about 300 words at most). Select a part and try again."
                    415 -> "That screenshot couldn't be read. Try taking it again."
                    429 -> reason.ifBlank { "You've reached the fact-check limit. Try again later." }
                    503 -> "Fact check isn't switched on on the server."
                    else -> "The server had a problem ($code). Try again."
                })
            }
            SovereignToken.onAccepted(context)
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val sources = mutableListOf<Pair<String, String>>()
            o.optJSONArray("sources")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optJSONObject(i) ?: continue
                    val url = s.optString("url")
                    if (url.startsWith("http")) sources += (s.optString("title").ifBlank { url } to url)
                }
            }
            val claims = mutableListOf<String>()
            o.optJSONArray("claims")?.let { arr ->
                for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotEmpty() }?.let { claims += it }
            }
            return Result(o.optString("verdict").ifBlank { "Can't verify" }, o.optString("answer"), sources,
                if (o.has("remaining")) o.optInt("remaining") else null, claims)
        } finally {
            conn.disconnect()
        }
    }

    private fun toast(context: Context, text: String) {
        mainHandler.post { Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show() }
    }

    /** The panel's views, built in code inside the keyboard's [container]. */
    private class Panel(val ime: LatinIME, val container: FrameLayout, val settings: DictationSettings) {
        private val ctx = container.context
        private val colors = Settings.getValues().mColors
        private val textColor = colors.get(ColorType.KEY_TEXT) or 0xFF000000.toInt()
        private val hintColor = colors.get(ColorType.KEY_HINT_TEXT) or 0xFF000000.toInt()
        private val keyColor = colors.get(ColorType.KEY_BACKGROUND) or 0xFF000000.toInt()
        private val badgeColor = colors.get(ColorType.FUNCTIONAL_KEY_BACKGROUND) or 0xFF000000.toInt()
        private val remainingView = text("", 13f, hintColor)
        private val tipView = text("", 13f, hintColor).apply { visibility = View.GONE; setPadding(0, 0, 0, dp(2)) }
        private val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        private val scroll = ScrollView(ctx).apply { isFillViewport = true; addView(body) }
        private val insertButton = button("Insert", primary = true) { insert() }
        private val copyButton = button("Copy", primary = false) { copy() }
        private var result: Result? = null
        val hasResult get() = result != null

        init {
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(6), dp(12), dp(8))
            }
            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(text("Fact check", 16f, textColor, bold = true))
                addView(remainingView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(10)
                })
                addView(text("✕", 20f, textColor).apply {
                    gravity = Gravity.CENTER
                    contentDescription = "Close fact check"
                    setOnClickListener { close() }
                }, LinearLayout.LayoutParams(dp(44), dp(40)))
            }
            root.addView(header)
            root.addView(tipView)
            root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val buttons = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(6), 0, 0)
                addView(insertButton, weighted())
                addView(copyButton, weighted())
                addView(button("Close", primary = false) { close() }, weighted())
            }
            root.addView(buttons)
            container.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setActionsEnabled(false)
        }

        fun showNote(withScreenshots: Boolean, onOk: () -> Unit) {
            body.removeAllViews()
            body.addView(text(if (withScreenshots) "Fact check sends your text or screenshot to Claude with web search. Names aren't hidden — " +
                    "don't use it for client details. Screenshots can show other people's messages and names — " +
                    "don't use this on client conversations."
                else "Fact check sends this text to Claude with web search. Names aren't hidden — " +
                    "don't use it for client details.", 15f, textColor).apply { setPadding(0, dp(10), 0, dp(12)) })
            body.addView(button("OK", primary = true) { onOk() }, LinearLayout.LayoutParams(dp(120), dp(44)))
        }

        /** No fresh screenshot: a one-line hint that a screenshot checks the whole conversation. */
        fun showTip(hasPhotoAccess: Boolean) {
            tipView.text = if (hasPhotoAccess) "Tip: take a screenshot first to check the whole conversation."
                else "Tip: allow photo access (Settings, SovereignBoard), then take a screenshot to check the whole conversation."
            tipView.visibility = View.VISIBLE
        }

        /** "Check your screenshot or your text?" - two big buttons, the screenshot one first and highlighted. */
        fun showChoice(thumb: Bitmap?, text: String, textOk: Boolean, onShot: () -> Unit, onText: () -> Unit) {
            body.removeAllViews()
            body.addView(text("Check your screenshot or your text?", 15f, textColor, bold = true).apply {
                setPadding(0, dp(6), 0, dp(8))
            })
            val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            val shotButton = bigButton(primary = true, onClick = onShot).apply {
                contentDescription = "Check your screenshot"
                if (thumb != null) addView(ImageView(ctx).apply {
                    setImageBitmap(thumb)
                    scaleType = ImageView.ScaleType.CENTER_CROP
                }, LinearLayout.LayoutParams(dp(44), dp(60)).apply { marginEnd = dp(10) })
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("Screenshot", 16f, Color.WHITE, bold = true))
                    addView(text("The whole conversation", 12f, Color.WHITE).apply { alpha = 0.85f })
                })
            }
            val preview = when {
                text.isEmpty() -> "Nothing typed or selected"
                !textOk -> "Too long - select a part"
                else -> text.replace('\n', ' ').let { if (it.length > 40) it.take(40).trimEnd() + "…" else it }
            }
            val textButton = bigButton(primary = false, onClick = onText).apply {
                contentDescription = "Check your text"
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(text("Your text", 16f, textColor, bold = true))
                    addView(text(preview, 12f, hintColor).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
                })
                isEnabled = textOk
                alpha = if (textOk) 1f else 0.45f
            }
            row.addView(shotButton, LinearLayout.LayoutParams(0, dp(84), 1f).apply { marginEnd = dp(5) })
            row.addView(textButton, LinearLayout.LayoutParams(0, dp(84), 1f).apply { marginStart = dp(5) })
            body.addView(row)
        }

        private fun bigButton(primary: Boolean, onClick: () -> Unit) = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (primary) SovereignTheme.ENTER else if (badgeColor != keyColor) badgeColor else keyColor)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        fun showLoading(screenshot: Boolean = false) {
            body.removeAllViews()
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(16), 0, 0)
                addView(ProgressBar(ctx), LinearLayout.LayoutParams(dp(32), dp(32)))
                addView(text(if (screenshot) "Reading the screenshot and checking the web…" else "Checking the web…", 15f, hintColor).apply { setPadding(dp(12), 0, 0, 0) })
            }
            body.addView(row)
        }

        fun showRemaining(left: Int) {
            remainingView.text = "$left left this month"
        }

        fun showError(message: String) {
            body.removeAllViews()
            body.addView(text(message, 15f, VERDICT_RED).apply { setPadding(0, dp(12), 0, 0) })
        }

        fun showResult(r: Result) {
            result = r
            body.removeAllViews()
            r.remaining?.let { showRemaining(it) }
            body.addView(text(r.verdict, 26f, verdictColor(r.verdict), bold = true).apply { setPadding(0, dp(4), 0, dp(4)) })
            if (r.claims.isNotEmpty()) {
                body.addView(text("Checked", 13f, hintColor, bold = true).apply { setPadding(0, 0, 0, dp(2)) })
                body.addView(text(r.claims.joinToString("\n") { "• $it" }, 14f, hintColor).apply {
                    setLineSpacing(0f, 1.1f)
                    setPadding(0, 0, 0, dp(8))
                })
            }
            if (r.answer.isNotBlank())
                body.addView(text(r.answer.replace(Regex("(?m)^- "), "• "), 15f, textColor).apply {
                    setLineSpacing(0f, 1.15f)
                    setTextIsSelectable(false)
                })
            if (r.sources.isNotEmpty()) {
                body.addView(text("Sources", 13f, hintColor, bold = true).apply { setPadding(0, dp(10), 0, dp(2)) })
                for ((title, url) in r.sources) {
                    body.addView(text(title, 14f, SovereignTheme.ENTER).apply {
                        paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                        maxLines = 2
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, dp(5), 0, dp(5))
                        contentDescription = "$title, opens in your browser"
                        setOnClickListener { openLink(url) }
                    })
                }
            }
            setActionsEnabled(true)
        }

        private fun setActionsEnabled(on: Boolean) {
            insertButton.isEnabled = on
            copyButton.isEnabled = on
            insertButton.alpha = if (on) 1f else 0.4f
            copyButton.alpha = if (on) 1f else 0.4f
        }

        private fun bullets(r: Result) = r.answer.lines().map { it.trim().removePrefix("-").removePrefix("•").trim() }.filter { it.isNotEmpty() }

        private fun insert() {
            val r = result ?: return
            val parts = StringBuilder("Fact check: ").append(r.verdict)
            bullets(r).firstOrNull()?.let { parts.append(" — ").append(it) }
            r.sources.firstOrNull()?.let { parts.append(" (").append(it.second).append(")") }
            close()
            val ic = ime.currentInputConnection ?: return
            val before = ic.getTextBeforeCursor(1, 0)?.toString().orEmpty()
            val lead = if (before.isNotEmpty() && !before.last().isWhitespace()) " " else ""
            ime.onTextInput(lead + parts.toString())
        }

        private fun copy() {
            val r = result ?: return
            val all = buildString {
                append("Fact check: ").append(r.verdict).append('\n')
                if (r.claims.isNotEmpty()) {
                    append("Checked:\n")
                    r.claims.forEach { append("- ").append(it).append('\n') }
                }
                bullets(r).forEach { append("- ").append(it).append('\n') }
                if (r.sources.isNotEmpty()) {
                    append("Sources:\n")
                    r.sources.forEach { (t, u) -> append(t).append(" - ").append(u).append('\n') }
                }
            }.trimEnd()
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("Fact check", all))
            Toast.makeText(ctx.applicationContext, "Fact check copied", Toast.LENGTH_SHORT).show()
        }

        private fun openLink(url: String) {
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Toast.makeText(ctx.applicationContext, "No browser to open that link", Toast.LENGTH_SHORT).show() }
        }

        private fun close() = ime.sovereignHideFactCheckPanel()

        private fun verdictColor(v: String): Int = when (v.lowercase()) {
            "true", "mostly true" -> VERDICT_GREEN
            "false", "mostly false" -> VERDICT_RED
            "mixed", "can't verify" -> VERDICT_AMBER
            else -> textColor
        }

        private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
            text = s
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

        private fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(ctx).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(if (primary) Color.WHITE else textColor)
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (primary) SovereignTheme.ENTER else if (badgeColor != keyColor) badgeColor else keyColor)
            }
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
        }

        private fun weighted() = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            marginStart = dp(4); marginEnd = dp(4)
        }

        private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
    }

    private const val VERDICT_GREEN = 0xFF2E7D32.toInt()
    private const val VERDICT_AMBER = 0xFFB26A00.toInt()
    private const val VERDICT_RED = 0xFFC62828.toInt()
}
