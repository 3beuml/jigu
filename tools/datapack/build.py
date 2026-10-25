#!/usr/bin/env python3
"""汲古数据包构建脚本。

把 chinese-poetry 数据集（外加蒙学目录中的古文观止等）清洗、归一（OpenCC t2s）、
去重后写入 SQLite，产出两个库：

  1. 精选库 jigu-core.db   -> app/src/main/assets/data/（随 APK 打包）
  2. 全量库 jigu-full.db   -> build-datapack/（App 内可选下载，不进 git）

用法：
  python tools/datapack/build.py --dataset <chinese-poetry 克隆目录>

设计要点（对应 docs/DECISIONS.md D-005/D-006）：
  - 一套 schema，两个库：同一构建函数，喂不同的数据源清单
  - 全文归一为简体（OpenCC t2s）；去重指纹 = md5(title|author|body)
  - FTS5 trigram 全文索引（Android 11+ 原生支持；API 26-29 需 App 侧 LIKE 兜底，
    阶段 6 处理，见 D-008 待办）
  - notes 列承载公版评点（幽梦影各家评点等），呼应 D-007 内容策略
  - PRAGMA user_version = schema_version；meta 表记录 data_version 与各分类计数
"""

from __future__ import annotations

import argparse
import hashlib
import json
import sqlite3
import sys
import time
from pathlib import Path

from opencc import OpenCC

T2S = OpenCC("t2s")

SCHEMA_VERSION = 1
DATA_VERSION = 1

SCHEMA = """
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);

CREATE TABLE authors(
  id      INTEGER PRIMARY KEY,
  name    TEXT NOT NULL,
  dynasty TEXT NOT NULL DEFAULT '',
  brief   TEXT NOT NULL DEFAULT '',
  UNIQUE(name, dynasty)
);

CREATE TABLE poems(
  id         INTEGER PRIMARY KEY,
  category   TEXT NOT NULL,
  title      TEXT NOT NULL,
  author_id  INTEGER REFERENCES authors(id),
  rhythmic   TEXT NOT NULL DEFAULT '',
  chapter    TEXT NOT NULL DEFAULT '',
  section    TEXT NOT NULL DEFAULT '',
  paragraphs TEXT NOT NULL,
  body       TEXT NOT NULL,
  tags       TEXT NOT NULL DEFAULT '[]',
  notes      TEXT NOT NULL DEFAULT '[]',
  hash       TEXT NOT NULL,
  source_id  TEXT NOT NULL DEFAULT '',
  UNIQUE(category, hash)
);
CREATE INDEX idx_poems_category ON poems(category);
CREATE INDEX idx_poems_author   ON poems(author_id);
CREATE INDEX idx_poems_title    ON poems(title);

CREATE VIRTUAL TABLE poems_fts USING fts5(
  body, title, content='poems', content_rowid='id', tokenize='trigram'
);

-- 日推引擎的节气/节日映射（阶段 2 填充）
CREATE TABLE calendar_map(
  id      INTEGER PRIMARY KEY,
  kind    TEXT NOT NULL,   -- solar_term | huaxin | festival | phenology
  key     TEXT NOT NULL,
  name    TEXT NOT NULL DEFAULT '',
  poem_id INTEGER REFERENCES poems(id),
  note    TEXT NOT NULL DEFAULT ''
);

-- 循物寻诗（阶段 1 建表，词表 v0 随后填充）
CREATE TABLE imagery(
  id   INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  note TEXT NOT NULL DEFAULT ''
);
CREATE TABLE poem_imagery(
  poem_id    INTEGER REFERENCES poems(id),
  imagery_id INTEGER REFERENCES imagery(id),
  PRIMARY KEY(poem_id, imagery_id)
);

-- 主题诗单（预编 24 节气 + 8 节日，阶段 6 填充）
CREATE TABLE collections(
  id   INTEGER PRIMARY KEY,
  name TEXT NOT NULL,
  kind TEXT NOT NULL DEFAULT '',
  note TEXT NOT NULL DEFAULT ''
);
CREATE TABLE collection_items(
  collection_id INTEGER REFERENCES collections(id),
  poem_id       INTEGER REFERENCES poems(id),
  sort          INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(collection_id, poem_id)
);

-- 用户数据（收藏；自建诗单阶段 6 定）
CREATE TABLE favorites(
  poem_id    INTEGER PRIMARY KEY REFERENCES poems(id),
  created_at INTEGER NOT NULL
);
"""


def s(text: str) -> str:
    """繁->简 + 清理不可见空白。"""
    return T2S.convert(text).strip()


def j(obj) -> str:
    return json.dumps(obj, ensure_ascii=False)


