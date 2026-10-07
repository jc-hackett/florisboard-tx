// SPDX-License-Identifier: GPL-3.0-only
// SovereignBoard: Gboard-style "recent screenshot" chip. Android doesn't put screenshots on the clipboard,
// so when the keyboard opens we look at the single newest image in a Screenshots folder (with photo
// access only) and, if it is under three minutes old and hasn't been used or dismissed, offer it in the
// suggestion strip. Tap pastes it (and adds it to clipboard history); the x or a long-press dismisses it.
package helium314.keyboard.tx

import android.Manifest
import android.content.ClipDescription
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import helium314.keyboard.keyboard.KeyboardTypeface
import helium314.keyboard.keyboard.internal.KeyboardIconsSet
import helium314.keyboard.latin.LatinIME
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.database.ClipboardDao
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.databinding.ClipboardSuggestionBinding
import helium314.keyboard.latin.utils.InputTypeUtils
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.ToolbarKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object SovereignScreenshots {
    private const val TAG = "SovereignScreenshots"
    private const val RECENT_MS = 3 * 60 * 1000L
    private const val PREFS = "dictation"
    private const val KEY_HANDLED = "screenshot_handled_id"

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private class Shot(val id: Long, val uri: Uri, val addedMs: Long, val mime: String, val thumb: Bitmap?)

    @Volatile private var candidate: Shot? = null

    /** Photo access needed to read screenshots: full, or (Android 14+) only for photos the user picked. */
    fun permissionsToRequest(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 ->
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Full photo access. */
    fun hasFullAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 33 -> granted(context, Manifest.permission.READ_MEDIA_IMAGES)
        else -> granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Android 14+ "selected photos only": works, but only for screenshots the user picked. */
    fun hasPartialAccess(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 34 && !hasFullAccess(context) &&
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)

    private fun hasAccess(context: Context) = hasFullAccess(context) || hasPartialAccess(context)

    private fun handledId(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_HANDLED, -1L)

    private fun markHandled(context: Context, id: Long) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_HANDLED, id).apply()
        if (candidate?.id == id) candidate = null
    }

    /** The keyboard opened in a new field: look for a fresh screenshot in the background. */
    @JvmStatic
    fun onKeyboardShown(ime: LatinIME) {
        candidate = null
        val ctx = ime.applicationContext
        if (!DictationSettings(ctx).offerScreenshots || !hasAccess(ctx)) return
        if (InputTypeUtils.isAnyPasswordInputType(ime.currentInputEditorInfo?.inputType ?: 0)) return
        scope.launch {
            val shot = withContext(Dispatchers.IO) { runCatching { findNewest(ctx) }.getOrNull() } ?: return@launch
            candidate = shot
            // Show it now if the strip is idle (nothing being typed yet).
            if (!ime.sovereignIsComposingWord()) ime.setNeutralSuggestionStrip()
        }
    }

    /** Reads only the single newest screenshot, and only if it is recent and not already used or dismissed. */
    private fun findNewest(context: Context): Shot? {
        val now = System.currentTimeMillis()
        val since = (now - RECENT_MS) / 1000
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED, MediaStore.Images.Media.MIME_TYPE,
        )
        val where: String
        val args: Array<String>
        if (Build.VERSION.SDK_INT >= 29) {
            where = "(${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?)" +
                " AND ${MediaStore.Images.Media.DATE_ADDED} >= ?"
            args = arrayOf("%Screenshots%", "Screenshots", since.toString())
        } else {
            @Suppress("DEPRECATION")
            where = "(${MediaStore.Images.Media.DATA} LIKE ? OR ${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?)" +
                " AND ${MediaStore.Images.Media.DATE_ADDED} >= ?"
            args = arrayOf("%/Screenshots/%", "Screenshots", since.toString())
        }
        val order = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val cursor = if (Build.VERSION.SDK_INT >= 30) {
            context.contentResolver.query(collection, projection, Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, where)
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, order)
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, 1)
            }, null)
        } else {
            context.contentResolver.query(collection, projection, where, args, order)
        }
        cursor?.use { c ->
            if (!c.moveToFirst()) return null // only the first (newest) row is ever read
            val id = c.getLong(0)
            val addedMs = c.getLong(1) * 1000
            val mime = c.getString(2) ?: "image/png"
            if (id == handledId(context) || now - addedMs > RECENT_MS) return null
            val uri = ContentUris.withAppendedId(collection, id)
            return Shot(id, uri, addedMs, mime, thumbnail(context, uri))
        }
        return null
    }

    private fun thumbnail(context: Context, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 29) {
            context.contentResolver.loadThumbnail(uri, Size(240, 240), null)
        } else {
            val opt = BitmapFactory.Options().apply { inSampleSize = 8 }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opt) }
        }
    }.getOrNull()

    /** The chip for the suggestion strip, or null if there is no fresh screenshot to offer. */
    @JvmStatic
    fun suggestionView(ime: LatinIME, parent: ViewGroup?): View? {
        val shot = candidate ?: return null
        if (parent == null) return null
        if (System.currentTimeMillis() - shot.addedMs > RECENT_MS) { candidate = null; return null }
        val binding = ClipboardSuggestionBinding.inflate(LayoutInflater.from(ime), parent, false)
        val textView = binding.clipboardSuggestionText
        KeyboardTypeface.applyToTextView(textView)
        textView.text = "Screenshot"
        val imageView = binding.clipboardSuggestionImage
        if (shot.thumb != null) {
            imageView.isVisible = true
            imageView.setImageBitmap(shot.thumb)
        }
        val paste = View.OnClickListener {
            binding.root.isGone = true
            paste(ime, shot)
            dismissView(ime)
        }
        val dismiss = View.OnLongClickListener {
            markHandled(ime, shot.id)
            binding.root.isGone = true
            dismissView(ime)
            true
        }
        textView.setOnClickListener(paste)
        imageView.setOnClickListener(paste)
        textView.setOnLongClickListener(dismiss)
        imageView.setOnLongClickListener(dismiss)
        val closeButton = binding.clipboardSuggestionClose
        closeButton.setImageDrawable(KeyboardIconsSet.instance.getIconDrawable(ToolbarKey.CLOSE_HISTORY.name.lowercase()))
        closeButton.layoutParams.width = textView.lineHeight
        closeButton.layoutParams.height = textView.lineHeight
        closeButton.contentDescription = "Dismiss screenshot"
        closeButton.setOnClickListener { dismiss.onLongClick(it) }

        val colors = Settings.getValues().mColors
        textView.setTextColor(colors.get(ColorType.KEY_TEXT))
        colors.setColor(closeButton, ColorType.REMOVE_SUGGESTION_ICON)
        colors.setBackground(binding.root, ColorType.CLIPBOARD_SUGGESTION_BACKGROUND)
        binding.root.contentDescription = "Paste recent screenshot"
        return binding.root
    }

    private fun dismissView(ime: LatinIME) {
        ime.setNeutralSuggestionStrip()
        ime.mHandler.postResumeSuggestions(false)
    }

    /**
     * Adds the screenshot to clipboard history (a private copy, as for copied images), then hands that
     * copy to the app the way pasting an image from clipboard history does.
     */
    private fun paste(ime: LatinIME, shot: Shot) {
        markHandled(ime, shot.id)
        val editorInfo = ime.currentInputEditorInfo
        val accepts = editorInfo != null &&
            EditorInfoCompat.getContentMimeTypes(editorInfo).any { ClipDescription.compareMimeTypes(shot.mime, it) }
        scope.launch {
            val entry = withContext(Dispatchers.IO) {
                runCatching {
                    val dao = ClipboardDao.getInstance(ime) ?: return@runCatching null
                    val description = ClipDescription("Screenshot", arrayOf(shot.mime))
                    dao.addClipUri(System.currentTimeMillis(), false, shot.uri, description, ime)
                    dao.getAll().filter { it.filename != null }.maxByOrNull { it.timeStamp }
                }.onFailure { Log.w(TAG, "could not keep screenshot", it) }.getOrNull()
            }
            if (!accepts) return@launch toast(ime, "This app doesn't accept images")
            if (entry == null) return@launch toast(ime, "Couldn't paste the screenshot")
            val ic = ime.currentInputConnection ?: return@launch
            val info = entry.getContentInfo(ime)
            InputConnectionCompat.commitContent(ic, editorInfo!!, info, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null)
        }
    }

    private fun toast(context: Context, text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
