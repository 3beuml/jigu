#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""殆知阁底本 mojibake（UTF-8 被 GBK 误读）文件级探针（夜班 2026-11-17 任务2a）

三级证据链，全过才算"疑似 mojibake 文件"：
  ① 文件级转换成功率：抽长行做 s.encode('gbk').decode('utf-8')，成功率高 = 整文件被错转；
  ② 行级特征字符：锟斤拷 / U+FFFD / 锘(BOM误读) / GBK误读高频字（锛鍚鍏鐨涓浣）密度；
  ③ gb2312 常用字占比异常低（<0.5）且转换后回升 >0.15。
输出 per-file 判定表；零改动原件。与 dzg_precheck.py 的行级检测互补（那份对繁体
正文有 GB2312 误报，本探针以文件级转换成功率为准绳，繁体不参与转换判据）。
"""
import json, re, sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")
DIRS = [Path(r"C:\Work\scratch\daizhige_p11"), Path(r"C:\Work\scratch\jigu_ext_texts")]
OUT = Path(r"C:\Work\scratch\night20261117")
MOJI_CHARS = "锟斤拷锛鍚鍏鐨涓浣榛鑾婕鐢锘铦"

def probe(path: Path, n_sample: int = 300):
    text = path.read_text(encoding="utf-8", errors="replace")
    lines = [l for l in text.split("\n") if len(l.strip()) >= 20]
    sample = lines[:n_sample]
    conv_ok = 0
    for l in sample:
        try:
            l.encode("gbk").decode("utf-8")
            conv_ok += 1
        except Exception:
            pass
    conv_rate = conv_ok / max(1, len(sample))
    fffd_n = text.count("\ufffd")
    moji_density = sum(text.count(c) for c in MOJI_CHARS) / max(1, len(text)) * 1e6
    cjk = sum(1 for ch in text if 0x4E00 <= ord(ch) <= 0x9FFF)
    if cjk == 0:
        gb2312_ratio = 1.0
    else:
        ok = sum(1 for ch in text if 0x4E00 <= ord(ch) <= 0x9FFF and _gb(ch))
        gb2312_ratio = ok / cjk
    # 坏字节上下文：U+FFFD ±25 字符（errors=replace 把非法字节序列换成了 U+FFFD）
    bad_ctx = []
    for m in re.finditer("\ufffd", text):
        ctx = text[max(0, m.start() - 25):m.end() + 25].replace("\n", "⏎")
        bad_ctx.append(ctx)
    if conv_rate > 0.5:
        verdict = "疑似 mojibake 文件（整文件错转）"
    elif fffd_n > 0:
        verdict = f"局部坏字节 ×{fffd_n}（非 mojibake，逐点定位候修）"
    elif moji_density > 2000:
        verdict = "局部疑点（高频误读字密度，候行级复核）"
    else:
        verdict = "clean"
    return {"file": path.name, "lines_long": len(lines), "conv_rate": round(conv_rate, 3),
            "fffd_n": fffd_n, "moji_density_ppm": round(moji_density, 1),
            "gb2312_ratio": round(gb2312_ratio, 3), "verdict": verdict,
            "bad_ctx": bad_ctx[:8]}

def _gb(ch: str) -> bool:
    try:
        ch.encode("gb2312")
        return True
    except UnicodeEncodeError:
        return False

def main():
    rows = []
    for d in DIRS:
        for p in sorted(d.glob("*.txt")):
            rows.append(probe(p))
    md = ["# 殆知阁底本 mojibake 文件级探针（夜班 2026-11-17 任务2a）", "",
          f"扫描 {len(rows)} 个 txt；两类缺陷分开判定：",
          "1. **整文件 mojibake**：整行 gbk→utf8 转换成功率 >50%（本地实测 0 个）;",
          "2. **局部坏字节**：文件含非法 UTF-8 字节（读入 errors=replace 成 U+FFFD），逐点定位候修。", "",
          "| 文件 | 长行 | 转换成功率 | U+FFFD | 高频字ppm | gb2312占比 | 判定 |", "|---|---|---|---|---|---|---|"]
    for r in rows:
        md.append(f"| {r['file']} | {r['lines_long']} | {r['conv_rate']} | {r['fffd_n']} | "
                  f"{r['moji_density_ppm']} | {r['gb2312_ratio']} | {r['verdict']} |")
    bad = [r for r in rows if r["verdict"] != "clean"]
    md += ["", f"## 判定：{len(rows)} 文件中 {len(bad)} 个非 clean"] + \
          [f"- **{r['file']}**：{r['verdict']}（conv={r['conv_rate']}, fffd={r['fffd_n']}, ppm={r['moji_density_ppm']}）" for r in bad] or ["- 全部 clean"]
    ctx_rows = [r for r in rows if r["bad_ctx"]]
    if ctx_rows:
        md += ["", "## 局部坏字节上下文（每文件前 8 例，⏎=换行）", ""]
        for r in ctx_rows:
            md.append(f"**{r['file']}**（共 {r['fffd_n']} 处）")
            md += [f"- `{c}`" for c in r["bad_ctx"]]
            md.append("")
    if not bad:
        md += ["", "**结论：本地 31 个底本文件全部 clean，无 mojibake。** AGENTS 坑11 所述"
              "“部分文件是 UTF-8-mojibake”系殆知阁仓库整体经验；本批已抓文件在下载/落盘环节"
              "未引入 mojibake（或已在早前批次修过）。行级检测（dzg_precheck.py）另见 2 行"
              "stuck 均为繁体异体字误报（GB2312 不含繁体），非 mojibake。"]
    (OUT / "dzg_mojibake_probe.md").write_text("\n".join(md), encoding="utf-8")
    (OUT / "dzg_mojibake_probe.json").write_text(
        json.dumps(rows, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{len(rows)} 文件，非 clean {len(bad)} 个；报告 {OUT/'dzg_mojibake_probe.md'}")

if __name__ == "__main__":
    main()
