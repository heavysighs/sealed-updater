#!/usr/bin/env python3
"""
Watcher: checks each app's official source for a newer version and, when one
appears, republishes the signed feed (feed/list.json + feed/list.json.sig).

Safety rules baked in:
- An entry is only updated when the new APK's signing certificate is
  IDENTICAL to the certificate in the current feed entry (or the package is
  being bootstrapped). A changed signer halts that app's updates, loudly.
- Facts in the feed (package, versionCode, sha256, cert) are always read
  from the actual downloaded APK, never from filenames or web pages.

Requires: apksigner + aapt2 from the Android SDK (present on GitHub runners),
the `cryptography` pip package, and env LIST_SIGNING_KEY (PEM EC private key).
"""
import base64
import glob
import hashlib
import json
import os
import re
import subprocess
import sys
import tempfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FEED = os.path.join(ROOT, "feed", "list.json")
SIG = FEED + ".sig"

# Where each app officially publishes. This is the ONLY place per-app
# knowledge lives; the phone app knows none of it.
GITHUB_SOURCES = [
    ("app.clearsms", "itsluminous/ClearSMS", r"^ClearSMS\.apk$"),
    ("neth.iecal.curbox", "curbox-app/curbox-android", r"^curbox-.*-fdroid\.apk$"),
    ("io.github.ronynn.karui", "ronynn/karui", r"^karui-release\.apk$"),
    ("com.infinity.readz", "ZHINFINITY/ReadInfinity", r"^ReadInfinity_.*_arm64-v8a\.apk$"),
    ("org.breezyweather", "breezy-weather/breezy-weather",
     r"^breezy-weather-arm64-v8a-.*_standard\.apk$"),
]
FDROID_SOURCES = [
    # Bati: the copy on the phone is F-Droid-signed, and Android only accepts
    # updates from the same signer, so Bati updates must come from F-Droid.
    "com.guiforge.bati",
    "de.binauralbeats.app.foss",
    "xyz.linerly.nontrinsic",
]

UA = {"User-Agent": "sealed-updater-watcher"}


def http_json(url, token=None):
    headers = dict(UA)
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=120) as r:
        return json.loads(r.read().decode("utf-8"))


def http_download(url, dest):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=600) as r, open(dest, "wb") as f:
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)


def find_tool(name):
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or ""
    hits = sorted(glob.glob(os.path.join(sdk, "build-tools", "*", name)))
    if not hits:
        sys.exit(f"ERROR: {name} not found under ANDROID_HOME={sdk}")
    return hits[-1]


def inspect_apk(path):
    aapt2 = find_tool("aapt2")
    apksigner = find_tool("apksigner")
    out = subprocess.run([aapt2, "dump", "badging", path],
                         capture_output=True, text=True, check=True).stdout
    m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'", out)
    if not m:
        raise ValueError(f"could not parse badging for {path}")
    cert_out = subprocess.run([apksigner, "verify", "--print-certs", path],
                              capture_output=True, text=True, check=True).stdout
    cm = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", cert_out)
    if not cm:
        raise ValueError(f"could not read signer cert for {path}")
    sha = hashlib.sha256(open(path, "rb").read()).hexdigest()
    return {
        "package": m.group(1),
        "versionCode": int(m.group(2)),
        "versionName": m.group(3),
        "sha256": sha,
        "certSha256": cm.group(1),
    }


def github_latest(repo, pattern, token):
    data = http_json(f"https://api.github.com/repos/{repo}/releases/latest", token)
    matches = [a for a in data.get("assets", []) if re.match(pattern, a["name"])]
    if len(matches) == 1:
        return matches[0]["browser_download_url"]
    if len(matches) > 1:
        # Several assets match: only an unambiguous arm64 pick is acceptable.
        arm = [a for a in matches if "arm64" in a["name"]]
        if len(arm) == 1:
            return arm[0]["browser_download_url"]
        print(f"WARN: {repo}: {len(matches)} assets match {pattern}, ambiguous; skipping")
    return None


def fdroid_latest(pkg):
    data = http_json(f"https://f-droid.org/api/v1/packages/{pkg}")
    vc = data["suggestedVersionCode"]
    return f"https://f-droid.org/repo/{pkg}_{vc}.apk"


def main():
    token = os.environ.get("GITHUB_TOKEN")
    feed = {"version": 1, "apps": []}
    if os.path.exists(FEED):
        feed = json.load(open(FEED))
    current = {a["package"]: a for a in feed["apps"]}
    changed = False

    candidates = []
    for pkg, repo, pattern in GITHUB_SOURCES:
        try:
            url = github_latest(repo, pattern, token)
        except Exception as e:  # one source being down must not stop the rest
            print(f"WARN: {pkg}: source check failed ({e}); keeping old entry")
            continue
        if url:
            candidates.append((pkg, url))
        else:
            print(f"WARN: no matching asset for {pkg} in {repo}")
    for pkg in FDROID_SOURCES:
        try:
            candidates.append((pkg, fdroid_latest(pkg)))
        except Exception as e:
            print(f"WARN: {pkg}: F-Droid check failed ({e}); keeping old entry")

    with tempfile.TemporaryDirectory() as tmp:
        for pkg, url in candidates:
            dest = os.path.join(tmp, pkg + ".apk")
            try:
                http_download(url, dest)
                info = inspect_apk(dest)
            except Exception as e:  # keep the old entry on any failure
                print(f"WARN: {pkg}: fetch/inspect failed ({e}); keeping old entry")
                continue
            if info["package"] != pkg:
                print(f"WARN: {pkg}: downloaded APK is {info['package']}; keeping old entry")
                continue
            old = current.get(pkg)
            if old and info["versionCode"] <= old["versionCode"]:
                print(f"{pkg}: no newer version ({info['versionCode']})")
                continue
            if old and info["certSha256"] != old["certSha256"]:
                print(f"ALARM: {pkg}: SIGNING CERT CHANGED - refusing to publish. "
                      f"Old {old['certSha256'][:12]}... new {info['certSha256'][:12]}...")
                continue
            info["url"] = url
            current[pkg] = info
            changed = True
            print(f"{pkg}: feed updated to versionCode {info['versionCode']}")

    if not changed:
        print("Feed unchanged.")
        return

    feed["apps"] = [current[k] for k in sorted(current)]
    payload = (json.dumps(feed, indent=1, sort_keys=True) + "\n").encode("utf-8")
    key_pem = os.environ.get("LIST_SIGNING_KEY")
    if not key_pem:
        sys.exit("ERROR: feed changed but LIST_SIGNING_KEY is not set; not writing.")
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    key = serialization.load_pem_private_key(key_pem.encode(), password=None)
    sig = key.sign(payload, ec.ECDSA(hashes.SHA256()))
    with open(FEED, "wb") as f:
        f.write(payload)
    with open(SIG, "w") as f:
        f.write(base64.b64encode(sig).decode() + "\n")
    print("Feed republished and signed.")


if __name__ == "__main__":
    main()
