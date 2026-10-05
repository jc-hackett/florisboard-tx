#!/usr/bin/env python3
"""Make (or remake) someone's SovereignBoard invite: make-invite.py <name>

- Gives <name> a brand-new access key (any older key for <name> stops working at once).
- Stores that key ONCE, in the claims file, behind a random one-time code.
- Prints the invite link: https://dictate.limn.dev/app/setup.html#c=<code>

The setup page's "Reveal my key" swaps the code for the key a single time; the server deletes
it as it hands it over ("shredded"). Unclaimed invites expire after 7 days. Run as root.
"""
import hashlib
import json
import os
import re
import secrets
import sys
import time

BASE = os.path.dirname(os.path.abspath(__file__))
TOKENS = os.path.join(BASE, "tokens")
CLAIMS = "/var/lib/dictate/claims.json"
SITE = "https://dictate.limn.dev/app/setup.html"


def main():
    name = sys.argv[1] if len(sys.argv) > 1 else ""
    if not re.fullmatch(r"[a-z0-9_-]{1,40}", name):
        sys.exit("usage: make-invite.py <name>  (lowercase letters, digits, - _)")
    token = "dt_" + secrets.token_urlsafe(32)
    # Replace this person's key line (or add one).
    lines = []
    if os.path.exists(TOKENS):
        with open(TOKENS) as f:
            lines = [l for l in f.read().splitlines() if l and not l.startswith(name + ":")]
    lines.append(f"{name}:{hashlib.sha256(token.encode()).hexdigest()}")
    with open(TOKENS + ".new", "w") as f:
        f.write("\n".join(lines) + "\n")
    os.replace(TOKENS + ".new", TOKENS)
    # One-time claim, keyed by the hash of a hex-only code (survives any app's link handling).
    code = secrets.token_hex(16)
    try:
        with open(CLAIMS) as f:
            claims = json.load(f)
    except (OSError, ValueError):
        claims = {}
    now = time.time()
    claims = {k: v for k, v in claims.items() if now - v.get("created", 0) < 7 * 86400 and v.get("user") != name}
    claims[hashlib.sha256(code.encode()).hexdigest()] = {"user": name, "token": token, "created": now}
    with open(CLAIMS + ".new", "w") as f:
        json.dump(claims, f)
    os.chmod(CLAIMS + ".new", 0o600)
    try:
        import pwd
        u = pwd.getpwnam("dictate")
        os.chown(CLAIMS + ".new", u.pw_uid, u.pw_gid)
    except (KeyError, ImportError):
        pass
    os.replace(CLAIMS + ".new", CLAIMS)
    print(f"{SITE}#c={code}")


if __name__ == "__main__":
    main()