class Db:
    def __init__(self, path: Path):
        path.parent.mkdir(parents=True, exist_ok=True)
        if path.exists():
            path.unlink()
        self.conn = sqlite3.connect(path)
        self.conn.executescript(SCHEMA)
        self.conn.execute("PRAGMA user_version = %d" % SCHEMA_VERSION)
        self.authors: dict[tuple[str, str], int] = {}
        self.counts: dict[str, int] = {}
        self.dups: dict[str, int] = {}

    def author(self, name: str, dynasty: str, brief: str = "") -> int:
        name, dynasty, brief = s(name), s(dynasty), s(brief)
        key = (name, dynasty)
        if key in self.authors:
            aid = self.authors[key]
            if brief:  # 后到的作者小传回填（首次创建时可能还没有）
                self.conn.execute(
                    "UPDATE authors SET brief = ? WHERE id = ? AND brief = ''",
                    (brief, aid),
                )
            return aid
        cur = self.conn.execute(
            "INSERT INTO authors(name, dynasty, brief) VALUES (?,?,?)",
            (name, dynasty, brief),
        )
        self.authors[key] = cur.lastrowid
        return cur.lastrowid

    def poem(self, rec: dict) -> bool:
        """rec: category/title/author/dynasty/paragraphs[/rhythmic/chapter/section/tags/notes/source_id/brief]"""
        paras = [s(p) for p in rec["paragraphs"] if p and p.strip()]
        if not paras:
            return False
        body = "\n".join(paras)
        title = s(rec.get("title") or "无题")
        h = hashlib.md5(f"{title}|{s(rec.get('author',''))}|{body}".encode()).hexdigest()
        cat = rec["category"]
        try:
            cur = self.conn.execute(
                "INSERT INTO poems(category,title,author_id,rhythmic,chapter,section,"
                "paragraphs,body,tags,notes,hash,source_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    cat, title,
                    self.author(rec.get("author", ""), rec.get("dynasty", ""), rec.get("brief", "")),
                    s(rec.get("rhythmic", "")), s(rec.get("chapter", "")), s(rec.get("section", "")),
                    j(paras), body, j(rec.get("tags", [])), j(rec.get("notes", [])),
                    h, rec.get("source_id", ""),
                ),
            )
        except sqlite3.IntegrityError:
            self.dups[cat] = self.dups.get(cat, 0) + 1
            return False
        self.counts[cat] = self.counts.get(cat, 0) + 1
        _ = cur.lastrowid
        return True

    def finish(self, label: str):
        self.conn.execute(
            "INSERT INTO poems_fts(rowid, body, title) SELECT id, body, title FROM poems"
        )
        for k, v in {
            "schema_version": SCHEMA_VERSION,
            "data_version": DATA_VERSION,
            "label": label,
            "built_at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
            "counts": j(self.counts),
            "dups": j(self.dups),
        }.items():
            self.conn.execute("INSERT INTO meta(key,value) VALUES (?,?)", (k, v))
        self.conn.commit()
        self.conn.execute("VACUUM")
        self.conn.commit()
        self.conn.close()


