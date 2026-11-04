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
import re
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

# 汉译对照（D-019，草译待校）：file 词根 -> (title_zh, artist_zh)。
# artist_zh 含时代（「清 · 黎简」形）；不确定的汉字名一律留 None 回退英文原文，勿猜。
# 待校名单：殷宏(5) 史瑞(10,62) 弘旿(31) 夏葵(33)。
ZH_META = {
    "term_01_lichun_1": ("墨梅", None),
    "term_01_lichun_2": ("梅花牡丹", "清 · 高凤翰"),
    "term_01_lichun_3": ("梅花", "清 · 汪士慎"),
    "term_01_lichun_4": ("月下梅影", "元 · 王冕"),
    "term_02_yushui_1": ("柳鹊图", "南宋 · 牧谿"),
    "term_02_yushui_2": ("春柳集禽图", "明 · 殷宏"),
    "term_02_yushui_3": ("杂画册·其六", "清 · 樊圻"),
    "term_02_yushui_4": ("杨柳图", "宋 · 佚名"),
    "term_03_jingzhe_1": ("春山图", "清 · 髡残"),
    "term_03_jingzhe_2": ("迎春图", "明 · 吴彬"),
    "term_03_jingzhe_3": ("桃源图", "明 · 史瑞"),
    "term_03_jingzhe_4": ("拟各家山水册·春耕", "清 · 查士标"),
    "term_04_chunfen_1": ("折枝花鸟", "宋 · 佚名"),
    "term_04_chunfen_2": ("百合蛱蝶", None),
    "term_04_chunfen_3": ("鹑雀秋色图", "元 · 王渊"),
    "term_04_chunfen_4": ("百鸟三友图", "明 · 边文进"),
    "term_05_qingming_1": ("江南春霭图", "清 · 石涛"),
    "term_05_qingming_2": ("四季花卉图", "明 · 王穀祥"),
    "term_05_qingming_3": ("疏林远岫图", "明 · 李流芳"),
    "term_05_qingming_4": ("山水图", "清 · 翟大坤"),
    "term_06_guyu_1": ("赵孟頫写经换茶图", "明 · 仇英"),
    "term_06_guyu_2": ("卢仝烹茶图", "清 · 闵贞"),
    "term_06_guyu_3": ("春日洗药图", "清 · 禹之鼎"),
    "term_06_guyu_4": ("四季山水册", "清 · 查士标"),
    "term_07_lixia_1": ("拟古册·莲石图", "明 · 陈洪绶"),
    "term_07_lixia_2": ("百子嬉春图", "宋 · 苏汉臣"),
    "term_07_lixia_3": ("燕荷图", "南宋 · 牧谿"),
    "term_07_lixia_4": ("拟古图册", "明 · 陈洪绶"),
    "term_08_xiaoman_1": ("风竹图", "元 · 雪窗"),
    "term_08_xiaoman_2": ("竹石幽兰图", "元 · 赵孟頫"),
    "term_08_xiaoman_3": ("墨竹图", "元 · 佚名"),
    "term_08_xiaoman_4": ("雪竹图", "清 · 弘旿"),
    "term_09_mangzhong_1": ("杂画册·其七", "清 · 樊圻"),
    "term_09_mangzhong_2": ("婴戏图", "明 · 夏葵"),
    "term_09_mangzhong_3": ("竹林孤鹤图", None),
    "term_09_mangzhong_4": ("春晚归农图", None),
    "term_10_xiazhi_1": ("妙法莲华经扉画", "宋 · 佚名"),
    "term_10_xiazhi_2": ("普贤菩萨图", "宋 · 佚名"),
    "term_10_xiazhi_3": ("蛮王礼佛图", "宋 · 赵光辅"),
    "term_10_xiazhi_4": ("释迦三尊图", "元 · 佚名"),
    "term_11_xiaoshu_1": ("兰亭修禊图", "清 · 樊沂"),
    "term_11_xiaoshu_2": ("天台石桥图", "清 · 戴熙"),
    "term_11_xiaoshu_3": ("草亭诗意图", "元 · 吴镇"),
    "term_11_xiaoshu_4": ("虎丘十二景图", "明 · 沈周"),
    "term_12_dashu_1": ("杂画册·其二", "清 · 樊圻"),
    "term_12_dashu_2": ("烟林听秋图", "清 · 张风"),
    "term_12_dashu_3": ("茅屋清谈图", "元 · 佚名"),
    "term_12_dashu_4": ("竹林七贤图", "清 · 佚名"),
    "term_13_liqiu_1": ("秋窗对话图", "清 · 华喦"),
    "term_13_liqiu_2": ("秋日江山图", "明 · 董其昌"),
    "term_13_liqiu_3": ("秋猎图", "南宋 · 陈居中"),
    "term_13_liqiu_4": ("秋林烟舍图", "南宋 · 李安忠"),
    "term_14_chushu_1": ("秦淮忆旧图", "清 · 石涛"),
    "term_14_chushu_2": ("西湖泊舟图", "南宋 · 佚名"),
    "term_14_chushu_3": ("杂画册·其一", "清 · 樊圻"),
    "term_14_chushu_4": ("墨戏山水图", "元 · 方从义"),
    "term_15_bailu_1": ("溪山飞雁图", "南宋 · 马麟"),
    "term_15_bailu_2": ("书画合璧册·其一", "清 · 边寿民"),
    "term_15_bailu_3": ("狩猎图", "明清 · 佚名"),
    "term_15_bailu_4": ("书画合璧册·其二", "清 · 边寿民"),
    "term_16_qiufen_1": ("拟各家山水册·待月", "清 · 查士标"),
    "term_16_qiufen_2": ("对月图", "南宋 · 马远"),
    "term_16_qiufen_3": ("中秋待月图", "明 · 史瑞"),
    "term_16_qiufen_4": ("听琴图", "明 · 唐寅"),
    "term_17_hanlu_1": ("拟古册·菊石图", "明 · 陈洪绶"),
    "term_17_hanlu_2": ("菘菊图", "明 · 陶成"),
    "term_17_hanlu_3": ("拟古册·菊花图", "明 · 陈洪绶"),
    "term_17_hanlu_4": ("拟王渊菊桂图", "明 · 沈周"),
    "term_18_shuangjiang_1": ("杂画册·其八", "清 · 樊圻"),
    "term_18_shuangjiang_2": ("秋林孤色", "明 · 王建章"),
    "term_18_shuangjiang_3": ("白云红树图", "清 · 黎简"),
    "term_18_shuangjiang_4": ("拟王蒙太白山图", "明 · 杜琼"),
    "term_19_lidong_1": ("四季山水册·其八", "清 · 萧云从"),
    "term_19_lidong_2": ("四季山水册·其七", "清 · 萧云从"),
    "term_19_lidong_3": ("四季竹图·冬", "元 · 佚名"),
    "term_19_lidong_4": ("杂画册·其九", "清 · 樊圻"),
    "term_20_xiaoxue_1": ("雪染千山图", "清 · 法若真"),
    "term_20_xiaoxue_2": ("出山释迦图", "宋 · 佚名"),
    "term_20_xiaoxue_3": ("寒溪古木图", "明 · 文徵明"),
    "term_20_xiaoxue_4": ("九龙潭图", "清 · 梅清"),
    "term_21_daxue_1": ("五叠泉图", "明 · 陈洪绶"),
    "term_21_daxue_2": ("寒林群鸟图", None),
    "term_21_daxue_3": ("拟各家山水册·雪景", "清 · 查士标"),
    "term_21_daxue_4": ("溪山幽居图", "明 · 陆治"),
    "term_22_dongzhi_1": ("四季竹图·夏冬", "元 · 佚名"),
    "term_22_dongzhi_2": ("冬景图", "宋 · 佚名"),
    "term_22_dongzhi_3": ("三清图", "清 · 张熊"),
    "term_22_dongzhi_4": ("书画合璧册·其三", "清 · 边寿民"),
    "term_23_xiaohan_1": ("林逋月下吟图", "明 · 杜堇"),
    "term_23_xiaohan_2": ("四季山水册·其一", "清 · 萧云从"),
    "term_23_xiaohan_3": ("月梅图", "元 · 颜辉"),
    "term_23_xiaohan_4": ("花鸟册·梅禽图", "清 · 张若霭"),
    "term_24_dahan_1": ("拟古山水册", "清 · 梅清"),
    "term_24_dahan_2": ("拟郭忠恕山水图", "清 · 八大山人"),
    "term_24_dahan_3": ("秋山归樵图", "宋 · 佚名"),
    "term_24_dahan_4": ("牧牛图", None),
}


