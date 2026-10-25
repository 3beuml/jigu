#!/usr/bin/env python3
"""发版脚本：创建 GitHub Release 并上传 release APK。

前置：
  1. app/build/outputs/apk/release/app-release.apk 已构建（assembleRelease）
  2. versionCode/versionName 已按 D-009 规则递增
  3. 令牌在 C:\\Work\\.jigu_token.txt（单仓库 Contents 权限，用后吊销）
  4. 已打 git tag（如 v0.1.0）

用法：python tools/release/upload_release.py --tag v0.1.0 --apk app/build/outputs/apk/release/app-release.apk
注意：Release 正文留空，由用户自行撰写（D-009 惯例）。
"""

import argparse
import json
import sys
import urllib.request
from pathlib import Path

REPO = "3beuml/jigu"


def api(url: str, token: str, method: str = "GET", data: dict | None = None):
    req = urllib.request.Request(url, method=method, headers={
        "Authorization": f"token {token}",
        "User-Agent": "jigu-release",
        "Accept": "application/vnd.github+json",
    }, data=json.dumps(data).encode() if data else None)
    with urllib.request.urlopen(req, timeout=60) as r:
        body = r.read()
    return json.loads(body) if body else {}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tag", required=True)
    ap.add_argument("--apk", required=True, type=Path)
    ap.add_argument("--token-file", type=Path, default=Path("C:/Work/.jigu_token.txt"))
    args = ap.parse_args()

    token = args.token_file.read_text().strip()
    apk = args.apk
    if not apk.exists():
        print(f"APK 不存在: {apk}", file=sys.stderr)
        return 1

    # 1) 创建 release（草稿态，正文留空由用户写）
    rel = api(f"https://api.github.com/repos/{REPO}/releases", token, "POST", {
        "tag_name": args.tag,
        "name": args.tag,
        "body": "",
        "draft": True,
        "prerelease": args.tag.startswith("v0."),
    })
    print(f"release #{rel['id']} 创建（draft）")

    # 2) 上传 APK
    upload_url = rel["upload_url"].split("{")[0] + f"?name={apk.name}"
    req = urllib.request.Request(upload_url, method="POST", headers={
        "Authorization": f"token {token}",
        "Content-Type": "application/vnd.android.package-archive",
        "Content-Length": str(apk.stat().st_size),
    }, data=apk.read_bytes())
    with urllib.request.urlopen(req, timeout=600) as r:
        print("上传完成:", json.load(r).get("browser_download_url"))

    print("提醒：发布正文请自行撰写；令牌用后到 GitHub 吊销。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
