# SovereignBoard H

The HeliBoard edition of SovereignBoard: HeliBoard's typing engine (GPL-3.0, see LICENSE) with
SovereignBoard's dictation, AI cleanup and update features added on top. It talks to the same
dictate server as the FlorisBoard edition (branch feat/dictate).

Based on HeliBoard (https://github.com/Helium314/HeliBoard), unmodified except where marked
"SovereignBoard". Nothing here is offered back upstream.

## Bundled emoji dictionary

`app/src/main/assets/dicts/emoji_en.dict` is the English emoji dictionary from
https://codeberg.org/Helium314/aosp-dictionaries (emoji_cldr_signal_dictionaries/emoji_en.dict,
commit 482a69d8, SHA-256 4102dd0cbed74fd1f81f69ce99ec07e6c474f257501f8273338cfb2725fd85c4).
It was built from Unicode CLDR (Unicode License V3) and Signal's emoji-search-index (AGPL-3.0);
GPL-3.0 section 13 allows it to be combined with this GPL-3.0 app, and it keeps its own licences.
