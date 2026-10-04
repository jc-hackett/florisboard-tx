"""De-identification for dictated text before it goes to Claude.

Replaces names, places, organisations, dates/times, phone numbers, emails, URLs,
long digit runs and any remaining capitalised non-sentence-initial word with
placeholders like [NAME_1]. Errs on the side of over-redacting: a placeholder
costs a little cleanup quality, a missed name costs privacy.
"""
import re
from collections import Counter

import spacy

_nlp = spacy.load("en_core_web_sm", disable=["lemmatizer"])

_LABELS = {
    "PERSON": "NAME",
    "GPE": "PLACE", "LOC": "PLACE", "FAC": "PLACE",
    "ORG": "ORG", "NORP": "GROUP",
    "DATE": "DATE", "TIME": "TIME",
}

_REGEXES = [
    ("EMAIL", re.compile(r"\b[\w.+-]+@[\w-]+(?:\.[\w-]+)+\b")),
    ("URL", re.compile(r"\b(?:https?://|www\.)\S+", re.I)),
    ("PHONE", re.compile(r"(?<!\w)(?:\+?\d[\d\s().-]{6,}\d)(?!\w)")),
    ("DATE", re.compile(r"\b\d{1,4}[/-]\d{1,2}[/-]\d{1,4}\b")),
    ("NUM", re.compile(r"\b\d{3,}\b")),  # ages are short; MRNs, zips, account numbers are not
]

# Capitalised words that are fine to leave in place.
_SAFE_CAPS = {
    "I", "I'm", "I've", "I'll", "I'd", "OK", "Okay", "Um", "Uh", "Mr", "Mrs", "Ms", "Dr",
    "Mr.", "Mrs.", "Ms.", "Dr.", "God", "English", "Spanish",
}

# Ordinary words that only got a capital by mistake ("I wanted Want it") shouldn't be hidden
# from the proofreader. The system word list marks proper nouns with a capital ("Tony", "Grace",
# "Will" all appear capitalised), so a capitalised word is let through only when its lower-case
# form is listed and its capitalised form is not. Unknown words stay hidden: privacy first.
_WORDS_FILE = "/usr/share/dict/american-english"
try:
    with open(_WORDS_FILE, encoding="utf-8", errors="ignore") as _f:
        _DICT = {line.strip() for line in _f if line.strip()}
except OSError:
    _DICT = set()


def _ordinary_word(w: str) -> bool:
    return bool(_DICT) and w.lower() in _DICT and w not in _DICT


_PLACEHOLDER = re.compile(r"\[([A-Z]+)_(\d+)\]")


def deidentify(text: str):
    """Return (masked_text, mapping placeholder -> original)."""
    spans = []  # (start, end, kind)

    for kind, rx in _REGEXES:
        for m in rx.finditer(text):
            spans.append((m.start(), m.end(), kind))

    doc = _nlp(text)
    for ent in doc.ents:
        kind = _LABELS.get(ent.label_)
        if kind:
            spans.append((ent.start_char, ent.end_char, kind))

    # Fallback: capitalised tokens not at the start of a sentence.
    for sent in doc.sents:
        for tok in list(sent)[1:]:
            w = tok.text
            if (w[:1].isupper() and w not in _SAFE_CAPS and not _ordinary_word(w)
                    and not (tok.i > 0 and doc[tok.i - 1].text in {".", "!", "?", "\"", "'", ":"})):
                spans.append((tok.idx, tok.idx + len(w), "NAME"))

    # A possessive spoken without its apostrophe ("Johns visit") would be hidden whole, and the
    # proofreader could never add the "'s". When a hidden name ends in "s", its "X's" form is a
    # word the dictionary knows, and the next word is a thing rather than an action, hide only
    # "John" and leave the "s" in view. ("James said" keeps "James" whole.)
    by_start = {t.idx: t for t in doc}
    adjusted = []
    for start, end, kind in spans:
        word = text[start:end]
        tok = by_start.get(start)
        nxt = doc[tok.i + 1] if tok is not None and tok.i + 1 < len(doc) else None
        if (kind in ("NAME", "ORG", "GROUP") and " " not in word and len(word) > 3 and word.endswith("s")
                and f"{word[:-1]}'s" in _DICT and nxt is not None and nxt.pos_ in ("NOUN", "PROPN")):
            end -= 1
        adjusted.append((start, end, kind))
    spans = adjusted

    # Merge overlaps: keep the earliest-starting, then longest span.
    spans.sort(key=lambda s: (s[0], -(s[1] - s[0])))
    merged = []
    for s in spans:
        if merged and s[0] < merged[-1][1]:
            if s[1] > merged[-1][1]:
                merged[-1] = (merged[-1][0], s[1], merged[-1][2])
            continue
        merged.append(s)

    mapping, reverse, counters = {}, {}, {}
    out, pos = [], 0
    for start, end, kind in merged:
        original = text[start:end]
        key = (kind, original.lower())
        if key not in reverse:
            counters[kind] = counters.get(kind, 0) + 1
            ph = f"[{kind}_{counters[kind]}]"
            reverse[key] = ph
            mapping[ph] = original
        out.append(text[pos:start])
        out.append(reverse[key])
        pos = end
    out.append(text[pos:])
    return "".join(out), mapping


def _counts(s: str) -> Counter:
    return Counter(m.group(0) for m in _PLACEHOLDER.finditer(s))


def reidentify(cleaned: str, mapping: dict, masked_in: str):
    """Put originals back. Returns None if the model dropped, invented or duplicated a placeholder."""
    if _counts(cleaned) != _counts(masked_in):
        return None
    return _PLACEHOLDER.sub(lambda m: mapping[m.group(0)], cleaned)
