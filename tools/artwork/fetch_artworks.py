#!/usr/bin/env python3
"""节气配画抓取管线：克利夫兰艺术博物馆开放数据 API（CC0）。

按「节气 -> 英文检索词策展表」在中文艺术部检索水墨画，确定性选取（跳过已用），
下载后居中裁 3:4、压成 WebP，产出：
  app/src/main/assets/art/term_{序号}_{拼音}.webp
  app/src/main/assets/art/manifest.json（含署名，App「赏画」与「关于」页展示）

幂等：已存在的 webp 跳过。重跑加 --force。
"""

import argparse
import json
import sys
import urllib.request
from pathlib import Path

from PIL import Image

API = "https://openaccess-api.clevelandart.org/api/artworks/"
UA = {"User-Agent": "JiguApp/0.1 (data pipeline; contact via github 3beuml/jigu)"}
SIZE = (780, 1040)  # 3:4

TERMS = [
    ("lichun", "立春", ["plum", "spring"]),
    ("yushui", "雨水", ["willow", "rain"]),
    ("jingzhe", "惊蛰", ["spring landscape", "blossom"]),
    ("chunfen", "春分", ["bird and flower", "bird"]),
    ("qingming", "清明", ["spring river", "willow"]),
    ("guyu", "谷雨", ["tea", "peony"]),
    ("lixia", "立夏", ["lotus leaf", "summer"]),
    ("xiaoman", "小满", ["bamboo", "field"]),
    ("mangzhong", "芒种", ["rice", "landscape"]),
    ("xiazhi", "夏至", ["lotus"]),
    ("xiaoshu", "小暑", ["pavilion", "fan"]),
    ("dashu", "大暑", ["bamboo grove", "bamboo"]),
    ("liqiu", "立秋", ["autumn"]),
    ("chushu", "处暑", ["boat", "river"]),
    ("bailu", "白露", ["goose", "wild geese"]),
    ("qiufen", "秋分", ["moon"]),
    ("hanlu", "寒露", ["chrysanthemum"]),
    ("shuangjiang", "霜降", ["maple", "autumn leaves"]),
    ("lidong", "立冬", ["winter"]),
    ("xiaoxue", "小雪", ["snow"]),
    ("daxue", "大雪", ["snow mountain", "winter landscape"]),
    ("dongzhi", "冬至", ["winter", "bare trees"]),
    ("xiaohan", "小寒", ["plum blossom", "plum"]),
    ("dahan", "大寒", ["ice", "winter landscape"]),
]

FALLBACK_KEYWORDS = ["landscape", "mountain"]
PER_TERM = 4  # 每节气目标幅数：节气内按 poem_id 轮换，破"同幅感"


def get_json(url: str):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


def download(url: str, dst: Path) -> bool:
    try:
        req = urllib.request.Request(url, headers=UA)
        with urllib.request.urlopen(req, timeout=30) as r:
            data = r.read()
        # 中心裁 3:4
        img = Image.open(__import__("io").BytesIO(data)).convert("RGB")
        w, h = img.size
        target = SIZE[0] / SIZE[1]
        if w / h > target:
            nw = int(h * target)
            box = ((w - nw) // 2, 0, (w + nw) // 2, h)
        else:
            nh = int(w / target)
            box = (0, (h - nh) // 2, w, (h + nh) // 2)
        img = img.crop(box).resize(SIZE, Image.LANCZOS)
        img.save(dst, "WEBP", quality=82)
        return True
    except Exception as e:
        print(f"    下载失败 {url[:60]}: {e}")
        return False


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    ap.add_argument("--force", action="store_true")
    args = ap.parse_args()

    out_dir = args.repo / "app/src/main/assets/art"
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = out_dir / "manifest.json"

    manifest = []
    if args.force:
        for f in out_dir.glob("term_*.webp"):
            f.unlink()  # 强制模式清空旧画池，命名统一为带序号的新格式
    if manifest_path.exists() and not args.force:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    used_ids = {m["object_id"] for m in manifest}
    done_terms = {m["term"] for m in manifest}

    for idx, (pinyin, cn, keywords) in enumerate(TERMS, 1):
        if cn in done_terms and not args.force:
            continue
        got = 0
        for kw in keywords + FALLBACK_KEYWORDS:  # 每个检索词贡献一整池候选，逐个取
            if got >= PER_TERM:
                break
            try:
                d = get_json(
                    f"{API}?q={urllib.request.quote(kw)}&department=Chinese%20Art"
                    "&has_image=1&cc0=1&limit=60"
                )
            except Exception as e:
                print(f"[{cn}] 检索失败（{kw}）: {e}")
                continue
            for a in d.get("data", []):
                if got >= PER_TERM:
                    break
                if a["id"] in used_ids:
                    continue
                if "painting" not in str(a.get("type", "")).lower():
                    continue  # 只要绘画，滤掉青铜/陶瓷等器物
                url = (a.get("images") or {}).get("web", {}).get("url", "")
                if not url:
                    continue
                fname = f"term_{idx:02d}_{pinyin}_{got + 1}.webp"
                if not download(url, out_dir / fname):
                    continue
                creators = a.get("creators") or [{}]
                manifest.append({
                    "term": cn,
                    "pinyin": pinyin,
                    "file": fname,
                    "title": a.get("title", ""),
                    "artist": creators[0].get("description", "").strip() or "佚名",
                    "date": a.get("creation_date", ""),
                    "object_id": a["id"],
                    "credit": "The Cleveland Museum of Art, CC0 1.0",
                })
                used_ids.add(a["id"])
                got += 1
                print(f"[{cn} {got}/{PER_TERM}] {a.get('title','')} · {a.get('creation_date','')} <- {kw}")
        if got == 0:
            print(f"[{cn}] 所有检索词均无可用画，跳过")

    manifest.sort(key=lambda m: (TERMS.index(next(t for t in TERMS if t[1] == m["term"])), m["file"]))
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8",
    )
    print(f"== 完成 {len(manifest)}/24，manifest -> {manifest_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
