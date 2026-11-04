#!/usr/bin/env python3
"""字体覆盖面自查：App 会展示的每个字符是否都在子集字体里。

比对来源（全部 App 实际可展示文本）：
  1. 建库 DB 全部表的全部 TEXT 列（不限于 subset_font 采集的四处）
  2. Kotlin 源码字符串字面量 + res/values*/strings.xml
  3. assets/data/calendar.json
  4. subset_font.py 的 UI_TEXT
用法：
  python tools/fonts/check_font_coverage.py [--db build-datapack/jigu-full.db] [字体路径]
输出仍缺的字符及样例出处；退出码 1 = 有缺字。
"""

import argparse
import json
import re
import sqlite3
import sys
from collections import defaultdict
from pathlib import Path

from fontTools.ttLib import TTFont

REPO = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(REPO / "tools/fonts"))
from subset_font import UI_TEXT  # noqa: E402  复用同一份界面文案字符集

DEFAULT_DB = REPO / "build-datapack/jigu-full.db"
DEFAULT_FONT = REPO / "app/src/main/res/font/wenkai_regular.ttf"

ESC = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "'": "'", "\\": "\\"}


def add_text(chars: set, loc: str, text: str, samples: dict) -> None:
    for ch in text:
        if 0x20 <= ord(ch):
            chars.add(ch)
            if ch not in samples and ord(ch) > 0x2000:  # 只为非常用字记样例，减噪
                samples[ch] = f"{loc}: …{text[max(0, text.find(ch)-6):text.find(ch)+7].strip()}…"


def collect_db(db_path: Path, chars: set, samples: dict) -> None:
    db = sqlite3.connect(f"file:{db_path.as_posix()}?mode=ro", uri=True)
    tables = [
        r[0]
        for r in db.execute("SELECT name FROM sqlite_master WHERE type='table'")
        if not r[0].startswith(("sqlite_",)) and "fts" not in r[0]
    ]
    for t in tables:
        cols = [
            (r[1], r[2].upper())
            for r in db.execute(f"PRAGMA table_info({t})")
        ]
        text_cols = [c for c, ty in cols if ty in ("TEXT",)]
        if not text_cols:
            continue
        sel = ", ".join(f"IFNULL({c},'')" for c in text_cols)
        n = 0
        for row in db.execute(f"SELECT {sel} FROM {t}"):
            for col, val in zip(text_cols, row):
                if val:
                    n += 1
                    add_text(chars, f"{t}.{col}", val, samples)
        print(f"  db:{t} ({n} 段文本)")
    db.close()


def collect_kotlin(chars: set, samples: dict) -> None:
    root = REPO / "app/src/main"
    pat = re.compile(r'"((?:\\.|[^"\\])*)"')
    n = 0
    for sub in ("java", "res"):
        for f in (root / sub).rglob("*"):
            if f.suffix not in (".kt", ".xml") or "font" in f.parts:
                continue
            try:
                src = f.read_text(encoding="utf-8")
            except UnicodeDecodeError:
                continue
            if sub == "res" and f.name != "strings.xml":
                continue
            if sub == "res":
                # XML 文本节点与属性值
                for m in re.finditer(r">(.*?)<", src, re.S):
                    add_text(chars, f"res:{f.name}", m.group(1), samples)
                n += 1
            else:
                for m in pat.finditer(src):
                    raw = m.group(1)
                    if not raw:
                        continue
                    try:
                        decoded = re.sub(
                            r"\\(u[0-9a-fA-F]{4}|.)",
                            lambda mm: (
                                chr(int(mm.group(1)[1:], 16))
                                if mm.group(1).startswith("u")
                                else ESC.get(mm.group(1), mm.group(1))
                            ),
                            raw,
                        )
                    except Exception:
                        decoded = raw
                    add_text(chars, f"kt:{f.name}", decoded, samples)
                    n += 1
    print(f"  kotlin/xml: {n} 段文本")


def collect_calendar(chars: set, samples: dict) -> None:
    p = REPO / "app/src/main/assets/data/calendar.json"
    if p.exists():
        add_text(chars, "calendar.json", p.read_text(encoding="utf-8"), samples)
        print("  calendar.json")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DEFAULT_DB)
    ap.add_argument("font", nargs="?", type=Path, default=DEFAULT_FONT)
    args = ap.parse_args()

    chars: set = set(UI_TEXT)
    samples: dict = {}
    print("采集文本来源:")
    collect_db(args.db, chars, samples)
    collect_kotlin(chars, samples)
    collect_calendar(chars, samples)
    chars.update(chr(c) for c in range(0x20, 0x7F))
    chars.update("，。！？；：、「」『』（）《》〈〉“”‘’—…·～【】〖〗％‰°℃")
    chars.update("０１２３４５６７８９")
    print(f"字符集规模: {len(chars)}")

    font = TTFont(str(args.font), lazy=True)
    cmap = font.getBestCmap()
    missing = sorted(ch for ch in chars if ord(ch) not in cmap)
    if not missing:
        print("✅ 覆盖完整：可展示文本全部字符均在字体中")
        return 0
    print(f"❌ 缺 {len(missing)} 个字符:")
    for ch in missing:
        cp = ord(ch)
        sample = samples.get(ch, "")
        block = (
            "BMP" if cp <= 0xFFFF else "SIP" if cp <= 0x2FFFF else "Ext"
        )
        print(f"  U+{cp:05X} {block} '{ch}'  {sample}")
    return 1


if __name__ == "__main__":
    sys.exit(main())
