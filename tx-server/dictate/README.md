# dictate

Server half of the florisboard-tx dictation key. Phone -> HTTPS -> Caddy -> 127.0.0.1:8790.
Whisper (faster-whisper small.en, int8, CPU) transcribes in memory; if ANTHROPIC_API_KEY is set,
the text is de-identified (deid.py), tidied by Claude over the Messages API, and re-identified.
Audio never touches disk; logs hold timing/status only.

- Settings: dictate.env (root-only). Tokens: `./add-token.py <name>` (hashes stored in `tokens`).
- Unit: /etc/systemd/system/dictate.service (copy of dictate.service here).
- Caddy: /etc/caddy/sites/dictate.caddy(.disabled).
- Moving to its own server: copy /opt/dictate, create user `dictate`, install unit + Caddy block,
  point DNS. No code change.
