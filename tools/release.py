#!/usr/bin/env python3
"""One-command release for Jarvis.

Usage:
  python tools/release.py 0.2.0           # explicit version
  python tools/release.py --patch         # 0.1.0 -> 0.1.1
  python tools/release.py --minor         # 0.1.0 -> 0.2.0
  python tools/release.py --major         # 0.1.0 -> 1.0.0
  python tools/release.py --patch --notes NOTES.md
  python tools/release.py --dry-run       # print the plan, touch nothing

Does: clean-tree check -> bump versionCode/versionName -> commit -> tag
      -> assembleRelease -> verify signature -> push (main + tag)
      -> create GitHub Release -> upload APK asset.

Requires: the release keystore + keystore.properties (local, gitignored),
git push access to origin, and a stored GitHub credential (git credential fill).
"""

import argparse
import base64
import json
import re
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = ROOT / "app" / "build.gradle.kts"
APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
SDK_BT = Path(r"C:/Users/Lohar/AppData/Local/Android/Sdk/build-tools")
REPO = "Abhishek-karma/Jarvis"


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, text=True, shell=True, **kw)
    if r.returncode != 0:
        sys.exit(f"[FAIL] {' '.join(cmd) if isinstance(cmd, str) else cmd}\n{r.stderr or r.stdout}")
    return r.stdout.strip()


def sh(cmd):
    return subprocess.run(cmd, capture_output=True, text=True, shell=True, cwd=ROOT)


def git(*args):
    return run("git " + " ".join(args), cwd=ROOT)


def die(msg):
    sys.exit(f"[FAIL] {msg}")


def current_version():
    text = GRADLE.read_text(encoding="utf-8")
    m = re.search(r'versionCode\s*=\s*(\d+)', text)
    n = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    if not m or not n:
        die("cannot find versionCode/versionName in app/build.gradle.kts")
    return int(m.group(1)), n.group(1)


def bump(version, part):
    a, b, c = (int(x) for x in version.split("."))
    return {"major": (a + 1, 0, 0), "minor": (a, b + 1, 0), "patch": (a, b, c + 1)}[part]


def set_version(code, version):
    text = GRADLE.read_text(encoding="utf-8")
    text = re.sub(r'versionCode\s*=\s*\d+', f'versionCode = {code}', text)
    text = re.sub(r'versionName\s*=\s*"[^"]+"', f'versionName = "{version}"', text)
    GRADLE.write_text(text, encoding="utf-8")


def gh_token():
    r = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n",
                       capture_output=True, text=True)
    for line in r.stdout.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1]
    return None


def gh_api(url, token, data=None, content_type="application/json"):
    req = urllib.request.Request(url, method="POST" if data is not None else "GET")
    req.add_header("Authorization", f"Bearer {token}")
    req.add_header("Accept", "application/vnd.github+json")
    if data is not None:
        req.add_header("Content-Type", content_type)
        req.data = data
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("version", nargs="?", help="explicit version, e.g. 0.2.0")
    ap.add_argument("--patch", action="store_true")
    ap.add_argument("--minor", action="store_true")
    ap.add_argument("--major", action="store_true")
    ap.add_argument("--notes", help="path to release-notes markdown (default: generated)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    code, cur = current_version()
    if args.version:
        ver = args.version.lstrip("v")
    elif args.patch:
        ver = ".".join(map(str, bump(cur, "patch")))
    elif args.minor:
        ver = ".".join(map(str, bump(cur, "minor")))
    elif args.major:
        ver = ".".join(map(str, bump(cur, "major")))
    else:
        die("give a version (e.g. 0.2.0) or one of --patch/--minor/--major")
    if tuple(int(x) for x in ver.split(".")) <= tuple(int(x) for x in cur.split(".")):
        die(f"version {ver} must be greater than current {cur}")

    tag = f"v{ver}"
    print(f"[plan] {cur} (code {code})  ->  {ver} (code {code + 1})  tag {tag}")

    if git("status --porcelain"):
        die("working tree not clean — commit or stash first")
    branch = git("rev-parse --abbrev-ref HEAD")
    if branch != "main":
        die(f"releases run from main only (you are on {branch})")

    if args.dry_run:
        print("[dry-run] would: bump, commit 'Release {tag}', tag, assembleRelease,")
        print("[dry-run]   verify signature, push main+tag, create GitHub Release, upload APK")
        return

    # 1. bump + commit + tag
    set_version(code + 1, ver)
    git("add app/build.gradle.kts")
    git(f'commit -m "Release {tag}"')
    git(f'tag -a {tag} -m "Jarvis {tag}"')
    print(f"[1/5] bumped to {ver}, committed + tagged {tag}")

    # 2. build
    print("[2/5] building signed release APK (~4 min) ...")
    r = sh("gradlew.bat :app:assembleRelease --console=plain")
    if r.returncode != 0:
        sys.exit(r.stdout[-3000:])
    if not APK.exists():
        die("APK not found after build")
    print(f"      built {APK.name}  {APK.stat().st_size / 1e6:.1f} MB")

    # 3. verify signature
    bt = sorted(SDK_BT.glob("*"))[-1]
    r = sh(f'"{bt / "apksigner.bat"}" verify --print-certs "{APK}"')
    if r.returncode != 0 or "V2 Signer" not in r.stdout:
        die("APK signature verification failed")
    print("[3/5] signature verified (V2)")

    # 4. push
    git("push origin main")
    git(f"push origin {tag}")
    print("[4/5] pushed main + tag")

    # 5. GitHub release
    token = gh_token()
    if not token:
        die("no stored GitHub credential (git credential fill returned nothing)")
    slug = ver.replace(".", "")
    body = (Path(args.notes).read_text(encoding="utf-8") if args.notes else
            f"## Jarvis {tag}\n\nSee commits since the previous release for changes.\n\n"
            f"## Install\nDownload `Jarvis-{tag}.apk` below and open it on your phone (Android 10+).")
    payload = json.dumps({"tag_name": tag, "name": f"Jarvis {tag}", "body": body,
                          "draft": False, "prerelease": False}).encode()
    rel = gh_api(f"https://api.github.com/repos/{REPO}/releases", token, payload)
    asset_name = f"Jarvis-{tag}.apk"
    up = rel["upload_url"].split("{")[0] + f"?name={asset_name}"
    asset = gh_api(up, token, APK.read_bytes(), "application/vnd.android.package-archive")
    print(f"[5/5] released: {rel['html_url']}")
    print(f"      asset:   {asset['browser_download_url']}")


if __name__ == "__main__":
    main()
