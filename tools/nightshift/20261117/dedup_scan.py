#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""全库跨卡查重扫描（夜班 2026-11-17 任务3，铁律：只读库，零写入，不入库不 bump）

对 jigu-full.db（DATA 27，39.4 万条）做"诗题 + 正文首 12 字"归一化去重扫描：
  归一口径与 verify_j11.py 校验环一致——OpenCC t2s + 异体表 VAR/NORM_MAP + 仅留基本区 CJK。
  两阶段：快筛（title_norm + body12_norm）碰撞组 → 组内全文 norm hash 细分
    - 确凿重复（全文 hash 同）
    - 复沓疑似（题+首 12 字同、全文异：民歌重章叠句/同题异文，逐组留人工判）
  产出：跨卡重复组 / 同卡重复组 / 卡对重叠矩阵 / 复沓疑似 / 空 paragraphs 统计。

用法：python -I dedup_scan.py [--db PATH] [--out DIR]
"""
import argparse, hashlib, json, re, sqlite3, sys
from collections import Counter, defaultdict
from pathlib import Path

from opencc import OpenCC

sys.stdout.reconfigure(encoding="utf-8")
T2S = OpenCC("t2s")

# 与 verify_j11.py 同源异体表（查重口径：字形差异不拆重复）
VAR = {"凢":"凡","蠧":"蠹","搆":"构","蜯":"蚌","鑚":"钻","兎":"兔","逺":"远","舎":"舍",
       "髄":"髓","讐":"仇","徳":"德","鼈":"鳖","衞":"卫","歎":"叹","脩":"修","戲":"戏",
       "鬭":"斗","竒":"奇","碬":"碫","阨":"厄","愼":"慎","歴":"历","畧":"略","廻":"回","灋":"法","髙":"高"}
NORM_MAP = {
    "茍": "苟", "歎": "叹", "脩": "修", "於": "于", "遊": "游", "甯": "宁",
    "鹹": "咸", "卻": "却", "囘": "回", "廻": "回", "羣": "群", "畧": "略",
    "歸": "归", "徧": "遍", "彊": "强", "饑": "饥", "衆": "众", "裏": "里",
    "爲": "为", "發": "发", "覩": "睹", "悅": "悦", "說": "说", "爾": "尔",
    "曏": "向", "嚮": "向", "鄉": "向", "黨": "倘", "暴": "暴", "蚤": "早",
    "倍": "背", "畔": "叛", "錯": "措", "寘": "置", "田": "畋", "辟": "避",
}
_TAB = {**VAR, **NORM_MAP}
JAZHU = re.compile(r"【[^】]*】")  # 库内正文一般无【】；万一有，先剥再比


def norm(text: str) -> str:
    t = JAZHU.sub("", text)
    t = T2S.convert(t)
    t = "".join(_TAB.get(c, c) for c in t)
    return "".join(ch for ch in t if 0x4E00 <= ord(ch) <= 0x9FFF)


def body_of(paragraphs_json: str) -> str:
    try:
        arr = json.loads(paragraphs_json)
        return "\n".join(arr) if isinstance(arr, list) else str(arr)
    except Exception:
        return paragraphs_json


def h8(s: str) -> str:
    return hashlib.md5(s.encode("utf-8")).hexdigest()[:8]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", default=r"C:\Work\repos\Jigu\build-datapack\jigu-full.db")
    ap.add_argument("--out", default=r"C:\Work\scratch\night20261117")
    args = ap.parse_args()
    out_dir = Path(args.out); out_dir.mkdir(parents=True, exist_ok=True)

    conn = sqlite3.connect(f"file:{args.db}?mode=ro", uri=True)
    meta = dict(conn.execute("SELECT key, value FROM meta"))
    print(f"库 data_version={meta.get('data_version')} schema={meta.get('schema_version')} built={meta.get('built_at')}")

    rows = conn.execute(
        "SELECT id, category, type, title, author_id, chapter, paragraphs FROM poems").fetchall()
    authors = dict(conn.execute("SELECT id, name FROM authors"))
    print(f"读库 {len(rows)} 条，开始归一化快筛 …")

    # 快筛：key=(title_norm, body12_norm) -> [entry]
    groups = defaultdict(list)
    empty_body = 0
    for i, (pid, cat, typ, title, aid, chap, para) in enumerate(rows):
        body = body_of(para)
        if not body.strip():
            empty_body += 1
        tn = norm(title)
        b12 = norm(body[:200])[:12]  # 快筛只转前 200 字（全文 hash 细分在碰撞组内做）
        if not tn and not b12:
            continue
        groups[(tn, b12)].append({"id": pid, "cat": cat, "type": typ, "title": title,
                                  "author": authors.get(aid, ""), "chapter": chap})

    dup_groups = {k: v for k, v in groups.items() if len(v) > 1}
    print(f"快筛碰撞组 {len(dup_groups)}（涉及 {sum(len(v) for v in dup_groups.values())} 条），"
          f"空正文 {empty_body} 条；进入全文 hash 细分 …")

    # 组内全文 hash 细分（只对碰撞组回库取全文，二次转换量小）
    confirmed = []   # 组内存在全文 hash 相同的对
    chongfu = []     # 题+首12 同但全文均异（复沓/异文疑似）
    para_cache = {}
    for key, members in dup_groups.items():
        ids = [m["id"] for m in members]
        ph = {}
        for pid in ids:
            if pid not in para_cache:
                p = conn.execute("SELECT paragraphs FROM poems WHERE id=?", (pid,)).fetchone()[0]
                para_cache[pid] = h8(norm(body_of(p)))
            ph[pid] = para_cache[pid]
        if len(set(ph.values())) < len(ph):
            confirmed.append((key, members, ph))
        else:
            chongfu.append((key, members))

    def cat_matrix(items):
        pairs = Counter()
        for key, members, *_ in items:
            cc = Counter(m["cat"] for m in members)
            cats = sorted(cc)
            for i in range(len(cats)):
                for j in range(i + 1, len(cats)):
                    pairs[(cats[i], cats[j])] += min(cc[cats[i]], cc[cats[j]])
        return pairs

    cross = [x for x in confirmed if len({m["cat"] for m in x[1]}) > 1]
    within = [x for x in confirmed if len({m["cat"] for m in x[1]}) == 1]
    cross_pairs = cat_matrix(cross)
    within_cat = Counter()
    for key, members, ph in within:
        within_cat[members[0]["cat"]] += 1
    chongfu_cross = sum(1 for k, ms in chongfu if len({m["cat"] for m in ms}) > 1)

    report = {
        "db": args.db, "data_version": meta.get("data_version"), "built_at": meta.get("built_at"),
        "total_rows": len(rows), "empty_body": empty_body,
        "fast_collide_groups": len(dup_groups),
        "confirmed_groups": len(confirmed), "confirmed_rows": sum(len(m) for _, m, _ in confirmed),
        "cross_groups": len(cross), "within_groups": len(within),
        "chongfu_groups": len(chongfu), "chongfu_cross_groups": chongfu_cross,
        "meta_dups_ingest": json.loads(meta["dups"]) if "dups" in meta else {},
        "cross_pairs": [{"a": a, "b": b, "n": n} for (a, b), n in cross_pairs.most_common()],
        "groups": [
            {"title": k[0][:40], "body12": k[1], "members": m,
             "hashes": sorted(set(ph.values())) if len(x) == 3 else []}
            for x in confirmed for k, m in [x[:2]] for ph in [x[2] if len(x) == 3 else {}]],
        "chongfu_detail": [{"title": k[0][:40], "body12": k[1],
                            "members": [{kk: m[kk] for kk in ("id", "cat", "title", "author", "chapter")} for m in ms]}
                           for k, ms in chongfu[:400]],
    }
    (out_dir / "dedup_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")

    def row(m):
        ch = f" [{m['chapter']}]" if m["chapter"] else ""
        return f"{m['id']}｜{m['cat']}｜{m['title'][:30]}｜{m['author'][:10]}{ch}"

    md = ["# 全库跨卡查重报告（夜班 2026-11-17，任务3）", "",
          f"- 库：jigu-full.db，DATA {meta.get('data_version')}（built {meta.get('built_at')}），{len(rows)} 条，"
          f"只读扫描零写入。", 
          f"- 归一口径：verify_j11 同源（t2s+异体表+去标点，仅留基本区 CJK）。",
          f"- 快筛键：norm(题) + norm(正文首 12 字)；碰撞组 {len(dup_groups)} → 全文 hash 细分。",
          f"- **确凿重复组 {len(confirmed)}**（组内存在全文归一后全同的条目对，共 "
          f"{sum(len(m) for _, m, _ in confirmed)} 条）＝ 跨卡 {len(cross)} 组 + 同卡 {len(within)} 组。",
          f"- **复沓疑似 {len(chongfu)} 组**（题+首 12 字同、全文异：重章叠句/同题异文，候人工）。",
          f"- 空 paragraphs：{empty_body} 条。", "",
          f"- 对照：入库时 UNIQUE(category,hash) 已吞重复（meta.dups）= {report['meta_dups_ingest']}；"
          f"本扫描是语义口径（归一化后），两者不等价。", ""]

    md += ["## 一、跨卡重叠矩阵（确凿重复的卡对，条目对下界）", "",
           "| 卡A | 卡B | 重叠条数(≥) |", "|---|---|---|"]
    for (a, b), n in cross_pairs.most_common():
        md.append(f"| {a} | {b} | {n} |")
    if not cross_pairs:
        md.append("|（无）| | |")

    def dump_groups(lst, title, cap):
        out = ["", f"## {title}", ""]
        lst_sorted = sorted(lst, key=lambda x: -len(x[1]))
        for k, members, *rest in lst_sorted[:cap]:
            hs = f"（hash {'/'.join(sorted(set(rest[0].values())))}）" if rest else ""
            out.append(f"### {k[0][:30] or '(无题)'} ｜首12「{k[1]}」{hs}")
            for m in members:
                out.append(f"- {row(m)}")
        if len(lst_sorted) > cap:
            out.append(f"\n（仅列前 {cap} 组，全量见 dedup_report.json）")
        return out

    md += dump_groups(cross, "二、跨卡确凿重复组（用户在两张卡会看到同文）", 120)
    md += dump_groups(within, "三、同卡确凿重复组（同一卡列表出现两次）", 80)
    md += dump_groups([(k, ms) for k, ms in chongfu], "四、复沓疑似（题+首12 同、全文异）", 40)

    (out_dir / "dedup_report.md").write_text("\n".join(md), encoding="utf-8")
    print(f"\n确凿重复组 {len(confirmed)}（跨卡 {len(cross)} / 同卡 {len(within)}），复沓疑似 {len(chongfu)} 组")
    print(f"报告：{out_dir / 'dedup_report.md'}")


if __name__ == "__main__":
    main()
