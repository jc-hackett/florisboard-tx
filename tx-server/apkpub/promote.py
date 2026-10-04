#!/usr/bin/env python3
"""Release the build on the test lane to everyone.

  promote.py "short note for the release"

Copies /srv/dictate-app/beta/{florisboard-tx.apk,latest.json} to /srv/dictate-app/, gives the
release the next number, and appends it to /srv/dictate-app/releases.json (the record of what
went out and when). Run it only when Jeremiah says "release".
"""
import json
import os
import shutil
import sys
import time

BETA = "/srv/dictate-app/beta"
OUT = "/srv/dictate-app"
RELEASES = os.path.join(OUT, "releases.json")


def main():
    note = " ".join(sys.argv[1:]).strip()
    with open(os.path.join(BETA, "latest.json")) as f:
        info = json.load(f)
    try:
        with open(RELEASES) as f:
            releases = json.load(f)
    except (OSError, ValueError):
        releases = []
    number = f"0.{len(releases) + 1}"
    info.update({"release": number, "released": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "note": note})
    tmp = os.path.join(OUT, ".florisboard-tx.apk.new")
    shutil.copyfile(os.path.join(BETA, info.get("apk", "florisboard-tx.apk")), tmp)
    os.chmod(tmp, 0o644)
    os.replace(tmp, os.path.join(OUT, "florisboard-tx.apk"))
    with open(os.path.join(OUT, "latest.json.new"), "w") as f:
        json.dump(info, f)
    os.chmod(os.path.join(OUT, "latest.json.new"), 0o644)
    os.replace(os.path.join(OUT, "latest.json.new"), os.path.join(OUT, "latest.json"))
    releases.append({"release": number, "build": info["build"], "released": info["released"], "note": note})
    with open(RELEASES, "w") as f:
        json.dump(releases, f, indent=1)
    os.chmod(RELEASES, 0o644)
    print(f"released {number} ({info['build'][:8]})")


if __name__ == "__main__":
    main()