def load_json(path: Path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def author_desc(dataset: Path, fname: str) -> dict[str, str]:
    """authors.tang/song.json -> {name: desc}"""
    out = {}
    p = dataset / "全唐诗" / fname
    if p.exists():
        for a in load_json(p):
            out[a["name"]] = a.get("desc", "")
    return out


# ---------- 各数据源适配器：yield 归一化 rec ----------

def it_tang_song(dataset: Path, prefix: str, category: str, dynasty: str, descs: dict):
    for p in sorted((dataset / "全唐诗").glob(f"{prefix}.*.json")):
        for r in load_json(p):
            yield {
                "category": category, "title": r["title"], "author": r["author"],
                "dynasty": dynasty, "paragraphs": r["paragraphs"],
                "source_id": r.get("id", ""), "brief": descs.get(r["author"], ""),
            }


def it_tang300(dataset: Path, descs: dict | None = None):
    descs = descs or {}
    for r in load_json(dataset / "全唐诗" / "唐诗三百首.json"):
        yield {
            "category": "tang_poem", "title": r["title"], "author": r["author"],
            "dynasty": "唐", "paragraphs": r["paragraphs"], "tags": r.get("tags", []),
            "source_id": r.get("id", ""), "brief": descs.get(r["author"], ""),
        }


def it_ci(dataset: Path):
    for p in sorted((dataset / "宋词").glob("ci.song.*.json")):
        for r in load_json(p):
            # 宋词数据无 title 字段，以词牌为题
            yield {
                "category": "ci", "title": r.get("rhythmic", "") or "无题", "author": r["author"],
                "dynasty": "宋", "paragraphs": r["paragraphs"], "rhythmic": r.get("rhythmic", ""),
            }


def it_shijing(dataset: Path):
    for r in load_json(dataset / "诗经" / "shijing.json"):
        yield {
            "category": "shijing", "title": r["title"], "author": "",
            "dynasty": "先秦", "paragraphs": r["content"],
            "chapter": r.get("chapter", ""), "section": r.get("section", ""),
        }


def it_chuci(dataset: Path):
    for r in load_json(dataset / "楚辞" / "chuci.json"):
        yield {
            "category": "chuci", "title": r["title"], "author": r.get("author", ""),
            "dynasty": "先秦", "paragraphs": r["content"], "section": r.get("section", ""),
        }


def it_chapters(dataset: Path, rel: str, category: str, dynasty: str):
    """论语 / 孟子：[{chapter, paragraphs}]；大学等 dict 变体也在处理。"""
    data = load_json(dataset / rel)
    if isinstance(data, dict):
        data = [data]
    for i, r in enumerate(data):
        chapter = r.get("chapter", "")
        paras = r.get("paragraphs") or r.get("content") or []
        if isinstance(paras, str):
            paras = [paras]
        yield {
            "category": category, "title": chapter or f"{category}·{i+1}",
            "author": r.get("author", ""), "dynasty": dynasty,
            "paragraphs": paras, "chapter": chapter,
        }


def it_guwen(dataset: Path):
    """古文观止（蒙学/guwenguanzhi.json）：{content:[{title:卷,content:[{chapter,source,author,paragraphs}]}]}"""
    d = load_json(dataset / "蒙学" / "guwenguanzhi.json")
    for vol in d["content"]:
        for a in vol["content"]:
            author_raw = a.get("author", "")  # 形如 "先秦：左丘明 "
            dynasty, _, name = author_raw.partition("：")
            name = name.strip()
            # source 字段混杂：《左傳》与 "(王羲之)" 两种形态；与作者同名时丢弃
            src = a.get("source", "").strip("《》()").strip()
            if src == name:
                src = ""
            yield {
                "category": "guwen", "title": a["chapter"], "author": name,
                "dynasty": s(dynasty.strip()) or "先秦", "paragraphs": a["paragraphs"],
                "section": src, "chapter": vol["title"],
            }


def it_youmengying(dataset: Path):
    for i, r in enumerate(load_json(dataset / "幽梦影" / "youmengying.json"), 1):
        yield {
            "category": "youmengying", "title": f"幽梦影·第{i}则", "author": "张潮",
            "dynasty": "清", "paragraphs": [r["content"]], "notes": r.get("comment", []),
        }


def it_nalan(dataset: Path):
    for r in load_json(dataset / "纳兰性德" / "纳兰性德诗集.json"):
        yield {
            "category": "qing_ci", "title": r["title"], "author": r.get("author", "纳兰性德"),
            "dynasty": "清", "paragraphs": r["para"], "source_id": "",
        }


def it_caocao(dataset: Path):
    for r in load_json(dataset / "曹操诗集" / "caocao.json"):
        yield {
            "category": "han_wei", "title": r["title"], "author": "曹操",
            "dynasty": "汉", "paragraphs": r["paragraphs"],
        }


def it_yuanqu(dataset: Path):
    for r in load_json(dataset / "元曲" / "yuanqu.json"):
        yield {
            "category": "yuan_qu", "title": r["title"], "author": r["author"],
            "dynasty": "元", "paragraphs": r["paragraphs"],
        }


# ---------- 组装 ----------

CORE_SOURCES = [
    ("唐诗三百首", lambda ds: it_tang300(ds, author_desc(ds, "authors.tang.json"))),
    ("诗经", lambda ds: it_shijing(ds)),
    ("楚辞", lambda ds: it_chuci(ds)),
    ("论语", lambda ds: it_chapters(ds, "论语/lunyu.json", "lunyu", "先秦")),
    ("四书五经", lambda ds: it_chapters(ds, "四书五经/mengzi.json", "sishu", "战国")),
    ("古文观止", lambda ds: it_guwen(ds)),
    ("幽梦影", lambda ds: it_youmengying(ds)),
    ("纳兰词", lambda ds: it_nalan(ds)),
    ("曹操诗集", lambda ds: it_caocao(ds)),
]

FULL_EXTRA_SOURCES = [
    ("全唐诗", lambda ds, d: it_tang_song(ds, "poet.tang", "tang_poem", "唐", author_desc(ds, "authors.tang.json"))),
    ("全宋诗", lambda ds, d: it_tang_song(ds, "poet.song", "song_poem", "宋", author_desc(ds, "authors.song.json"))),
    ("全宋词", lambda ds, d: it_ci(ds)),
    ("元曲", lambda ds, d: it_yuanqu(ds)),
]


def build(db_path: Path, dataset: Path, label: str, full: bool):
    db = Db(db_path)
    for name, fn in CORE_SOURCES:
        n = 0
        for rec in fn(dataset):
            if db.poem(rec):
                n += 1
        print(f"  [{label}] {name}: {n} 条")
    if full:
        for name, fn in FULL_EXTRA_SOURCES:
            n = 0
            for rec in fn(dataset, None):
                if db.poem(rec):
                    n += 1
                if n % 20000 == 0 and n:
                    print(f"  [{label}] {name}: {n} …", flush=True)
            print(f"  [{label}] {name}: {n} 条")
    db.finish(label)
    total = sum(db.counts.values())
    print(f"  [{label}] 合计 {total} 条，重出剔除 {sum(db.dups.values())} 条 -> {db_path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", required=True, type=Path, help="chinese-poetry 克隆目录")
    ap.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    args = ap.parse_args()

    core_out = args.repo / "app/src/main/assets/data/jigu-core.db"
    full_out = args.repo / "build-datapack/jigu-full.db"

    print("== 精选库 ==")
    build(core_out, args.dataset, "core", full=False)
    print("== 全量库 ==")
    build(full_out, args.dataset, "full", full=True)


if __name__ == "__main__":
    sys.exit(main())
