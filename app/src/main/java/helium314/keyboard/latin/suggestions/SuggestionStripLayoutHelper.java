/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.suggestions;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.Layout; // SovereignBoard:
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.StyleSpan;
import android.text.style.UnderlineSpan;
import android.util.AttributeSet;
import android.util.TypedValue; // SovereignBoard:
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList; // SovereignBoard:
import java.util.HashSet; // SovereignBoard:

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import helium314.keyboard.accessibility.AccessibilityUtils;
import helium314.keyboard.keyboard.KeyboardTypeface;
import helium314.keyboard.latin.PunctuationSuggestions;
import helium314.keyboard.latin.R;
import helium314.keyboard.latin.SuggestedWords;
import helium314.keyboard.latin.SuggestedWords.SuggestedWordInfo;
import helium314.keyboard.latin.common.ColorType;
import helium314.keyboard.latin.common.Colors;
import helium314.keyboard.latin.settings.Settings;
import helium314.keyboard.latin.settings.SettingsValues;
import helium314.keyboard.latin.utils.ResourceUtils;
import helium314.keyboard.latin.utils.ViewLayoutUtils;

import java.util.ArrayList;

final class SuggestionStripLayoutHelper {
    private static final int DEFAULT_SUGGESTIONS_COUNT_IN_STRIP = 3;
    private static final float DEFAULT_CENTER_SUGGESTION_PERCENTILE = 0.40f;
    private static final int DEFAULT_MAX_MORE_SUGGESTIONS_ROW = 2;
    private static final int PUNCTUATIONS_IN_STRIP = 5;
    private static final float MIN_TEXT_XSCALE = 0.70f;

    public final int mPadding;
    public final int mDividerWidth;
    public final int mSuggestionsStripHeight;
    private final int mSuggestionsCountInStrip;
    public final int mMoreSuggestionsRowHeight;
    private int mMaxMoreSuggestionsRow;
    public final float mMinMoreSuggestionsWidth;
    public final int mMoreSuggestionsBottomGap;
    private boolean mMoreSuggestionsAvailable;

    // The index of these {@link ArrayList} is the position in the suggestion strip. The indices
    // increase towards the right for LTR scripts and the left for RTL scripts, starting with 0.
    // The position of the most important suggestion is in {@link #mCenterPositionInStrip}
    private final ArrayList<TextView> mWordViews;
    private final ArrayList<View> mDividerViews;
    private final ArrayList<TextView> mDebugInfoViews;

    private final int mColorValidTypedWord;
    private final int mColorTypedWord;
    private final int mColorAutoCorrect;
    private final int mColorSuggested;
    private final float mAlphaObsoleted;
    private final float mCenterSuggestionWeight;
    private final int mCenterPositionInStrip;
    private final int mTypedWordPositionWhenAutocorrect;
    private final Drawable mMoreSuggestionsHint;
    private static final String MORE_SUGGESTIONS_HINT = "…";

    private static final CharacterStyle BOLD_SPAN = new StyleSpan(Typeface.BOLD);
    private static final CharacterStyle UNDERLINE_SPAN = new UnderlineSpan();

    private final int mSuggestionStripOptions;
    // These constants are the flag values of
    // {@link R.styleable#SuggestionStripView_suggestionStripOptions} attribute.
    private static final int AUTO_CORRECT_BOLD = 0x01;
    private static final int AUTO_CORRECT_UNDERLINE = 0x02;
    private static final int VALID_TYPED_WORD_BOLD = 0x04;

