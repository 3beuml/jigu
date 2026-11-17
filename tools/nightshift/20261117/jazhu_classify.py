#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""殆知阁底本【】语义分类检定（夜班 2026-11-17 任务2b）

背景：AGENTS 坑11"白文本带【夹注】需剥"——但实测这批底本的【】绝大多数不是夹注，
而是**解析器锚点/结构标记**（乐府【曲题】、五代词【词牌】、左传【经】【传】、四库卷首
【臣】提要、逯案〖校勘记〗）。无脑正则剥除会毁掉 D-034~D-038 已入库内容的解析锚。
本脚本对全部含【】文件做全量语义分类，产出"可剥/不可剥/候人工"定性表，
供收录决策拍板；不修整任何原件（铁律）。

分类规则（基于抽样定性）：
  struct   结构标记：单字 ∈ {经,传,注,笺,疏,序,赞} 或"X之属"类四库分类
  tiyao    四库提要体例：【臣】/臣等谨案/总纂官（集中文件头部）
  title    诗题曲题锚：独立成行（行内仅此一块）、前后空行——解析器状态机锚点
  note     注/按语/叶韵：内容含 按|案|一作|音|叶|评|释|谓 且非独立行
  other    其余（人工候）
"""
import json, re, sys
from collections import Counter
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
JZ = re.compile(r"【([^】]*)】")
DIRS = [Path(r"C:\Work\scratch\daizhige_p11"), Path(r"C:\Work\scratch\jigu_ext_texts")]
OUT = Path(r"C:\Work\scratch\night20261117")
STRUCT1 = set("经传注笺疏序赞")
NOTE_HINT = re.compile(r"按|案|一作|一云|音|叶|评|释|注|谓|考|辨|笺|疏")


def classify_line_context(text: str, match: re.Match, line_no: int, lines: list[str]) -> str:
    inner = match.group(1)
    stripped = lines[line_no].strip()
    alone = stripped == f"【{inner}】"
    if len(inner) == 1 and inner in STRUCT1:
        return "struct"
    if inner == "臣" or inner.startswith(("臣等", "臣防", "臣訥", "臣官")):
        return "tiyao"
    if "之属" in inner or re.fullmatch(r"(歌|詩|樂|賦)[上下]?[一二三四五六七八九十]*", inner):
        return "struct"  # 四库/分类标记（杂事之属、歌上）
    if alone:
        return "title"   # 独立成行 = 诗题/词牌/曲题锚
    if len(inner) <= 6 and re.fullmatch(r"[一二三四五六七八九十百首]+", inner):
        return "title"   # 【二首】【十三韵】型题注
    if NOTE_HINT.search(inner):
        return "note"
    return "other"


def scan(path: Path):
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = text.split("\n")
    counter = Counter()
    samples = {}
    for m in JZ.finditer(text):
        line_no = text.count("\n", 0, m.start())
        cls = classify_line_context(text, m, line_no, lines)
        counter[cls] += 1
        if len(samples.get(cls, [])) < 3:
            ctx = text[max(0, m.start() - 15):m.end() + 10].replace("\n", "⏎")
            samples.setdefault(cls, []).append(ctx[-70:])
    return counter, samples


def main():
    files = sorted(p for d in DIRS for p in d.glob("*.txt") if JZ.search(p.read_text(encoding="utf-8", errors="replace")[:2000000]))
    rows = []
    for p in files:
        counter, samples = scan(p)
        total = sum(counter.values())
        anchor_ratio = (counter["struct"] + counter["title"]) / max(1, total)
        note_ratio = (counter["note"] + counter["tiyao"]) / max(1, total)
        if anchor_ratio > 0.5:
            verdict = "不可剥（结构/标题锚，解析器依赖）"
        elif note_ratio > 0.6:
            verdict = "候选剥除（注/按语为主，候体例细析）"
        else:
            verdict = "双义/混杂（候人工细析，禁自动剥）"
        rows.append({"file": p.name, "dir": p.parent.name, "total": total,
                     "cls": dict(counter), "samples": samples, "verdict": verdict})
        print(f"{p.name:26s} 【】{total:>6}  " +
              " ".join(f"{k}={v}" for k, v in counter.most_common()) + f"  → {verdict}")

    md = ["# 殆知阁底本【】语义分类检定（夜班 2026-11-17 任务2b）", "",
          "**结论先行**：本批底本【】以标题锚/结构标记/提要体例为主，"
          "与 AGENTS 坑11'白文【夹注】需剥'的语义不符——剥除决策必须逐文件拍板，禁全局正则。", "",
          "| 文件 | 【】总数 | struct | title | note | other | 定性 |", "|---|---|---|---|---|---|---|"]
    for r in rows:
        c = r["cls"]
        md.append(f"| {r['file']} | {r['total']} | {c.get('struct',0)} | {c.get('title',0)} | "
                  f"{c.get('note',0)} | {c.get('other',0)} | {r['verdict']} |")
    md += ["", "## 实例抽样（每类最多 3 例，⏎=换行）", ""]
    for r in rows:
        md.append(f"**{r['file']}**")
        for k, vs in r["samples"].items():
            for v in vs:
                md.append(f"- {k}: `{v}`")
        md.append("")
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / "jazhu_semantics.md").write_text("\n".join(md), encoding="utf-8")
    (OUT / "jazhu_semantics.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n报告：{OUT / 'jazhu_semantics.md'}")


if __name__ == "__main__":
    main()
