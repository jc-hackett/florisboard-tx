"""Spoken punctuation for dictation: "exclamation point" -> "!", "quote ... unquote" -> "...".

Applied to the raw transcript before the tidy step. The speech model often adds its own comma or
full stop around a spoken command ("great, exclamation point."), so each command also swallows
the punctuation directly around it.
"""
import re

# Commands that become a mark attached to the previous word ("great!"), longest first.
_ATTACH = [
    (r"exclamation (?:point|mark)", "!"),
    (r"question mark", "?"),
    (r"semi-?colon", ";"),
    (r"colon", ":"),
    (r"comma", ","),
    # "period" only as a command when nothing ordinary follows ("a period of time" stays).
    (r"full stop(?=\s*(?:$|[,.;:!?\n]|(?-i:[A-Z])|new (?:line|paragraph)))", "."),
    (r"period(?=\s*(?:$|[,.;:!?\n]|(?-i:[A-Z])|new (?:line|paragraph)))", "."),
    (r"ellipsis|dot dot dot", "…"),
]
_BREAKS = [
    (r"new paragraph", "\n\n"),
    (r"new line|next line", "\n"),
]
_PUNCT = r"[,.;:!?]*"

_OPEN_QUOTE = r"(?:open quote|begin quote|quote)"
_CLOSE_QUOTE = r"(?:close quote|end quote|unquote|end of quote)"


def _cmd(words: str) -> str:
    # A command, plus any punctuation the model hung on either side of it.
    return rf"{_PUNCT}\s*(?<![\w'])(?:{words})(?![\w']){_PUNCT}"


def apply(text: str) -> str:
    if not text:
        return text
    t = text

    # "quote unquote freedom" -> "freedom" in quotes (the scare-quote idiom).
    t = re.sub(rf"(?i){_cmd('quote,? unquote')}\s*([\w'-]+)", lambda m: f' "{m.group(1)}"', t)
    # "quote ... unquote" -> "...". Only when both ends are said, so a plain "quote" stays a word.
    t = re.sub(rf"(?is){_cmd(_OPEN_QUOTE)}\s*(.+?)\s*{_cmd(_CLOSE_QUOTE)}",
               lambda m: f' "{m.group(1).strip(" ,.")}"', t)

    for words, mark in _ATTACH:
        t = re.sub(rf"(?i)\s*{_cmd(words)}", mark, t)
    for words, brk in _BREAKS:
        # Breaks keep whatever mark came before them ("Tony, new paragraph").
        t = re.sub(rf"(?i)[ \t]*(?<![\w'])(?:{words})(?![\w']){_PUNCT}\s*", brk, t)

    # Tidy spacing around what we inserted.
    t = re.sub(r"[ \t]+([,.;:!?…])", r"\1", t)            # no space before a mark
    t = re.sub(r"([,;:!?…])(?=[^\s\"'\n,.;:!?…])", r"\1 ", t)  # one space after
    t = re.sub(r"(?<=\w)\.(?=[A-Za-z])", ". ", t)
    t = re.sub(r"([.!?]){2,}", r"\1", t)                   # "!." -> "!"
    t = re.sub(r"\"\s+([.,;:!?])", r'"\1', t)
    t = re.sub(r"[ \t]{2,}", " ", t)
    t = re.sub(r"[ \t]*\n[ \t]*", "\n", t)
    t = re.sub(r"(\n)([a-z])", lambda m: m.group(1) + m.group(2).upper(), t)  # a new line starts a sentence
    return t.strip(" ")


if __name__ == "__main__":
    tests = [
        "I love this, exclamation point.",
        "That is so fast exclamation point",
        "Is it working question mark",
        "Here is the plan colon first we test.",
        "She said quote I'm fine unquote and left.",
        "It was quote, unquote, freedom.",
        "Dear Tony comma new paragraph thanks for looking.",
        "A quote from the book is nice.",
        "We waited for a period of time.",
        "That is all period",
        "That is all period. Next thing.",
        "Check his colon and comma please.",
    ]
    for s in tests:
        print(repr(s), "->", repr(apply(s)))
