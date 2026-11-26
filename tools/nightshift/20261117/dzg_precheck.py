#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""殆知阁底本预修整（夜班 2026-11-17 任务2，D-033/D-034 已知三类脏问题的批处理）

只做检测+修整副本落盘，不改任何原始底本、不入库、不 bump 版本：
  ① UTF-8-mojibake 反向还原（GBK 误读）：s.encode('gbk').decode('utf-8')，
     含 PUA/生僻字逐段兜底（不可编码字符原样保留，段内转换失败则保段）；
  ② 目录繁简混杂与同书异名归一 —— 产出 dzg_alias_map.json（观察事实+AGENTS 已知坑）；
  ③ 白文【夹注】统计与剥除预览（乐府诗集类【】双义文件只标旗不自动剥）。

用法：python -I dzg_precheck.py [--dirs a,b,...] [--out DIR] [--fix]
默认扫描 C:/Work/scratch/daizhige_p11 与 C:/Work/scratch/jigu_ext_texts。
报告 JSON+MD 落 --out（默认 C:/Work/scratch/night20261117/）。
"""
import argparse, json, re, sys
from pathlib import Path

sys.stdout.reconfigure(encoding="utf-8")

DEFAULT_DIRS = [r"C:\Work\scratch\daizhige_p11", r"\Work\scratch\jigu_ext_texts".replace("\\W", "C:\\W")]
DEFAULT_OUT = Path(r"C:\Work\scratch\night20261117")

JAZHU = re.compile(r"【[^】]*】")

# AGENTS/D-033/D-034 已知坑 + fetch_all.py 观察事实 → 未来抓取脚本一律走这张表
ALIAS_MAP = {
    "商君书": {"aliases": ["商子"], "dirs": ["子藏/法家"], "evidence": "AGENTS 本机坑11（商君书=商子）+ fetch_all.py 实测"},
    "春秋左传": {"aliases": [], "dirs": ["儒藏/春秋"], "evidence": "AGENTS 本机坑11（在儒藏/春秋）+ fetch_all.py 实测"},
    "晏子春秋": {"aliases": [], "dirs": ["史藏/传记"], "evidence": "AGENTS 本机坑11（晏子春秋在史藏/传记）+ fetch_all.py 实测"},
    "列子": {"aliases": [], "dirs": ["子藏/道藏"], "evidence": "AGENTS 本机坑11（列子在道藏）；D-033 时未定位，候验证"},
    "淮南子": {"aliases": [], "dirs": ["子藏/诸子", "子藏/法家"], "evidence": "fetch_all.py 兜底双目录"},
    "目录名-繁简并存": {"aliases": ["諸子=诸子"], "dirs": ["子藏"], "evidence": "AGENTS 本机坑11（目录繁简混杂，文件名匹配需两套都试）"},
}


def gb2312_ratio(s: str) -> float:
    """GB2312 一级二级常用字占比——mojibake 文本大量落入 GBK 扩展区，还原后占比显著回升"""
    if not s:
        return 0.0
    ok = n = 0
    for ch in s:
        if ch.isascii() or ch.isspace():
            ok += 1
            n += 1
            continue
        if 0x4E00 <= ord(ch) <= 0x9FFF:
            n += 1
            try:
                ch.encode("gb2312")
                ok += 1
            except UnicodeEncodeError:
                pass
    return ok / n if n else 0.0


def _convert_runs(line: str) -> str:
    """逐段兜底：把可 GBK 编码的段各自尝试还原，不可编码字符（PUA 等）原样保留"""
    out, buf = [], []
    for ch in line:
        try:
            ch.encode("gbk")
            buf.append(ch)
        except UnicodeEncodeError:
            if buf:
                out.append(_fix_run("".join(buf)))
                buf = []
            out.append(ch)
    if buf:
        out.append(_fix_run("".join(buf)))
    return "".join(out)


def _fix_run(run: str) -> str:
    try:
        return run.encode("gbk").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return run


def try_unmojibake(line: str):
    """返回 (fixed_line, verdict)：verdict ∈ {clean, fixed, stuck}（stuck=疑似 mojibake 但还原失败）"""
    if not line.strip():
        return line, "clean"
    cjk = sum(1 for c in line if 0x4E00 <= ord(c) <= 0x9FFF)
    if cjk < 4:
        return line, "clean"  # 短行不足以判
    r0 = gb2312_ratio(line)
    if r0 > 0.93:
        return line, "clean"  # 本来就是规整中文，不试
    fixed = _convert_runs(line)
    r1 = gb2312_ratio(fixed)
    if fixed != line and r1 > r0 + 0.10 and r1 > 0.75:
        return fixed, "fixed"
    if "锟斤拷" in line or "\ufffd" in line:
        return line, "stuck"
    if r0 < 0.60 and r1 <= r0 + 0.10:
        return line, "stuck"  # 又怪又修不动——记录人工看
    return line, "clean"


def count_jazhu(text: str):
    n_blocks = len(JAZHU.findall(text))
    n_lines = sum(1 for l in text.split("\n") if JAZHU.search(l))
    return n_blocks, n_lines


def scan_file(path: Path, out_dir: Path | None, do_fix: bool):
    raw = path.read_text(encoding="utf-8", errors="replace")
    lines = raw.split("\n")
    stat = {"file": str(path), "lines": len(lines), "fixed": 0, "stuck": 0,
            "stuck_samples": [], "fixed_samples": [], "jazhu_blocks": 0, "jazhu_lines": 0}
    fixed_lines = []
    for i, l in enumerate(lines):
        f, verdict = try_unmojibake(l)
        fixed_lines.append(f)
        if verdict == "fixed":
            stat["fixed"] += 1
            if len(stat["fixed_samples"]) < 3:
                stat["fixed_samples"].append({"line": i + 1, "before": l[:60], "after": f[:60]})
        elif verdict == "stuck":
            stat["stuck"] += 1
            if len(stat["stuck_samples"]) < 5:
                stat["stuck_samples"].append({"line": i + 1, "text": l[:60]})
    stat["jazhu_blocks"], stat["jazhu_lines"] = count_jazhu(raw)
    stat["jazhu_line_ratio"] = round(stat["jazhu_lines"] / max(1, stat["lines"]), 4)
    # 【】双义风险旗：夹注占行比高且含案/按/注/校字样（乐府诗集〖逯案〗同族风险）
    stat["jazhu_risk"] = bool(stat["jazhu_lines"]) and (
        stat["jazhu_line_ratio"] > 0.30
        or bool(re.search(r"【[^】]{0,6}(案|按|笺|疏证)[：:〕】]", raw[:200000])))
    if do_fix and stat["fixed"]:
        dest = out_dir / path.name
        dest.write_text("\n".join(fixed_lines), encoding="utf-8")
        stat["fixed_copy"] = str(dest)
    return stat


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dirs", default=",".join(DEFAULT_DIRS))
    ap.add_argument("--out", default=str(DEFAULT_OUT))
    ap.add_argument("--fix", action="store_true", help="落盘修整副本（原文件不动）")
    ap.add_argument("--min-fixed", type=int, default=1, help="还原行数≥此值的文件进重点清单")
    args = ap.parse_args()
    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    files = []
    for d in args.dirs.split(","):
        d = d.strip().strip('"')
        p = Path(d)
        if p.is_dir():
            files += sorted(p.glob("*.txt"))
        elif p.is_file():
            files.append(p)
    report = {"scan_dirs": args.dirs, "n_files": len(files), "files": [], "alias_map": ALIAS_MAP}
    for f in files:
        st = scan_file(f, out_dir, args.fix)
        report["files"].append(st)
        tag = ""
        if st["fixed"] >= args.min_fixed:
            tag = f"  ← Mojibake×{st['fixed']}"
        if st["stuck"]:
            tag += f"  stuck×{st['stuck']}"
        print(f"{f.name:28s} lines={st['lines']:>6} 夹注行={st['jazhu_lines']:>5} "
              f"({st['jazhu_line_ratio']:.1%}){' [双义风险]' if st['jazhu_risk'] else ''}{tag}")

    heavy = [f for f in report["files"] if f["fixed"] >= args.min_fixed]
    stuck = [f for f in report["files"] if f["stuck"]]
    risk = [f["file"] for f in report["files"] if f["jazhu_risk"]]
    (out_dir / "dzg_precheck_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=1), encoding="utf-8")
    md = ["# 殆知阁底本预修整报告（夜班 2026-11-17）", "",
          f"扫描 {report['n_files']} 个 txt；Mojibake 修整 {sum(f['fixed'] for f in report['files'])} 行；"
          f"stuck（修不动候人工）{sum(f['stuck'] for f in report['files'])} 行。", ""]
    if heavy:
        md += ["## Mojibake 文件", ""]
        for f in heavy:
            md.append(f"- **{Path(f['file']).name}** 还原 {f['fixed']} 行 / {f['lines']} 行")
            for s in f["fixed_samples"][:2]:
                md.append(f"  - L{s['line']}: `{s['before'][:40]}` → `{s['after'][:40]}`")
        md.append("")
    if stuck:
        md += ["## stuck 行（人工候）", ""]
        for f in stuck:
            md.append(f"- {Path(f['file']).name}: {f['stuck']} 行，如 L{f['stuck_samples'][0]['line']} "
                      f"`{f['stuck_samples'][0]['text'][:40]}`")
        md.append("")
    md += ["## 【夹注】双义风险文件（只标旗，剥除策略候拍板）", ""]
    md += [f"- {Path(x).name}" for x in risk] or ["- 无"]
    md += ["", "## 同书异名归一表（dzg_alias_map.json 同步落盘）", ""]
    for k, v in ALIAS_MAP.items():
        md.append(f"- **{k}**：aliases={v['aliases']} dirs={v['dirs']}（{v['evidence']}）")
    (out_dir / "dzg_precheck_report.md").write_text("\n".join(md), encoding="utf-8")
    (out_dir / "dzg_alias_map.json").write_text(
        json.dumps(ALIAS_MAP, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n报告：{out_dir / 'dzg_precheck_report.md'}")


if __name__ == "__main__":
    main()
