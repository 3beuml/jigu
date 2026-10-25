#!/usr/bin/env python3
"""数据包抽查工具（阶段验收用）。

用法：
  python tools/datapack/inspect.py                # 精选库总览 + 每分类抽样
  python tools/datapack/inspect.py --full         # 看全量库
  python tools/datapack/inspect.py --search 月落乌啼
  python tools/datapack/inspect.py --title 兰亭集序
"""

import argparse
import sqlite3
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
CORE = REPO / "build-datapack/jigu-core.db"
FULL = REPO / "build-datapack/jigu-full.db"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--full", action="store_true", help="查全量库而非精选库")
    ap.add_argument("--search", help="FTS 全文搜索（≥3 个字）")
    ap.add_argument("--title", help="按标题精确/前缀查")
    ap.add_argument("-n", type=int, default=2, help="每分类抽样条数")
    args = ap.parse_args()

    db_path = FULL if args.full else CORE
    if not db_path.exists():
        print(f"库不存在：{db_path}", file=sys.stderr)
        print("全量库需先运行 python tools/datapack/build.py --dataset <数据集目录>", file=sys.stderr)
        return 1
    db = sqlite3.connect(db_path)

    meta = dict(db.execute("SELECT key, value FROM meta"))
    print(f"库：{db_path.name}（schema v{db.execute('PRAGMA user_version').fetchone()[0]}，"
          f"构建于 {meta.get('built_at','?')}）")
    print(f"总数：{db.execute('SELECT count(*) FROM poems').fetchone()[0]}")
    print(f"分类：{meta.get('counts', '{}')}")
    print(f"去重：{meta.get('dups', '{}')}")

    if args.search:
        print(f"\n== FTS 搜索「{args.search}」 ==")
        rows = db.execute(
            "SELECT p.category, p.title, a.name, substr(p.body, 1, 40) "
            "FROM poems_fts JOIN poems p ON p.id = poems_fts.rowid "
            "LEFT JOIN authors a ON a.id = p.author_id "
            "WHERE poems_fts MATCH ? LIMIT 10", (args.search,)
        ).fetchall()
        for r in rows:
            print(f"  [{r[0]}] {r[1]} · {r[2]}：{r[3]}…")
        if not rows:
            print("  （无结果；trigram 分词仅支持 ≥3 字查询）")

    if args.title:
        print(f"\n== 标题「{args.title}」 ==")
        rows = db.execute(
            "SELECT p.category, p.title, a.name, substr(p.body, 1, 60) "
            "FROM poems p LEFT JOIN authors a ON a.id = p.author_id "
            "WHERE p.title LIKE ? LIMIT 10", (args.title + "%",)
        ).fetchall()
        for r in rows:
            print(f"  [{r[0]}] {r[1]} · {r[2]}：{r[3]}…")
        if not rows:
            print("  （无结果）")

    if not args.search and not args.title:
        print("\n== 每分类抽样 ==")
        cats = db.execute(
            "SELECT category, count(*) FROM poems GROUP BY category ORDER BY 2 DESC"
        ).fetchall()
        for cat, n in cats:
            print(f"\n  [{cat}] 共 {n} 条")
            rows = db.execute(
                "SELECT p.title, IFNULL(a.name,'佚名'), substr(p.body, 1, 50) "
                "FROM poems p LEFT JOIN authors a ON a.id = p.author_id "
                "WHERE p.category = ? ORDER BY RANDOM() LIMIT ?", (cat, args.n)
            ).fetchall()
            for r in rows:
                print(f"    《{r[0]}》 {r[1]}：{r[2]}…")
    return 0


if __name__ == "__main__":
    sys.exit(main())
