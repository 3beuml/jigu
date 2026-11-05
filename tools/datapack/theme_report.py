#!/usr/bin/env python3
"""主题词表账面工具：对库内诗类逐篇跑 theme_hits_for，报告各细类计数/覆盖率/抽样。

用途（批 2 政策）：
- 改 THEME_RULES 前后各跑一次，看每类计数变化与"最大单类占比"，防词表质量倒挂；
- 抽样处注意有误伤一目了然（每类随机 3 条报 title + 首段 30 字）；
- 未打标篇数与覆盖率一目了然，作为"先做高频 N 条"的决策依据。

运行：
  python tools/datapack/theme_report.py                 # 检查 build-datapack/jigu-full.db
  python tools/datapack/theme_report.py --db <另一库>
只读数据库，不改任何表。
"""

import argparse
import random
import sqlite3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from build import POETRY_CATEGORIES, theme_hits_for  # noqa: E402

SAMPLES_PER_CLASS = 3

DEFAULT_DB = Path(__file__).resolve().parents[2] / "build-datapack" / "jigu-full.db"


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--db", type=Path, default=DEFAULT_DB, help="jigu.db 路径（默认全量库）")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    conn = sqlite3.connect(f"file:{args.db}?mode=ro", uri=True)
    cats = ",".join("?" for _ in POETRY_CATEGORIES)
    rows = conn.execute(
        f"SELECT id, title, paragraphs, category FROM poems WHERE category IN ({cats})",
        POETRY_CATEGORIES,
    ).fetchall()

    tally: dict[str, int] = {}
    counter: dict[str, List] = {}
    cat_counts = {}
    # 收集起来先计数再抽样（直接边扫边抽即可）
    r = random.Random(args.seed)
    samples: dict[str, list] = {}
    untagged = 0
    cat_tagged: dict[str, int] = {}
    cat_total: dict[str, int] = {}

    import json as _json
    for pid, title, body, cat in rows:
        body = "".join(_json.loads(body))
        hits = theme_hits_for(title, body)
        cat_total[cat] = cat_total.get(cat, 0) + 1
        if hits:
            cat_tagged[cat] = cat_tagged.get(cat, 0) + 1
            for h in hits:
                tally[h] = tally.get(h, 0) + 1
                bucket = samples.setdefault(h, [])
                if len(bucket) < 40:
                    bucket.append(pid)
                elif r.random() < 0.02:  # 温吞的蓄水池近似：多见类仍换样本
                    bucket[r.randrange(len(bucket))] = pid
            if len(hits) == 0:
                untagged += 1
        else:
            untagged += 1

    def poem_info(pid: int) -> str:
        t, a, b = conn.execute(
            "SELECT p.title, IFNULL(a.name,''), substr(paragraphs,1,60) FROM poems p "
            "LEFT JOIN authors a ON a.id = p.author_id WHERE p.id = ?", (pid,)
        ).fetchone()
        return f"《{t}》{a or '佚名'}：{b}…"

    total = len(rows)
    tagged = total - untagged
    print(f"库：{args.db}")
    print(f"诗类篇数 {total}，有主题 {tagged}（{tagged/total:.1%}），无主题 {untagged}")
    print()
    print(f"{'细类':<6}{'计数':<9}{'占比':<8}样本")
    for name, cnt in sorted(tally.items(), key=lambda kv: -kv[1]):
        share = f"{cnt/total:.1%}"
        sample = " ｜ ".join(poem_info(pid) for pid in samples.get(name, [])[:SAMPLES_PER_CLASS])
        print(f"{name:<6}{cnt:<9}{share:<8}{sample}")
    print()
    for cat in sorted(cat_total, key=lambda k: -cat_total[k]):
        t, g = cat_tagged.get(cat, 0), cat_total[cat]
        print(f"  [{cat}] {t}/{g}（{t/g:.0%}）")
    conn.close()


if __name__ == "__main__":
    # 轻量别名：直接用 list 免 typing 依赖
    List = list  # noqa: E811
    main()