    public SuggestionStripLayoutHelper(final Context context, final AttributeSet attrs,
            final int defStyle, final ArrayList<TextView> wordViews,
            final ArrayList<View> dividerViews, final ArrayList<TextView> debugInfoViews) {
        mWordViews = wordViews;
        mDividerViews = dividerViews;
        mDebugInfoViews = debugInfoViews;

        final TextView wordView = wordViews.get(0);
        final View dividerView = dividerViews.get(0);
        mPadding = wordView.getCompoundPaddingLeft() + wordView.getCompoundPaddingRight();
        dividerView.measure(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        mDividerWidth = dividerView.getMeasuredWidth();

        final Resources res = wordView.getResources();
        mSuggestionsStripHeight = res.getDimensionPixelSize(
                R.dimen.config_suggestions_strip_height);

        final TypedArray a = context.obtainStyledAttributes(attrs,
                R.styleable.SuggestionStripView, defStyle, R.style.SuggestionStripView);
        mSuggestionStripOptions = a.getInt(R.styleable.SuggestionStripView_suggestionStripOptions, 0);
        mAlphaObsoleted = ResourceUtils.getFraction(a, R.styleable.SuggestionStripView_alphaObsoleted, 1.0f);

        final Colors colors = Settings.getValues().mColors;
        mColorValidTypedWord = colors.get(ColorType.SUGGESTION_VALID_WORD);
        mColorTypedWord = colors.get(ColorType.SUGGESTION_TYPED_WORD);
        mColorAutoCorrect = colors.get(ColorType.SUGGESTION_AUTO_CORRECT);
        mColorSuggested = colors.get(ColorType.SUGGESTED_WORD);
        final int colorMoreSuggestionsHint = colors.get(ColorType.MORE_SUGGESTIONS_HINT);

        mSuggestionsCountInStrip = a.getInt(
                R.styleable.SuggestionStripView_suggestionsCountInStrip,
                DEFAULT_SUGGESTIONS_COUNT_IN_STRIP);
        mCenterSuggestionWeight = ResourceUtils.getFraction(a,
                R.styleable.SuggestionStripView_centerSuggestionPercentile,
                DEFAULT_CENTER_SUGGESTION_PERCENTILE);
        mMaxMoreSuggestionsRow = a.getInt(
                R.styleable.SuggestionStripView_maxMoreSuggestionsRow,
                DEFAULT_MAX_MORE_SUGGESTIONS_ROW);
        mMinMoreSuggestionsWidth = ResourceUtils.getFraction(a,
                R.styleable.SuggestionStripView_minMoreSuggestionsWidth, 1.0f);
        a.recycle();

        mMoreSuggestionsHint = getMoreSuggestionsHint(res,
                res.getDimension(R.dimen.config_more_suggestions_hint_text_size),
                colorMoreSuggestionsHint);
        mCenterPositionInStrip = mSuggestionsCountInStrip / 2;
        // Assuming there are at least three suggestions. Also, note that the suggestions are
        // laid out according to script direction, so this is left of the center for LTR scripts
        // and right of the center for RTL scripts.
        mTypedWordPositionWhenAutocorrect = mCenterPositionInStrip - 1;
        mMoreSuggestionsBottomGap = res.getDimensionPixelOffset(
                R.dimen.config_more_suggestions_bottom_gap);
        mMoreSuggestionsRowHeight = res.getDimensionPixelSize(
                R.dimen.config_more_suggestions_row_height);
    }

    public int getMaxMoreSuggestionsRow() {
        return mMaxMoreSuggestionsRow;
    }

    private int getMoreSuggestionsHeight() {
        return mMaxMoreSuggestionsRow * mMoreSuggestionsRowHeight + mMoreSuggestionsBottomGap;
    }

    public void setMoreSuggestionsHeight(final int remainingHeight) {
        final int currentHeight = getMoreSuggestionsHeight();
        if (currentHeight <= remainingHeight) {
            return;
        }
        mMaxMoreSuggestionsRow = (remainingHeight - mMoreSuggestionsBottomGap) / mMoreSuggestionsRowHeight;
    }

    private static Drawable getMoreSuggestionsHint(final Resources res, final float textSize, final int color) {
        final Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setTextAlign(Align.CENTER);
        paint.setTextSize(textSize);
        paint.setColor(color);
        final Rect bounds = new Rect();
        paint.getTextBounds(MORE_SUGGESTIONS_HINT, 0, MORE_SUGGESTIONS_HINT.length(), bounds);
        final int width = Math.round(bounds.width() + 0.5f);
        final int height = Math.round(bounds.height() + 0.5f);
        final Bitmap buffer = Bitmap.createBitmap(width, (height * 3 / 2), Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(buffer);
        canvas.drawText(MORE_SUGGESTIONS_HINT, width / 2, height, paint);
        BitmapDrawable bitmapDrawable = new BitmapDrawable(res, buffer);
        bitmapDrawable.setTargetDensity(canvas);
        return bitmapDrawable;
    }

    private CharSequence getStyledSuggestedWord(final SuggestedWords suggestedWords,
            final int indexInSuggestedWords) {
        if (indexInSuggestedWords >= suggestedWords.size()) {
            return null;
        }
        final String word = suggestedWords.getLabel(indexInSuggestedWords);
        // TODO: don't use the index to decide whether this is the auto-correction/typed word, as
        // this is brittle
        final boolean isAutoCorrection = suggestedWords.mWillAutoCorrect
                && indexInSuggestedWords == SuggestedWords.INDEX_OF_AUTO_CORRECTION;
        // SovereignBoard: bold only means "space will correct to this"; the valid typed word is drawn plain
        final boolean isTypedWordValid = false;
        if (!isAutoCorrection && !isTypedWordValid) {
            return word;
        }

        final Spannable spannedWord = new SpannableString(word);
        final int options = mSuggestionStripOptions;
        if ((isAutoCorrection && (options & AUTO_CORRECT_BOLD) != 0)
                || (isTypedWordValid && (options & VALID_TYPED_WORD_BOLD) != 0)) {
            addStyleSpan(spannedWord, BOLD_SPAN);
        }
        if (isAutoCorrection && (options & AUTO_CORRECT_UNDERLINE) != 0) {
            addStyleSpan(spannedWord, UNDERLINE_SPAN);
        }
        return spannedWord;
    }

    /**
     * Convert an index of {@link SuggestedWords} to position in the suggestion strip.
     * @param indexInSuggestedWords the index of {@link SuggestedWords}.
     * @param suggestedWords the suggested words list
     * @return Non-negative integer of the position in the suggestion strip.
     *         Negative integer if the word of the index shouldn't be shown on the suggestion strip.
     */
    private int getPositionInSuggestionStrip(final int indexInSuggestedWords,
            final SuggestedWords suggestedWords) {
        final SettingsValues settingsValues = Settings.getValues();
        final boolean shouldOmitTypedWord = shouldOmitTypedWord(suggestedWords.mInputStyle,
                settingsValues.mGestureFloatingPreviewTextEnabled, true);
        if (shouldOmitTypedWord && showsValidTypedWordInSingleSlot(suggestedWords)) { // SovereignBoard:
            return indexInSuggestedWords == SuggestedWords.INDEX_OF_TYPED_WORD ? mCenterPositionInStrip : -1;
        }
        return getPositionInSuggestionStrip(indexInSuggestedWords, suggestedWords.mWillAutoCorrect,
                shouldOmitTypedWord, mCenterPositionInStrip, mTypedWordPositionWhenAutocorrect);
    }

    /**
     * SovereignBoard: with a single slot, a valid typed word and no auto-correction pending, the slot shows the
     * word as typed (tapping commits it), and completions move to the long-press panel. Not when the entry at
     * index 1 already is the typed word in another case (capitalized at sentence start, or "center the typed
     * word" setting): upstream already puts that one in the slot.
     */
    private boolean showsValidTypedWordInSingleSlot(final SuggestedWords suggestedWords) {
        if (!suggestedWords.mTypedWordValid || suggestedWords.mWillAutoCorrect
                || suggestedWords.size() == 0 || suggestedWords.isPunctuationSuggestions()) {
            return false;
        }
        final SuggestedWordInfo typed = suggestedWords.getInfo(SuggestedWords.INDEX_OF_TYPED_WORD);
        if (!typed.isKindOf(SuggestedWordInfo.KIND_TYPED) || TextUtils.isEmpty(typed.mWord)) {
            return false;
        }
        return suggestedWords.size() <= SuggestedWords.INDEX_OF_AUTO_CORRECTION
                || !typed.mWord.equalsIgnoreCase(suggestedWords.getWord(SuggestedWords.INDEX_OF_AUTO_CORRECTION));
    }

    /**
     * SovereignBoard: the words for the long-press panel, in order: the typed word first when it is not what the
     * strip shows (so the user can always keep what he typed), then everything from {@code startIndex} on,
     * without anything already shown in the strip and without repeats.
     */
    private SuggestedWords buildMoreSuggestionsWords(final SuggestedWords suggestedWords, final int startIndex,
            final ArrayList<Integer> shownIndices) {
        final HashSet<String> seen = new HashSet<>();
        for (final int index : shownIndices) {
            if (index >= 0 && index < suggestedWords.size()) seen.add(suggestedWords.getWord(index));
        }
        final ArrayList<SuggestedWordInfo> infos = new ArrayList<>();
        if (suggestedWords.size() > 0 && !shownIndices.contains(SuggestedWords.INDEX_OF_TYPED_WORD)) {
            final SuggestedWordInfo typed = suggestedWords.getInfo(SuggestedWords.INDEX_OF_TYPED_WORD);
            if (typed.isKindOf(SuggestedWordInfo.KIND_TYPED) && !TextUtils.isEmpty(typed.mWord)
                    && seen.add(typed.mWord)) {
                infos.add(typed);
            }
        }
        final int size = Math.min(suggestedWords.size(), SuggestedWords.MAX_SUGGESTIONS);
        for (int index = Math.max(startIndex, 0); index < size && infos.size() < SuggestedWords.MAX_SUGGESTIONS; index++) {
            if (shownIndices.contains(index)) continue;
            final SuggestedWordInfo info = suggestedWords.getInfo(index);
            if (seen.add(info.mWord)) infos.add(info);
        }
        // mWillAutoCorrect false: the panel must not swap labels (MoreSuggestions.isIndexSubjectToAutoCorrection)
        return new SuggestedWords(infos, null, suggestedWords.mTypedWordInfo, false, false,
                suggestedWords.mIsObsoleteSuggestions, suggestedWords.mInputStyle, suggestedWords.mSequenceNumber);
    }

    private SuggestedWords mMoreSuggestionsWords = SuggestedWords.getEmptyInstance(); // SovereignBoard:

    /** SovereignBoard: what the long-press panel shows for the suggestions last laid out (start at index 0). */
    public SuggestedWords getMoreSuggestionsWords() {
        return mMoreSuggestionsWords;
    }

    static boolean shouldOmitTypedWord(final int inputStyle,
            final boolean gestureFloatingPreviewTextEnabled,
            final boolean shouldShowUiToAcceptTypedWord) {
        final boolean omitTypedWord = (inputStyle == SuggestedWords.INPUT_STYLE_TYPING)
                || (inputStyle == SuggestedWords.INPUT_STYLE_TAIL_BATCH)
                || (inputStyle == SuggestedWords.INPUT_STYLE_UPDATE_BATCH && gestureFloatingPreviewTextEnabled);
        return shouldShowUiToAcceptTypedWord && omitTypedWord;
    }

    static int getPositionInSuggestionStrip(final int indexInSuggestedWords,
            final boolean willAutoCorrect, final boolean omitTypedWord,
            final int centerPositionInStrip, final int typedWordPositionWhenAutoCorrect) {
        if (omitTypedWord) {
            if (indexInSuggestedWords == SuggestedWords.INDEX_OF_TYPED_WORD) {
                // Ignore.
                return -1;
            }
            if (indexInSuggestedWords == SuggestedWords.INDEX_OF_AUTO_CORRECTION) {
                // Center in the suggestion strip.
                return centerPositionInStrip;
            }
            // If neither of those, the order in the suggestion strip is left of the center first
            // then right of the center, to both edges of the suggestion strip.
            // For example, center-1, center+1, center-2, center+2, and so on.
            final int offsetFromCenter = (indexInSuggestedWords % 2) == 0 ? -(indexInSuggestedWords / 2) : (indexInSuggestedWords / 2);
            return centerPositionInStrip + offsetFromCenter;
        }
        final int indexToDisplayMostImportantSuggestion;
        final int indexToDisplaySecondMostImportantSuggestion;
        if (willAutoCorrect) {
            indexToDisplayMostImportantSuggestion = SuggestedWords.INDEX_OF_AUTO_CORRECTION;
            indexToDisplaySecondMostImportantSuggestion = SuggestedWords.INDEX_OF_TYPED_WORD;
        } else {
            indexToDisplayMostImportantSuggestion = SuggestedWords.INDEX_OF_TYPED_WORD;
            indexToDisplaySecondMostImportantSuggestion = SuggestedWords.INDEX_OF_AUTO_CORRECTION;
        }
        if (indexInSuggestedWords == indexToDisplayMostImportantSuggestion) {
            // Center in the suggestion strip.
            return centerPositionInStrip;
        }
        if (indexInSuggestedWords == indexToDisplaySecondMostImportantSuggestion) {
            // Center-1.
            return typedWordPositionWhenAutoCorrect;
        }
        // If neither of those, the order in the suggestion strip is right of the center first
        // then left of the center, to both edges of the suggestion strip.
        // For example, Center+1, center-2, center+2, center-3, and so on.
        final int n = indexInSuggestedWords + 1;
        final int offsetFromCenter = (n % 2) == 0 ? -(n / 2) : (n / 2);
        return centerPositionInStrip + offsetFromCenter;
    }

    private int getSuggestionTextColor(final SuggestedWords suggestedWords,
            final int indexInSuggestedWords) {
        // Use identity for strings, not #equals : it's the typed word if it's the same object
        final boolean isTypedWord = suggestedWords.getInfo(indexInSuggestedWords).isKindOf(SuggestedWordInfo.KIND_TYPED);

        final int color;
        if (indexInSuggestedWords == SuggestedWords.INDEX_OF_AUTO_CORRECTION && suggestedWords.mWillAutoCorrect) {
            color = mColorAutoCorrect;
        } else if (isTypedWord && suggestedWords.mTypedWordValid) {
            color = mColorValidTypedWord;
        } else if (isTypedWord) {
            color = mColorTypedWord;
        } else {
            color = mColorSuggested;
        }
        if (suggestedWords.mIsObsoleteSuggestions && !isTypedWord) {
            return applyAlpha(color, mAlphaObsoleted);
        }
        return color;
    }

    private static int applyAlpha(final int color, final float alpha) {
        final int newAlpha = (int)(Color.alpha(color) * alpha);
        return Color.argb(newAlpha, Color.red(color), Color.green(color), Color.blue(color));
    }

    private static void addDivider(final ViewGroup stripView, final View dividerView) {
        stripView.addView(dividerView);
        final LinearLayout.LayoutParams params = (LinearLayout.LayoutParams)dividerView.getLayoutParams();
        params.gravity = Gravity.CENTER;
    }

    /**
     * Layout suggestions to the suggestions strip. And returns the start index of more
     * suggestions.
     *
     * @param suggestedWords suggestions to be shown in the suggestions strip.
     * @param stripView the suggestions strip view.
     * @param placerView the view where the debug info will be placed.
     * @return the start index of more suggestions.
     */
    public int layoutAndReturnStartIndexOfMoreSuggestions(
            final Context context,
            final SuggestedWords suggestedWords,
            final ViewGroup stripView,
            final ViewGroup placerView) {
        if (suggestedWords.isPunctuationSuggestions()) {
            mMoreSuggestionsWords = suggestedWords; // SovereignBoard: panel unchanged for punctuation
            return layoutPunctuationsAndReturnStartIndexOfMoreSuggestions(
                    (PunctuationSuggestions)suggestedWords, stripView);
        }

        if (mSovereignRowB != null) { // SovereignBoard: fixed [word A][word B][emoji] strip
            return layoutSovereignSlots(context, suggestedWords, stripView);
        }

        final int wordCountToShow = suggestedWords.getWordCountToShow();
        final int startIndexOfMoreSuggestions = setupWordViewsAndReturnStartIndexOfMoreSuggestions(
                suggestedWords, mSuggestionsCountInStrip);
        final TextView centerWordView = mWordViews.get(mCenterPositionInStrip);
        final int stripWidth = stripView.getWidth();
        final int centerWidth = getSuggestionWidth(mCenterPositionInStrip, stripWidth);
        if (wordCountToShow == 1 || getTextScaleX(centerWordView.getText(), centerWidth,
                centerWordView.getPaint()) < MIN_TEXT_XSCALE) {
            // Layout only the most relevant suggested word at the center of the suggestion strip
            // by consolidating all slots in the strip.
            final int countInStrip = 1;
            // SovereignBoard: panel words; the hint shows whenever the panel has something
            final Integer centerIndex = (Integer)centerWordView.getTag();
            final ArrayList<Integer> shown = new ArrayList<>();
            if (centerIndex != null) shown.add(centerIndex);
            mMoreSuggestionsWords = buildMoreSuggestionsWords(suggestedWords,
                    (centerIndex == null ? 0 : centerIndex) + 1, shown);
            mMoreSuggestionsAvailable = (wordCountToShow > countInStrip) || !mMoreSuggestionsWords.isEmpty();
            layoutWord(context, mCenterPositionInStrip, stripWidth - mPadding);
            stripView.addView(centerWordView);
            setLayoutWeight(centerWordView, 1.0f, ViewGroup.LayoutParams.MATCH_PARENT);
            if (SuggestionStripView.DEBUG_SUGGESTIONS) {
                layoutDebugInfo(mCenterPositionInStrip, placerView, stripWidth);
            }
            final Integer lastIndex = (Integer)centerWordView.getTag();
            return (lastIndex == null ? 0 : lastIndex) + 1;
        }

        final int countInStrip = mSuggestionsCountInStrip;
        // SovereignBoard: panel words; the hint shows whenever the panel has something
        final ArrayList<Integer> shown = new ArrayList<>();
        for (int positionInStrip = 0; positionInStrip < countInStrip; positionInStrip++) {
            final Object tag = mWordViews.get(positionInStrip).getTag();
            if (tag instanceof Integer) shown.add((Integer) tag);
        }
        mMoreSuggestionsWords = buildMoreSuggestionsWords(suggestedWords, startIndexOfMoreSuggestions, shown);
        mMoreSuggestionsAvailable = (wordCountToShow > countInStrip) || !mMoreSuggestionsWords.isEmpty();
        @SuppressWarnings("unused")
        int x = 0;
        for (int positionInStrip = 0; positionInStrip < countInStrip; positionInStrip++) {
            if (positionInStrip != 0) {
                final View divider = mDividerViews.get(positionInStrip);
                // Add divider if this isn't the left most suggestion in suggestions strip.
                addDivider(stripView, divider);
                x += divider.getMeasuredWidth();
            }

            final int width = getSuggestionWidth(positionInStrip, stripWidth);
            final TextView wordView = layoutWord(context, positionInStrip, width);
            stripView.addView(wordView);
            setLayoutWeight(wordView, getSuggestionWeight(positionInStrip), ViewGroup.LayoutParams.MATCH_PARENT);
            x += wordView.getMeasuredWidth();

            if (SuggestionStripView.DEBUG_SUGGESTIONS) {
                layoutDebugInfo(positionInStrip, placerView, (int) stripView.getX() + x);
            }
        }
        return startIndexOfMoreSuggestions;
    }

    /**
     * Format appropriately the suggested word in {@link #mWordViews} specified by
     * <code>positionInStrip</code>. When the suggested word doesn't exist, the corresponding
     * {@link TextView} will be disabled and never respond to user interaction. The suggested word
     * may be shrunk or ellipsized to fit in the specified width.
     * <p>
     * The <code>positionInStrip</code> argument is the index in the suggestion strip. The indices
     * increase towards the right for LTR scripts and the left for RTL scripts, starting with 0.
     * The position of the most important suggestion is in {@link #mCenterPositionInStrip}. This
     * usually doesn't match the index in <code>suggedtedWords</code> -- see
     * {@link #getPositionInSuggestionStrip(int,SuggestedWords)}.
     *
     * @param positionInStrip the position in the suggestion strip.
     * @param width the maximum width for layout in pixels.
     * @return the {@link TextView} containing the suggested word appropriately formatted.
     */
    private TextView layoutWord(final Context context, final int positionInStrip, final int width) {
        final TextView wordView = mWordViews.get(positionInStrip);
        final CharSequence word = wordView.getText();
        if (positionInStrip == mCenterPositionInStrip && mMoreSuggestionsAvailable) {
            // TODO: This "more suggestions hint" should have a nicely designed icon.
            wordView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, mMoreSuggestionsHint);
            // HACK: Align with other TextViews that have no compound drawables.
            wordView.setCompoundDrawablePadding(-mMoreSuggestionsHint.getIntrinsicHeight());
        } else {
            wordView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
        }
        // {@link StyleSpan} in a content description may cause an issue of TTS/TalkBack.
        // Use a simple {@link String} to avoid the issue.
        wordView.setContentDescription(
                TextUtils.isEmpty(word)
                    ? context.getResources().getString(R.string.spoken_empty_suggestion)
                    : word.toString());
        final CharSequence text = getEllipsizedTextWithSettingScaleX(
                word, width, wordView.getPaint());
        final float scaleX = wordView.getTextScaleX();
        wordView.setText(text); // TextView.setText() resets text scale x to 1.0.
        wordView.setTextScaleX(scaleX);
        // A <code>wordView</code> should be disabled when <code>word</code> is empty in order to
        // make it unclickable.
        // With accessibility touch exploration on, <code>wordView</code> should be enabled even
        // when it is empty to avoid announcing as "disabled".
        wordView.setEnabled(!TextUtils.isEmpty(word)
                || AccessibilityUtils.Companion.getInstance().isTouchExplorationEnabled());
        return wordView;
    }

    // SovereignBoard: the fixed three-slot strip. Left of the pinned keys: [word A][word B (+ "+")][emoji].
    //  A and B always get the same width (half of what the emoji slot leaves); the emoji slot is a fixed square.
    //  Slot widths never change; each word's text shrinks to fit its slot (18dp down to 10dp), "..." only below that.
    //  No dividers; words are centred in their slots. "+" has a fixed place at the right edge of slot B (kept
    //  even while hidden), and word B is centred in the rest of the slot, so neither ever moves.
    private ViewGroup mSovereignRowB;
    private View mSovereignAddWordView;
    private TextView mSovereignEmojiView;
    private int mSovereignEmojiSlotWidth;
    private float mSovereignBaseTextSize;
    private float mSovereignMinTextSize;

    /** SovereignBoard: switch on the fixed three-slot strip. {@code rowB} holds word B and the "+" button. */
    public void setSovereignSlots(final ViewGroup rowB, final View addWordView, final TextView emojiView,
            final int emojiSlotWidth) {
        mSovereignRowB = rowB;
        mSovereignAddWordView = addWordView;
        mSovereignEmojiView = emojiView;
        mSovereignEmojiSlotWidth = emojiSlotWidth;
        mSovereignBaseTextSize = mWordViews.get(0).getTextSize();
        mSovereignMinTextSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, SOVEREIGN_MIN_TEXT_DP,
                rowB.getResources().getDisplayMetrics());
        final View.OnLayoutChangeListener refit = (v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) v.post(this::refitSovereignWords);
        };
        mWordViews.get(0).addOnLayoutChangeListener(refit);
        mWordViews.get(1).addOnLayoutChangeListener(refit);
    }

    private static final float SOVEREIGN_MIN_TEXT_DP = 10f;

    /** SovereignBoard: shrink each word's text so it fits its slot. Call when text, "+" or the bin icon change. */
    public void refitSovereignWords() {
        if (mSovereignRowB == null || mWordViews.get(1).getParent() != mSovereignRowB) return;
        final TextView wordA = mWordViews.get(0);
        fitSovereignWord(wordA, wordA.getWidth());
        final TextView wordB = mWordViews.get(1); // its view already stops where the "+" place begins
        fitSovereignWord(wordB, wordB.getWidth());
    }

    private void fitSovereignWord(final TextView view, final int outerWidth) {
        float size = mSovereignBaseTextSize;
        final CharSequence text = view.getText();
        final int avail = outerWidth - view.getTotalPaddingLeft() - view.getTotalPaddingRight();
        if (outerWidth > 0 && avail > 0 && !TextUtils.isEmpty(text)) {
            final TextPaint paint = new TextPaint(view.getPaint());
            paint.setTextScaleX(1.0f);
            paint.setTextSize(mSovereignBaseTextSize);
            final float needed = Layout.getDesiredWidth(text, paint);
            if (needed > avail)
                size = Math.max(mSovereignMinTextSize, (float) Math.floor(mSovereignBaseTextSize * avail / needed * 0.98f));
        }
        if (Math.abs(view.getTextSize() - size) > 0.5f)
            view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
    }

    /** SovereignBoard: suggestion indices in the order the word slots take them (A first, then B). */
    private ArrayList<Integer> sovereignSlotOrder(final SuggestedWords suggestedWords) {
        final ArrayList<Integer> order = new ArrayList<>();
        final int size = suggestedWords.size();
        final boolean omitTypedWord = shouldOmitTypedWord(suggestedWords.mInputStyle,
                Settings.getValues().mGestureFloatingPreviewTextEnabled, true);
        if (omitTypedWord && showsValidTypedWordInSingleSlot(suggestedWords)) {
            for (int i = 0; i < size; i++) order.add(i); // the valid typed word as typed, then completions
        } else if (omitTypedWord) {
            for (int i = 1; i < size; i++) order.add(i); // the bold auto-correction (or best guess) first
            // last resort: the typed word, so a slot left empty (or only emoji found) still offers it
            if (size > 0 && suggestedWords.getInfo(SuggestedWords.INDEX_OF_TYPED_WORD).isKindOf(SuggestedWordInfo.KIND_TYPED))
                order.add(SuggestedWords.INDEX_OF_TYPED_WORD);
        } else if (suggestedWords.mWillAutoCorrect) {
            order.add(SuggestedWords.INDEX_OF_AUTO_CORRECTION);
            order.add(SuggestedWords.INDEX_OF_TYPED_WORD);
            for (int i = 2; i < size; i++) order.add(i);
        } else {
            for (int i = 0; i < size; i++) order.add(i);
        }
        return order;
    }

    private int layoutSovereignSlots(final Context context, final SuggestedWords suggestedWords,
            final ViewGroup stripView) {
        final TextView wordA = mWordViews.get(0);
        final TextView wordB = mWordViews.get(1);
        // slot A: the primary word; slot B: the next best different word. Emoji never go in A or B.
        int indexA = -1;
        int indexB = -1;
        for (final int index : sovereignSlotOrder(suggestedWords)) {
            if (index < 0 || index >= suggestedWords.size()) continue;
            final SuggestedWordInfo info = suggestedWords.getInfo(index);
            if (TextUtils.isEmpty(info.mWord) || info.isEmoji()) continue;
            if (indexA < 0) {
                indexA = index;
            } else if (!info.mWord.equalsIgnoreCase(suggestedWords.getWord(indexA))) {
                indexB = index;
                break;
            }
        }
        // emoji slot: the best emoji the dictionaries offer for this word / context
        int emojiIndex = -1;
        for (int index = 0; index < suggestedWords.size(); index++) {
            if (suggestedWords.getInfo(index).isEmoji()) {
                emojiIndex = index;
                break;
            }
        }

        final ArrayList<Integer> shown = new ArrayList<>();
        if (indexA >= 0) shown.add(indexA);
        if (indexB >= 0) shown.add(indexB);
        if (emojiIndex >= 0) shown.add(emojiIndex);
        mMoreSuggestionsWords = buildMoreSuggestionsWords(suggestedWords, 0, shown);
        mMoreSuggestionsAvailable = !mMoreSuggestionsWords.isEmpty();

        setupSovereignWord(context, wordA, suggestedWords, indexA, mMoreSuggestionsAvailable);
        setupSovereignWord(context, wordB, suggestedWords, indexB, false);
        final String emoji = emojiIndex >= 0 ? suggestedWords.getWord(emojiIndex) : null;
        mSovereignEmojiView.setText(emoji);
        mSovereignEmojiView.setTag(emoji);
        mSovereignEmojiView.setContentDescription(emoji != null ? emoji
                : context.getResources().getString(R.string.spoken_empty_suggestion));
        mSovereignEmojiView.setEnabled(emoji != null
                || AccessibilityUtils.Companion.getInstance().isTouchExplorationEnabled());

        // fixed layout: A | B + | emoji — always all three, empty or not, no dividers
        stripView.addView(wordA);
        setLayoutWeight(wordA, 1.0f, ViewGroup.LayoutParams.MATCH_PARENT);
        mSovereignRowB.removeAllViews();
        mSovereignRowB.addView(wordB);
        final ViewGroup.LayoutParams lpB = wordB.getLayoutParams();
        if (lpB instanceof final LinearLayout.LayoutParams llpB) { // SovereignBoard: word B takes the slot up to the "+" place
            llpB.weight = 1f;
            llpB.width = 0;
            llpB.height = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        mSovereignRowB.addView(mSovereignAddWordView);
        stripView.addView(mSovereignRowB);
        setLayoutWeight(mSovereignRowB, 1.0f, ViewGroup.LayoutParams.MATCH_PARENT);
        stripView.addView(mSovereignEmojiView);
        final ViewGroup.LayoutParams lp = mSovereignEmojiView.getLayoutParams();
        if (lp instanceof final LinearLayout.LayoutParams llp) {
            llp.weight = 0f;
            llp.width = mSovereignEmojiSlotWidth;
            llp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        }
        refitSovereignWords();
        return Math.max(indexA, indexB) + 1;
    }

    private void setupSovereignWord(final Context context, final TextView wordView,
            final SuggestedWords suggestedWords, final int index, final boolean showMoreHint) {
        if (index >= 0) {
            wordView.setTag(index);
            wordView.setText(getStyledSuggestedWord(suggestedWords, index));
            wordView.setTextColor(getSuggestionTextColor(suggestedWords, index));
        } else {
            wordView.setTag(null);
            wordView.setText(null);
        }
        KeyboardTypeface.applyToTextView(wordView);
        wordView.setGravity(Gravity.CENTER); // SovereignBoard: words centred in their slots
        wordView.setTextScaleX(1.0f);
        wordView.setMaxWidth(Integer.MAX_VALUE);
        wordView.setEllipsize(TextUtils.TruncateAt.END);
        wordView.setMinWidth(0);
        wordView.setMinimumWidth(0);
        if (showMoreHint) {
            wordView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, mMoreSuggestionsHint);
            // HACK: Align with other TextViews that have no compound drawables.
            wordView.setCompoundDrawablePadding(-mMoreSuggestionsHint.getIntrinsicHeight());
        } else {
            wordView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
        }
        final CharSequence word = wordView.getText();
        wordView.setContentDescription(TextUtils.isEmpty(word)
                ? context.getResources().getString(R.string.spoken_empty_suggestion) : word.toString());
        wordView.setEnabled(!TextUtils.isEmpty(word)
                || AccessibilityUtils.Companion.getInstance().isTouchExplorationEnabled());
    }

    private void layoutDebugInfo(final int positionInStrip, final ViewGroup placerView,
            final int x) {
        final TextView debugInfoView = mDebugInfoViews.get(positionInStrip);
        final CharSequence debugInfo = debugInfoView.getText();
        if (debugInfo == null) {
            return;
        }
        placerView.addView(debugInfoView);
        debugInfoView.measure(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        final int infoWidth = debugInfoView.getMeasuredWidth();
        ViewLayoutUtils.placeViewAt(debugInfoView, x - infoWidth, 0, infoWidth, debugInfoView.getMeasuredHeight());
    }

    private int getSuggestionWidth(final int positionInStrip, final int maxWidth) {
        final int paddings = mPadding * mSuggestionsCountInStrip;
        final int dividers = mDividerWidth * (mSuggestionsCountInStrip - 1);
        final int availableWidth = maxWidth - paddings - dividers;
        return (int)(availableWidth * getSuggestionWeight(positionInStrip));
    }

    private float getSuggestionWeight(final int positionInStrip) {
        if (positionInStrip == mCenterPositionInStrip) {
            return mCenterSuggestionWeight;
        }
        // TODO: Revisit this for cases of 5 or more suggestions
        return (1.0f - mCenterSuggestionWeight) / (mSuggestionsCountInStrip - 1);
    }

    private int setupWordViewsAndReturnStartIndexOfMoreSuggestions(
            final SuggestedWords suggestedWords, final int maxSuggestionInStrip) {
        // Clear all suggestions first
        for (int positionInStrip = 0; positionInStrip < maxSuggestionInStrip; ++positionInStrip) {
            final TextView wordView = mWordViews.get(positionInStrip);
            wordView.setText(null);
            wordView.setTag(null);
            // Make this inactive for touches in {@link #layoutWord(int,int)}.
            if (SuggestionStripView.DEBUG_SUGGESTIONS) {
                mDebugInfoViews.get(positionInStrip).setText(null);
            }
        }
        int count = 0;
        int indexInSuggestedWords;
        for (indexInSuggestedWords = 0; indexInSuggestedWords < suggestedWords.size()
                && count < maxSuggestionInStrip; indexInSuggestedWords++) {
            final int positionInStrip =
                    getPositionInSuggestionStrip(indexInSuggestedWords, suggestedWords);
            if (positionInStrip < 0) {
                continue;
            }
            final TextView wordView = mWordViews.get(positionInStrip);
            // {@link TextView#getTag()} is used to get the index in suggestedWords at
            // {@link SuggestionStripView#onClick(View)}.
            wordView.setTag(indexInSuggestedWords);
            wordView.setText(getStyledSuggestedWord(suggestedWords, indexInSuggestedWords));
            wordView.setTextColor(getSuggestionTextColor(suggestedWords, indexInSuggestedWords));
            KeyboardTypeface.applyToTextView(wordView);
            if (SuggestionStripView.DEBUG_SUGGESTIONS) {
                mDebugInfoViews.get(positionInStrip).setText(suggestedWords.getDebugString(indexInSuggestedWords));
            }
            count++;
        }
        return indexInSuggestedWords;
    }

    private int layoutPunctuationsAndReturnStartIndexOfMoreSuggestions(
            final PunctuationSuggestions punctuationSuggestions, final ViewGroup stripView) {
        final int countInStrip = Math.min(punctuationSuggestions.size(), PUNCTUATIONS_IN_STRIP);
        if (mSovereignRowB != null) mSovereignRowB.removeAllViews(); // SovereignBoard: free word B for the strip
        for (int positionInStrip = 0; positionInStrip < countInStrip; positionInStrip++) {
            if (positionInStrip != 0) {
                // Add divider if this isn't the left most suggestion in suggestions strip.
                addDivider(stripView, mDividerViews.get(positionInStrip));
            }

            final TextView wordView = mWordViews.get(positionInStrip);
            final String punctuation = punctuationSuggestions.getLabel(positionInStrip);
            // {@link TextView#getTag()} is used to get the index in suggestedWords at
            // {@link SuggestionStripView#onClick(View)}.
            wordView.setTag(positionInStrip);
            wordView.setText(punctuation);
            wordView.setContentDescription(punctuation);
            wordView.setTextScaleX(1.0f);
            wordView.setGravity(Gravity.CENTER); // SovereignBoard: word slots are start-aligned
            if (mSovereignRowB != null) { // SovereignBoard: undo the word slots' shrink-to-fit
                wordView.setTextSize(TypedValue.COMPLEX_UNIT_PX, mSovereignBaseTextSize);
                wordView.setMaxWidth(Integer.MAX_VALUE);
            }
            wordView.setCompoundDrawables(null, null, null, null);
            wordView.setTextColor(mColorAutoCorrect);
            KeyboardTypeface.applyToTextView(wordView);
            stripView.addView(wordView);
            setLayoutWeight(wordView, 1.0f, mSuggestionsStripHeight);
        }
        mMoreSuggestionsAvailable = (punctuationSuggestions.size() > countInStrip);
        return countInStrip;
    }

    static void setLayoutWeight(final View v, final float weight, final int height) {
        final ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp instanceof final LinearLayout.LayoutParams llp) {
            llp.weight = weight;
            llp.width = 0;
            llp.height = height;
        }
    }

    private static float getTextScaleX(@Nullable final CharSequence text, final int maxWidth, final TextPaint paint) {
        paint.setTextScaleX(1.0f);
        final int width = getTextWidth(text, paint);
        if (width <= maxWidth || maxWidth <= 0) {
            return 1.0f;
        }
        return maxWidth / (float) width;
    }

    @Nullable
    private static CharSequence getEllipsizedTextWithSettingScaleX(
            @Nullable final CharSequence text, final int maxWidth, @NonNull final TextPaint paint) {
        if (text == null) {
            return null;
        }
        final float scaleX = getTextScaleX(text, maxWidth, paint);
        if (scaleX >= MIN_TEXT_XSCALE) {
            paint.setTextScaleX(scaleX);
            return text;
        }

        // <code>text</code> must be ellipsized with minimum text scale x.
        paint.setTextScaleX(MIN_TEXT_XSCALE);
        final boolean hasBoldStyle = hasStyleSpan(text, BOLD_SPAN);
        final boolean hasUnderlineStyle = hasStyleSpan(text, UNDERLINE_SPAN);
        // TextUtils.ellipsize erases any span object existed after ellipsized point.
        // We have to restore these spans afterward.
        final CharSequence ellipsizedText = TextUtils.ellipsize(text, paint, maxWidth, TextUtils.TruncateAt.MIDDLE);
        if (!hasBoldStyle && !hasUnderlineStyle) {
            return ellipsizedText;
        }
        final Spannable spannableText = (ellipsizedText instanceof Spannable)
                ? (Spannable)ellipsizedText : new SpannableString(ellipsizedText);
        if (hasBoldStyle) {
            addStyleSpan(spannableText, BOLD_SPAN);
        }
        if (hasUnderlineStyle) {
            addStyleSpan(spannableText, UNDERLINE_SPAN);
        }
        return spannableText;
    }

    private static boolean hasStyleSpan(@Nullable final CharSequence text,
            final CharacterStyle style) {
        if (text instanceof Spanned) {
            return ((Spanned)text).getSpanStart(style) >= 0;
        }
        return false;
    }

    private static void addStyleSpan(@NonNull final Spannable text, final CharacterStyle style) {
        text.removeSpan(style);
        text.setSpan(style, 0, text.length(), Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
    }

    private static int getTextWidth(@Nullable final CharSequence text, final TextPaint paint) {
        if (TextUtils.isEmpty(text)) {
            return 0;
        }
        final int length = text.length();
        final float[] widths = new float[length];
        final int count;
        final Typeface savedTypeface = paint.getTypeface();
        try {
            paint.setTypeface(getTextTypeface(text));
            count = paint.getTextWidths(text, 0, length, widths);
        } finally {
            paint.setTypeface(savedTypeface);
        }
        int width = 0;
        for (int i = 0; i < count; i++) {
            width += Math.round(widths[i] + 0.5f);
        }
        return width;
    }

    private static Typeface getTextTypeface(@Nullable final CharSequence text) {
        return hasStyleSpan(text, BOLD_SPAN) ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT;
    }
}
