#!/usr/bin/env python3
"""Publish the newest florisboard-tx build for the in-app updater.

Runs every few minutes (apkpub.timer). Finds the newest successful CI run on the branch, downloads
its APK (public repo, via nightly.link - no GitHub login needed), re-signs it with the ONE fixed
florisboard-tx key so every version installs over the last, verifies the signature, and publishes:

  /srv/dictate-app/florisboard-tx.apk   the newest build
  /srv/dictate-app/latest.json          {"build": <commit sha>, "sha256", "size", "published", "apk"}

Caddy serves that folder at https://dictate.limn.dev/app/. The phone compares "build" with its own
BuildConfig.BUILD_COMMIT_HASH and offers the update when they differ.

The signing key lives only in /root/apk-signing (backup in the owner's potent-stuff).
"""
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.request
import zipfile

REPO = "jc-hackett/florisboard-tx"
BRANCH = "feat/dictate"
WORKFLOW = "android.yml"
ARTIFACT = "app-debug.apk"
PACKAGE = "me.jchackett.florisboardtx"
# Every successful build goes to the TEST lane only. Releasing to everyone is a separate,
# deliberate step: /opt/apkpub/promote.py (copies the tested build into /srv/dictate-app).
OUT = "/srv/dictate-app/beta"
KEYDIR = "/root/apk-signing"
SERVER_ONLY = ("tx-server/", ".github/", "README", "docs/")


def get_json(url):
    req = urllib.request.Request(url, headers={"Accept": "application/vnd.github+json",
                                               "User-Agent": "florisboard-tx-apkpub"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def main():
    runs = get_json(f"https://api.github.com/repos/{REPO}/actions/workflows/{WORKFLOW}/runs"
                    f"?branch={BRANCH}&status=success&per_page=1")["workflow_runs"]
    if not runs:
        return
    run = runs[0]
    sha = run["head_sha"]
    latest_path = os.path.join(OUT, "latest.json")
    published = None
    try:
        with open(latest_path) as f:
            published = json.load(f).get("build")
    except (OSError, ValueError):
        pass
    if published == sha:
        return  # already published
    if published:
        # Server-only commits (tx-server/, CI config, docs) don't change the app: don't offer the
        # phone an "update" that changes nothing. The next real app change includes them anyway.
        changed = get_json(f"https://api.github.com/repos/{REPO}/compare/{published}...{sha}").get("files", [])
        if changed and all(f["filename"].startswith(SERVER_ONLY) for f in changed):
            return

    with tempfile.TemporaryDirectory() as tmp:
        zpath = os.path.join(tmp, "a.zip")
        url = f"https://nightly.link/{REPO}/actions/runs/{run['id']}/{ARTIFACT}.zip"
        urllib.request.urlretrieve(url, zpath)
        with zipfile.ZipFile(zpath) as z:
            z.extract(ARTIFACT, tmp)
        unsigned = os.path.join(tmp, ARTIFACT)
        signed = os.path.join(tmp, "signed.apk")
        with open(os.path.join(KEYDIR, "keystore.pass")) as f:
            pw = f.read().strip()
        env = dict(os.environ, KS_PASS=pw)
        subprocess.run(["apksigner", "sign", "--ks", os.path.join(KEYDIR, "florisboard-tx.jks"),
                        "--ks-key-alias", "florisboard-tx", "--ks-pass", "env:KS_PASS",
                        "--key-pass", "env:KS_PASS", "--out", signed, unsigned],
                       check=True, env=env, capture_output=True)
        certs = subprocess.run(["apksigner", "verify", "--print-certs", signed],
                               check=True, capture_output=True, text=True).stdout
        if "CN=florisboard-tx" not in certs:
            sys.exit("signed APK does not carry the florisboard-tx certificate")
        with zipfile.ZipFile(signed) as z:
            if PACKAGE.encode() not in z.read("AndroidManifest.xml").replace(b"\x00", b""):
                sys.exit("APK is not florisboard-tx")
        data = open(signed, "rb").read()
        digest = hashlib.sha256(data).hexdigest()

        os.makedirs(OUT, exist_ok=True)
        tmp_apk = os.path.join(OUT, ".florisboard-tx.apk.new")
        with open(tmp_apk, "wb") as f:
            f.write(data)
        os.chmod(tmp_apk, 0o644)
        os.replace(tmp_apk, os.path.join(OUT, "florisboard-tx.apk"))
        info = {"build": sha, "short": sha[:8], "sha256": digest, "size": len(data),
                "published": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
                "apk": "florisboard-tx.apk", "run": run["id"]}
        tmp_json = latest_path + ".new"
        with open(tmp_json, "w") as f:
            json.dump(info, f)
        os.chmod(tmp_json, 0o644)
        os.replace(tmp_json, latest_path)
        print(f"published {sha[:8]} ({len(data)} bytes)")


if __name__ == "__main__":
    main()
