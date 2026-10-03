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

package dev.patrickgold.florisboard.ime.ai

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * "Mark as bad" in Android's text-selection menu (next to Copy and Share): saves the selected
 * text to Recent edits with a 👎 so the cleanup can be tuned from it. No screen of its own;
 * it records the text and closes. The text itself is left unchanged in the app.
 */
class MarkBadActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) {
            EditLog.addMarked(this, "Marked text 👎", text, text)
            Toast.makeText(this, "Marked as bad. It's in Recent edits.", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