def _apply_zh(manifest: list) -> int:
    """把汉译合入 manifest（title_zh / artist_zh），返回覆盖条数"""
    hit = 0
    for a in manifest:
        zh = ZH_META.get(str(a.get("file", "")).replace(".webp", ""))
        if zh:
            t, ar = zh
            if t:
                a["title_zh"] = t
            if ar:
                a["artist_zh"] = ar
            hit += 1
    return hit


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
    ap.add_argument("--translate", action="store_true",
                    help="不联网：仅把 ZH_META 汉译合入现有 manifest")
    ap.add_argument("--expand", type=int, default=0, metavar="TARGET",
                    help="批8：扩图库到 TARGET 幅（单页拉全 561 幅 CC0 中国画，分类打标，题材配画池）")
    args = ap.parse_args()

    if args.expand:
        return expand(args.repo, args.expand)

    out_dir = args.repo / "app/src/main/assets/art"
    out_dir.mkdir(parents=True, exist_ok=True)
    manifest_path = out_dir / "manifest.json"

    if args.translate:
        if not manifest_path.exists():
            print("manifest 不存在，先抓取一次")
            return 1
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        hit = _apply_zh(manifest)
        manifest_path.write_text(
            json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8",
        )
        print(f"== 汉译合入 {hit}/{len(manifest)} -> {manifest_path}")
        return 0

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
    _apply_zh(manifest)
    manifest_path.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8",
    )
    print(f"== 完成 {len(manifest)}/24，manifest -> {manifest_path}")
    return 0




