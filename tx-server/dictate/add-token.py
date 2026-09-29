#!/opt/dictate/venv/bin/python
"""Make a new bearer token for one person/phone: add-token.py <name>
Prints the token once; only its SHA-256 hash is stored in /opt/dictate/tokens.
Remove a line from that file to revoke (takes effect immediately)."""
import hashlib, os, re, secrets, sys
name = sys.argv[1] if len(sys.argv) > 1 else ""
if not re.fullmatch(r"[a-z0-9_-]{1,40}", name):
    sys.exit("usage: add-token.py <name>  (lowercase letters, digits, - _)")
tok = "dt_" + secrets.token_urlsafe(32)
path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "tokens")
with open(path, "a") as f:
    f.write(f"{name}:{hashlib.sha256(tok.encode()).hexdigest()}\n")
print(tok)
