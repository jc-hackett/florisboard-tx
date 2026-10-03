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


# --- Spoken Markdown: "MD" + a command (added 2026-10-03) -----------------------------------
# The speech model may hear "MD" as "M.D.", "MD", "Md", "em dee", or the word "markdown".
_MD = r"(?:m\.?\s?d\.?|em\s?dee|markdown)"
_URL_JOINERS = ("dot", "slash", "colon", "dash", "hyphen")
_LINK = re.compile(r"\[[^\]\n]*\]\([^)\s]*\)")
_NUM = {"one": "1", "won": "1", "two": "2", "to": "2", "too": "2", "three": "3", "1": "1", "2": "2", "3": "3"}


def _spoken_url(words: str) -> str:
    """'proton dot me slash pricing' -> 'https://proton.me/pricing'."""
    u = words.strip().strip(" .,").lower()
    u = re.sub(r"\s*\b(?:dot)\b\s*", ".", u)
    u = re.sub(r"\s*\b(?:slash)\b\s*", "/", u)
    u = re.sub(r"\s*\b(?:colon)\b\s*", ":", u)
    u = re.sub(r"\s*\b(?:dash|hyphen)\b\s*", "-", u)
    u = u.replace(" ", "")
    if not re.match(r"^[a-z]+://", u):
        u = "https://" + u
    return u


def markdown(text: str) -> str:
    t = text
    # Links first: "MD link <text> MD to <address>" -> [text](https://address). The address is
    # read word by word ("proton dot me slash pricing") and ends at the first ordinary word.
    def link(m):
        label = m.group(1).strip(" ,.")
        words = m.group(2).split()
        taken = words[:1]
        i = 1
        while i + 1 < len(words) and words[i].lower().strip(",.") in _URL_JOINERS:
            taken += words[i:i + 2]
            i += 2
        end_punct = ""
        if taken and taken[-1][-1:] in ",.;!?":
            end_punct = taken[-1][-1]
            taken[-1] = taken[-1][:-1]
        rest = " ".join(words[i:])
        return f"[{label}]({_spoken_url(' '.join(taken))}){end_punct}" + (" " + rest if rest else "")
    t = re.sub(rf"(?i)\b{_MD}[,.]?\s+link[,.]?\s+(.+?)[,.]?\s+{_MD}[,.]?\s+(?:to|url|address)[,.]?\s+([^\n]+)",
               link, t)
    # Headings: "MD H1 Title" -> "# Title", on its own line.
    def heading(m):
        n = _NUM.get(m.group(1).lower(), "1")
        return "\n" + "#" * int(n) + " "
    t = re.sub(rf"(?i)[ \t]*\b{_MD}[,.]?\s+(?:h|age|each)[\s-]?(one|won|two|to|too|three|[123])\b[,.:]?\s*", heading, t)
    # Line starters.
    for words, mark in [(r"bullet(?:\s?point)?", "- "), (r"check\s?box", "- [ ] "),
                        (r"number(?:ed)?(?:\s?item)?", "1. "), (r"quote|block\s?quote", "> ")]:
        t = re.sub(rf"(?i)[ \t]*\b{_MD}[,.]?\s+(?:{words})\b[,.:]?\s*", "\n" + mark, t)
    # Inline toggles: "MD bold" ... "MD bold" -> **...**
    for words, mark in [(r"bold", "**"), (r"italics?", "*"), (r"code", "`")]:
        t = re.sub(rf"(?i)\s*\b{_MD}[,.]?\s+(?:{words})\b[,.]?\s*(.+?)\s*\b{_MD}[,.]?\s+(?:end\s+)?(?:{words})\b",
                   lambda m, k=mark: f" {k}{m.group(1).strip(' ,.')}{k}", t)
    # Tidy: no leading newline at the very start, no doubled blank lines from consecutive commands.
    t = re.sub(r"\n{3,}", "\n\n", t).lstrip("\n")
    t = re.sub(r"(?m)^(#{1,3} .*?)\.[ \t]*$", r"\1", t)  # headings don't end in a full stop
    return t


def apply(text: str) -> str:
    if not text:
        return text
    t = markdown(text)
    # Keep finished Markdown links out of the punctuation and spacing rules below.
    links = _LINK.findall(t)
    for i, link in enumerate(links):
        t = t.replace(link, f"\x00{i}\x00", 1)

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
    for i, link in enumerate(links):
        t = t.replace(f"\x00{i}\x00", link)
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
        "MD H1 Meeting notes. MD bullet call Tony. MD bullet send the spec.",
        "M.D. H2, next steps md checkbox book the room",
        "This is MD bold really important MD bold, okay.",
        "See MD link Proton Mail MD to proton dot me for details.",
        "Markdown H three Summary",
    ]
    for s in tests:
        print(repr(s), "->", repr(apply(s)))