# ============ 批8（D-027）：题材配画池——单页拉全 + 分类打标 ============

SELECT_FIELDS = ("id,title,title_in_original_language,creators,creation_date,"
                 "technique,collection,images,share_license_status")

# 分类正则：优先级 figure > birdflower > animal > landscape（词最泛放最后防误吞）
_PATTERNS = {
    "figure": ("人物", r"portrait|figure|lady|scholar|emperor|consort|children|ancestor|gathering|"
                        r"immortal|arhat|luohan|bodhisattva|buddha|guanyin|monk|sage|banquet|"
                        r"bodhidharma|amitabha|manjushri|samantabhadra|zhong kui|tieguai|patriarch"),
    "birdflower": ("花鸟", r"bird|flower|plum|bamboo|lotus|peony|chrysanth|orchid|magpie|goose|"
                          r"quail|eagle|crane|butterfly|insect|blossom|willow|narcissus|camellia|wisteria"),
    "animal": ("动物", r"horse|ox|buffalo|dragon|tiger|fish|monkey|deer|cat|sheep|donkey|"
                      r"elephant|rabbit|turtle|shrimp"),
    "landscape": ("山水", r"landscape|mountain|river|stream|pavilion|boat|bridge|snow|moon|"
                         r"autumn|winter|waterfall|hermit|travelers|fisherman|cliff|valley|mist|"
                         r"lake|shore|retreat"),
}
# 目标配额（总数 ≥300；现有 96 幅计入）
QUOTA = {"landscape": 115, "birdflower": 85, "figure": 65, "animal": 45, "other": 12}


