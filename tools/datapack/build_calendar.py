#!/usr/bin/env python3
"""预生成节气/农历节日日期表 -> app/src/main/assets/data/calendar.json

用 cnlunar（PyPI，1900-2100 支持）在构建期算好，App 端只查表不计算：
  - solar_terms: {"2024-02-04": "立春", ...}   24 节气交节日
  - festivals:   {"2024-02-10": "春节", ...}   农历节日 + 除夕
年份范围 2024-2040（App 遇到范围外年份回退到普通日推即可）。

锚点已验证：2024-02-04 立春 / 2024-02-10 春节 / 2025-05-31 端午。
"""

import json
import time
from datetime import date, datetime, timedelta
from pathlib import Path

import cnlunar

START_YEAR = 2024
END_YEAR = 2040

LUNAR_FESTIVALS = {
    (1, 1): "春节",
    (1, 15): "元宵",
    (5, 5): "端午",
    (7, 7): "七夕",
    (8, 15): "中秋",
    (9, 9): "重阳",
    (12, 8): "腊八",
}


def main():
    terms: dict[str, str] = {}
    festivals: dict[str, str] = {}
    lunar_ymd: dict[str, tuple[int, int]] = {}

    day = date(START_YEAR, 1, 1)
    end = date(END_YEAR, 12, 31)
    prev_term = ""
    while day <= end:
        dt = datetime(day.year, day.month, day.day)
        lu = cnlunar.Lunar(dt, godType="8char")
        term = lu.todaySolarTerms or ""
        if term == "无":  # cnlunar 用字面量「无」表示非交节日
            term = ""
        if term and term != prev_term:
            terms[day.isoformat()] = term
        prev_term = term
        is_leap = lu.lunarMonthCn.startswith("闰")
        lunar_ymd[day.isoformat()] = (lu.lunarMonth, lu.lunarDay, is_leap)
        day += timedelta(days=1)

    # 农历节日（跳过闰月：闰五月初五不算端午）+ 除夕（明天是正月初一的那天）
    for iso, (m, d, leap) in lunar_ymd.items():
        if not leap and (m, d) in LUNAR_FESTIVALS:
            festivals[iso] = LUNAR_FESTIVALS[(m, d)]
    for iso, (m, d, leap) in lunar_ymd.items():
        nxt = (date.fromisoformat(iso) + timedelta(days=1)).isoformat()
        nxt_ymd = lunar_ymd.get(nxt)
        if nxt_ymd and nxt_ymd[:2] == (1, 1) and not nxt_ymd[2]:
            festivals[iso] = "除夕"

    out = {
        "version": 1,
        "generated_at": time.strftime("%Y-%m-%d"),
        "range": [START_YEAR, END_YEAR],
        "source": "cnlunar (PyPI) 构建期预生成；节气交节以 cnlunar 推算为准",
        "solar_terms": dict(sorted(terms.items())),
        "festivals": dict(sorted(festivals.items())),
    }
    dst = Path(__file__).resolve().parents[2] / "app/src/main/assets/data/calendar.json"
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(json.dumps(out, ensure_ascii=False, indent=0), encoding="utf-8")
    print(f"节气 {len(terms)} 条，节日 {len(festivals)} 条 -> {dst}")
    # 抽查
    for d in ["2024-02-04", "2024-02-10", "2025-05-31", "2024-09-17"]:
        src = "solar_terms" if d in terms else "festivals"
        print(f"  {d}: 节气={terms.get(d, '-')} 节日={festivals.get(d, '-')}")


if __name__ == "__main__":
    main()