def classify(a: dict) -> str:
    blob = " ".join([
        str(a.get("title", "")),
        str(a.get("title_in_original_language") or ""),
        str(a.get("technique") or ""),
        *[str(c.get("description", "")) for c in (a.get("creators") or [])],
    ]).lower()
    for cat, (_, pat) in _PATTERNS.items():
        if re.search(pat, blob):
            return cat
    return "other"


def expand(repo: Path, target: int) -> int:
    import io
    import re
    out_dir = repo / "app/src/main/assets/art"
    manifest_path = out_dir / "manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else []
    used_ids = {m.get("object_id") for m in manifest}

    # 1) 存量 96 幅补分类 + title_zh（中文原题直填）
    backfill = 0
    for m in manifest:
        if not m.get("category"):
            m["category"] = classify({"title": m.get("title", "")})
            backfill += 1
        if not m.get("title_zh"):
            pass  # 汉译仍走 ZH_META/人工，原题补译不覆盖已人工校的
    print(f"  存量补分类 {backfill}")

    # 2) 单页拉全量池（561 幅 CC0 中国绘画；limit=1000 一次到位，勿 skip 翻页——排序漂移）
    print("  拉取克利夫兰全量池…")
    d = get_json(f"{API}?department=Chinese%20Art&type=Painting&has_image=1&cc0=1"
                 f"&limit=1000&select={SELECT_FIELDS}")
    pool = [a for a in d.get("data", [])
            if a.get("share_license_status") == "CC0"
            and (a.get("images") or {}).get("web", {}).get("url")
            and a["id"] not in used_ids]
    print(f"  候选 {len(pool)}（库内未用）")

    # 3) 分类 + 分桶
    buckets: dict[str, list] = {}
    for a in pool:
        buckets.setdefault(classify(a), []).append(a)
    for cat, arr in buckets.items():
        # 确定性排序：有中文原题者优先（省汉译），再按 id
        arr.sort(key=lambda x: (0 if x.get("title_in_original_language") else 1, x["id"]))
    cur = {}
    for m in manifest:
        c = m.get("category", "other")
        cur[c] = cur.get(c, 0) + 1

    # 4) 按配额补齐到 target
    total = len(manifest)
    need_total = max(0, target - total)
    seq = {cat: 0 for cat in QUOTA}
    for m in manifest:
        fn = str(m.get("file", "")).replace(".webp", "")
        if fn.startswith("cat_"):
            parts = fn.split("_")
            if len(parts) >= 2:
                seq[parts[1]] = max(seq[parts[1]], int(parts[-1]))
    added = 0
    for cat in ("landscape", "birdflower", "figure", "animal", "other"):
        goal = QUOTA.get(cat, 0)
        want = min(len(buckets.get(cat, [])), max(0, goal - cur.get(cat, 0)))
        for a in buckets.get(cat, [])[:want]:
            if added >= need_total and total + added >= target:
                break
            seq[cat] += 1
            fname = f"cat_{cat}_{seq[cat]}.webp"
            url = a["images"]["web"]["url"]
            if not download(url, out_dir / fname):
                seq[cat] -= 1
                continue
            cr = (a.get("creators") or [{}])[0]
            manifest.append({
                "term": "cat",
                "pinyin": cat,
                "file": fname,
                "title": a.get("title", ""),
                "artist": cr.get("description", "") or "佚名",
                "date": a.get("creation_date", ""),
                "object_id": a["id"],
                "credit": "The Cleveland Museum of Art (CC0)",
                "category": cat,
                "title_zh": a.get("title_in_original_language") or "",
            })
            added += 1
            total += 1
        if total >= target:
            break
    manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=1), encoding="utf-8")
    from collections import Counter
    dist = Counter(m.get("category", "other") for m in manifest)
    print(f"== 扩池 +{added}，总计 {len(manifest)} 幅 | 分类分布 {dict(dist)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
