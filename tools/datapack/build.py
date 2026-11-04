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
import re
import sqlite3
import sys
import time
from pathlib import Path

from opencc import OpenCC

T2S = OpenCC("t2s")

REPO = Path(__file__).resolve().parents[2]

SCHEMA_VERSION = 7  # +1: 瘦身——poems.body 删列（正文=paragraphs 拼接冗余存储；FTS 外部内容表改挂 paragraphs）
DATA_VERSION = 19  # +1: 批5（D-024）——三书入库（庄子内篇七全+外篇二 9 条 / 史记名段 18 条 / 世说名则 39 条）+ 名句种子 +85（D-020 先例点名录入）+ 自动补种目标 300→400

SCHEMA = """
CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL);

CREATE TABLE authors(
  id         INTEGER PRIMARY KEY,
  name       TEXT NOT NULL,
  dynasty    TEXT NOT NULL DEFAULT '',
  brief      TEXT NOT NULL DEFAULT '',
  birth_year INTEGER,
  death_year INTEGER,
  UNIQUE(name, dynasty)
);

CREATE TABLE poems(
  id         INTEGER PRIMARY KEY,
  category   TEXT NOT NULL,
  type       TEXT NOT NULL DEFAULT '',
  genre      TEXT NOT NULL DEFAULT '',    -- 古文文体 13 类（古文观止；诗词为空）
  era        TEXT NOT NULL DEFAULT '',
  title      TEXT NOT NULL,
  author_id  INTEGER REFERENCES authors(id),
  rhythmic   TEXT NOT NULL DEFAULT '',
  chapter    TEXT NOT NULL DEFAULT '',
  section    TEXT NOT NULL DEFAULT '',
  paragraphs TEXT NOT NULL,
  tags       TEXT NOT NULL DEFAULT '[]',
  notes      TEXT NOT NULL DEFAULT '[]',
  hash       TEXT NOT NULL,
  source_id  TEXT NOT NULL DEFAULT '',
  popularity INTEGER NOT NULL DEFAULT 0,
  UNIQUE(category, hash)
  -- 瘦身（SCHEMA 7，遗留清单"APK 瘦身"）：body 列已删——正文 = paragraphs 逐行
  -- 按换行符拼接的完全冗余存储（Kotlin 侧 Poem.body 由 paragraphs 还原，UI 零改动）；
  -- 全文检索改以 paragraphs 列为源（FTS 外部内容表）
);
CREATE INDEX idx_poems_category ON poems(category);
CREATE INDEX idx_poems_type     ON poems(type);
CREATE INDEX idx_poems_era      ON poems(era);
CREATE INDEX idx_poems_author   ON poems(author_id);
CREATE INDEX idx_poems_title    ON poems(title);

-- 名句表（今日页亮一句、名句模块浏览）；种子见 FAMOUS_LINES，构建期解析回 poem_id
CREATE TABLE famous_lines(
  id      INTEGER PRIMARY KEY,
  poem_id INTEGER NOT NULL REFERENCES poems(id),
  line    TEXT NOT NULL,
  source  TEXT NOT NULL DEFAULT 'manual',  -- manual=种子 / auto=热度补种（精修时按此筛）
  UNIQUE(poem_id)
);

CREATE VIRTUAL TABLE poems_fts USING fts5(
  paragraphs, title, content='poems', content_rowid='id', tokenize='trigram'
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

-- 主题（题材）标签：诗词专用，与意象体系同构
CREATE TABLE themes(
  id   INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  grp  TEXT NOT NULL DEFAULT ''            -- 题材四门：写景/抒怀/咏物/世情
);
CREATE TABLE poem_theme(
  poem_id  INTEGER REFERENCES poems(id),
  theme_id INTEGER REFERENCES themes(id),
  PRIMARY KEY(poem_id, theme_id)
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


def theme_hits_for(title: str, body: str) -> list[str]:
    """主题打标 v2→v3（权重制纯函数）：标题词命中即成；正文词需 >=2 处；负向词命中即弃。
    词法："^X" = 标题前缀匹配（v3 引入，救"送"单字全文匹配过宽——25449 篇全库 7.6% 全是送别）；
    其余子串命中。改词表必跑 tools/datapack/test_pipeline.py（含一正一负用例）。
    "咏物"特判：标题以"咏"开头且非咏史怀古（防止"咏史诗"被记咏物）。"""
    hits = []
    for name, grp, t_kws, b_kws, negs in THEME_RULES:
        if name == "咏物":
            continue
        if any(k in title or k in body for k in negs):
            continue
        t_hit = any(
            (title.startswith(k[1:]) if k.startswith("^") else k in title)
            for k in t_kws
        )
        b_count = sum(body.count(k) for k in b_kws)
        if t_hit or b_count >= 2:
            hits.append(name)
    if not ("咏史怀古" in hits or title.startswith("咏史")) and title.startswith("咏"):
        hits.append("咏物")
    return hits


def s(text: str) -> str:
    """繁->简 + 清理不可见空白 + 字形门闩（文楷缺字替换为 Unihan 可映射的通行字）+ 清洗 PUA 乱码。"""
    out = T2S.convert(text).strip()
    # OpenCC 不处理的生僻变体，点名映射（茍=苟之异体，四书五经源文本出现）
    for a, b in MANUAL_VARIANTS.items():
        out = out.replace(a, b)
    out = "".join(ch for ch in out if not (0xE000 <= ord(ch) <= 0xF8FF))
    if GLYPH_GATE["cmap"]:
        out = "".join(_gate_char(ch) for ch in out)
    return out


# 手工变体映射（谨慎点名，每条有据；不做批量异体归并）
MANUAL_VARIANTS = {"茍": "苟"}


# 字形门闩状态（main 里加载）
GLYPH_GATE: dict = {"cmap": set(), "variants": {}, "cache": {}, "stats": {}}


def _gate_char(ch: str) -> str:
    cp = ord(ch)
    if cp < 0x2E80:  # ASCII/标点/符号不查
        return ch
    cached = GLYPH_GATE["cache"].get(cp)
    if cached is not None:
        return cached
    rep = ch
    if cp not in GLYPH_GATE["cmap"]:
        cands = GLYPH_GATE["variants"].get(cp, {})
        for prio in sorted(cands):
            found = next((chr(c) for c in sorted(cands[prio]) if c in GLYPH_GATE["cmap"]), None)
            if found:
                rep = found
                break
    if rep != ch:
        GLYPH_GATE["stats"][f"{ch}->{rep}"] = GLYPH_GATE["stats"].get(f"{ch}->{rep}", 0) + 1
    GLYPH_GATE["cache"][cp] = rep
    return rep


def load_glyph_gate(font_path: Path, unihan_zip: Path):
    """文楷 cmap + Unihan 变体索引：kSimplifiedVariant > kZVariant > kSemanticVariant > kSpecializedSemanticVariant"""
    from fontTools.ttLib import TTFont
    import zipfile
    import io

    cmap = TTFont(str(font_path)).getBestCmap()
    GLYPH_GATE["cmap"] = set(cmap.keys())

    variants: dict[int, dict[int, set[int]]] = {}
    fields = [
        ("Unihan_OtherMappings.txt", "kSimplifiedVariant", 0),
        ("Unihan_Variants.txt", "kZVariant", 1),
        ("Unihan_Variants.txt", "kSemanticVariant", 2),
        ("Unihan_Variants.txt", "kSpecializedSemanticVariant", 3),
    ]
    z = zipfile.ZipFile(str(unihan_zip))
    for fname, field, prio in fields:
        with z.open(fname) as f:
            for raw in io.TextIOWrapper(f, encoding="utf-8"):
                line = raw.strip()
                if not line or line.startswith("#"):
                    continue
                parts = line.split("\t")
                if len(parts) < 3 or parts[1] != field:
                    continue
                try:
                    cp = int(parts[0].lstrip("U+"), 16)
                except ValueError:
                    continue
                for cand in parts[2].split():
                    try:
                        c = int(cand.lstrip("U+"), 16)
                    except ValueError:
                        continue
                    variants.setdefault(cp, {}).setdefault(prio, set()).add(c)
    GLYPH_GATE["variants"] = variants
    print(f"  字形门闩：文楷 {len(GLYPH_GATE['cmap'])} 字形，Unihan 变体索引 {len(variants)} 字")


# ---------- 体裁分类（移植 chinese-poetry-api classifier 的判定链） ----------
# 优先级：来源直接映射 > 词牌 > 乐府标题 > 句数×每句字数的结构分析
SENT_SPLIT = re.compile(r"[，。！？；、：]")


def classify_type(category: str, title: str, rhythmic: str, paragraphs: list[str]) -> str:
    if category == "shijing":
        return "诗经体"
    if category == "chuci":
        return "骚体"
    if category == "gushi19":  # 古诗十九首（D-010 外部扩容，批4）：五言古体之祖
        return "五言古诗"
    if category in ("lunyu", "sishu", "guwen", "laozi", "zhuangzi", "shishuo", "shiji",
                    "youmengying", "mengxue"):
        return "文"
    if category == "yuan_qu":
        return "曲"
    if category in ("ci", "qing_ci", "wudai_ci") or rhythmic:
        return "词"

    if title and (any(k in title for k in ("乐府", "曲辞")) or title.endswith(("行", "引", "歌", "吟"))):
        return "乐府"

    lines = [seg for p in paragraphs for seg in SENT_SPLIT.split(p) if seg.strip()]

    def clen(seg: str) -> int:
        return sum(1 for ch in seg if "\u4e00" <= ch <= "\u9fff")

    if not lines:
        return "其他"
    counts = [clen(x) for x in lines]
    first = counts[0]
    if first == 0 or not all(c == first for c in counts):
        return "古体诗"
    n = len(lines)
    if n == 4 and first == 5:
        return "五言绝句"
    if n == 4 and first == 7:
        return "七言绝句"
    if n == 8 and first == 5:
        return "五言律诗"
    if n == 8 and first == 7:
        return "七言律诗"
    if n > 8 and first == 5:
        return "五言排律"
    if n > 8 and first == 7:
        return "七言排律"
    if first == 4:
        return "四言诗"
    if first == 5:
        return "五言古诗"
    if first == 7:
        return "七言古诗"
    return "古体诗"


# 朝代归一：时间轴按此分组；先秦含诸子，两汉并入汉
ERA_MAP = {
    "唐代": "唐", "宋朝": "宋", "宋代": "宋", "汉朝": "汉", "两汉": "汉",
    "战国": "先秦", "明代": "明", "清朝": "清", "元朝": "元", "隋朝": "隋",
    "五代十国": "五代", "三国": "三国",
}

# 主要作者生卒年种子（负数=公元前）。只收把握大的；无年份者时间轴内排在其朝代末尾。
BIRTH_DEATH = [
    ("孔子", -551, -479), ("孟子", -372, -289), ("屈原", -340, -278),
    ("曹操", 155, 220),
    ("骆宾王", 619, 687), ("王勃", 650, 676), ("贺知章", 659, 744), ("陈子昂", 661, 702),
    ("张九龄", 678, 740), ("王之涣", 688, 742), ("孟浩然", 689, 740), ("王昌龄", 698, 757),
    ("王维", 701, 761), ("李白", 701, 762), ("高适", 704, 765), ("岑参", 718, 769),
    ("杜甫", 712, 770), ("张继", 715, 779), ("刘长卿", 726, 786), ("韦应物", 737, 792),
    ("卢纶", 739, 799), ("韩愈", 768, 824), ("孟郊", 751, 814), ("张籍", 766, 830),
    ("王建", 768, 835), ("刘禹锡", 772, 842), ("白居易", 772, 846), ("元稹", 779, 831),
    ("贾岛", 779, 843), ("李贺", 790, 816), ("杜牧", 803, 852), ("李商隐", 813, 858),
    ("温庭筠", 812, 866), ("皮日休", 834, 883), ("韦庄", 836, 910), ("杜荀鹤", 846, 904),
    ("李益", 748, 829),
    ("寇准", 961, 1023), ("林逋", 967, 1028), ("柳永", 984, 1053), ("晏殊", 991, 1055),
    ("梅尧臣", 1002, 1060), ("欧阳修", 1007, 1072), ("苏洵", 1009, 1066), ("曾巩", 1019, 1083),
    ("司马光", 1019, 1086), ("王安石", 1021, 1086), ("苏轼", 1037, 1101), ("晏几道", 1038, 1110),
    ("苏辙", 1039, 1112), ("黄庭坚", 1045, 1105), ("秦观", 1049, 1100), ("贺铸", 1052, 1125),
    ("晁补之", 1053, 1110), ("陈师道", 1053, 1102), ("周邦彦", 1056, 1121),
    ("李清照", 1084, 1155), ("陈与义", 1090, 1139), ("岳飞", 1103, 1142),
    ("陆游", 1125, 1210), ("范成大", 1126, 1193), ("杨万里", 1127, 1206), ("朱熹", 1130, 1200),
    ("张孝祥", 1132, 1170), ("辛弃疾", 1140, 1207), ("姜夔", 1154, 1221), ("文天祥", 1236, 1283),
    ("白朴", 1226, 1306), ("张养浩", 1270, 1329),
    ("纳兰性德", 1655, 1685), ("郑燮", 1693, 1765), ("袁枚", 1716, 1798), ("龚自珍", 1792, 1841),
]


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
        name, brief = s(name), clean_brief(s(brief))
        dynasty = s(dynasty)
        dynasty = ERA_MAP.get(dynasty, dynasty)
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
        """rec: category/title/author/dynasty/paragraphs[/rhythmic/chapter/section/tags/notes/source_id/brief/type]"""
        paras = [s(p) for p in rec["paragraphs"] if isinstance(p, str) and p.strip()]
        if not paras:
            return False
        body = "\n".join(paras)
        title = clean_placeholder_title(norm_title(s(rec.get("title") or "无题")))
        title = next((new for old, new in TITLE_FIXES if title == old), title)
        h = hashlib.md5(f"{title}|{s(rec.get('author',''))}|{body}".encode()).hexdigest()
        cat = rec["category"]
        era = ERA_MAP.get(s(rec.get("dynasty", "")), s(rec.get("dynasty", "")))
        typ = rec.get("type") or classify_type(cat, title, s(rec.get("rhythmic", "")), paras)
        try:
            cur = self.conn.execute(
                "INSERT INTO poems(category,type,era,title,author_id,rhythmic,chapter,section,"
                "paragraphs,tags,notes,hash,source_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                (
                    cat, typ, era, title,
                    self.author(rec.get("author", ""), rec.get("dynasty", ""), rec.get("brief", "")),
                    s(rec.get("rhythmic", "")), s(rec.get("chapter", "")), s(rec.get("section", "")),
                    j(paras), j(rec.get("tags", [])), j(rec.get("notes", [])),
                    h, rec.get("source_id", ""),
                ),
            )
        except sqlite3.IntegrityError:
            self.dups[cat] = self.dups.get(cat, 0) + 1
            return False
        self.counts[cat] = self.counts.get(cat, 0) + 1
        _ = cur.lastrowid
        return True

    def finish(self, label: str, fts: bool = True):
        if fts:
            self.conn.execute(
                "INSERT INTO poems_fts(rowid, paragraphs, title) SELECT id, paragraphs, title FROM poems"
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


def it_laozi(dataset: Path):
    """老子精选章目：corpus/laozi.json（手工校订，D-019/批1）。corpus 在仓库内，不在外部数据集。
    批1 前曾挂在 guwen+chapter 补遗下（书架"古文观止"卡混入 19 章，卡题错位）；现独立分类，
    书架单独成卡；chapter 置空，不进文门书卷两级页。"""
    d = load_json(REPO / "tools/datapack/corpus/laozi.json")
    for sec in d["sections"]:
        yield {
            "category": "laozi", "title": f"老子·{sec['n']}", "author": d["author"],
            "dynasty": d["dynasty"], "paragraphs": sec["paragraphs"],
        }


def it_guwen_extra(dataset: Path):
    """古文观止缺收的课标名篇补遗：corpus/guwen_extra.json（手工校订，D-019）。"""
    d = load_json(REPO / "tools/datapack/corpus/guwen_extra.json")
    for a in d["pieces"]:
        yield {
            "category": "guwen", "title": a["chapter"], "author": a["author"],
            "dynasty": a["dynasty"], "paragraphs": a["paragraphs"],
            "section": a.get("source", ""), "chapter": "补遗",
        }


def it_gushi19(dataset: Path):
    """古诗十九首（批4 陈列层选本，D-010 外部扩容第二例）：corpus/gushi19.json
    ——《古诗源》卷三（清·沈德潜，第 4 首底本脱"今"字已按序位白名单点修）；
    东汉无名氏，五言古诗之祖。文本风险声明同 D-020：真机抽查按点名修正。"""
    d = load_json(REPO / "tools/datapack/corpus/gushi19.json")
    for a in d["pieces"]:
        yield {
            "category": "gushi19", "title": a["title"], "author": "佚名",
            "dynasty": "汉", "paragraphs": a["paragraphs"],
        }


def it_zhuangzi(dataset: Path):
    """庄子（批5，D-010 外部扩容第三例）：内篇七全 + 外篇名篇二，corpus/zhuangzi.json。
    底本=道藏南华经白文本（河上公系传本，有标点）；章题=篇名。"""
    d = load_json(REPO / "tools/datapack/corpus/zhuangzi.json")
    for a in d["pieces"]:
        yield {
            "category": "zhuangzi", "title": a["title"], "author": "庄子",
            "dynasty": a.get("dynasty", "战国"), "paragraphs": a["paragraphs"],
            "chapter": a.get("chapter", ""),
        }


def it_shiji(dataset: Path):
    """史记名段（批5，D-010 外部扩容第四例）：corpus/shiji.json——地标切片，
    段题为编者拟（通行称谓），chapter=卷名。收名段不收整本（D-010 ⑤）。"""
    d = load_json(REPO / "tools/datapack/corpus/shiji.json")
    for a in d["pieces"]:
        yield {
            "category": "shiji", "title": a["title"], "author": "司马迁",
            "dynasty": a.get("dynasty", "汉"), "paragraphs": a["paragraphs"],
            "chapter": a.get("chapter", ""),
        }


def it_shishuo(dataset: Path):
    """世说新语名则（批5，D-010 外部扩容·三书之一）：corpus/shishuo.json——
    选名则、手工通行标点、四库本白文逐字校验；chapter=门名。"""
    d = load_json(REPO / "tools/datapack/corpus/shishuo.json")
    for a in d["pieces"]:
        yield {
            "category": "shishuo", "title": a["title"], "author": "刘义庆",
            "dynasty": a.get("dynasty", "南北朝"), "paragraphs": a["paragraphs"],
            "chapter": a.get("chapter", ""),
        }


def it_nalan(dataset: Path):
    for r in load_json(dataset / "纳兰性德" / "纳兰性德诗集.json"):
        title = r["title"]
        # 题形"牌名·题"——词牌拆入 rhythmic 列（批3 词牌二级页用；全宋词源自带该字段）
        rhythmic = title.partition("·")[0].strip() if "·" in title else ""
        yield {
            "category": "qing_ci", "title": title, "author": r.get("author", "纳兰性德"),
            "dynasty": "清", "paragraphs": r["para"], "rhythmic": rhythmic, "source_id": "",
        }


def it_caocao(dataset: Path):
    for r in load_json(dataset / "曹操诗集" / "caocao.json"):
        yield {
            "category": "han_wei", "title": r["title"], "author": "曹操",
            "dynasty": "汉", "paragraphs": r["paragraphs"],
        }


def _yuanqu_rhythmic(title: str) -> str:
    """元曲曲牌抽取（批3 曲牌二级页）：题形「剧名・宫调/牌名」取末段牌名；
    「剧名・牌名」取尾部；无分隔段 ≤6 字视为牌名（同前/同上由调用方继承前调）。"""
    if "・" in title:
        tail = title.partition("・")[2]
        if "/" in tail:
            tail = tail.rpartition("/")[2]
        if 0 < len(tail) <= 6:
            return tail
    return ""


def it_yuanqu(dataset: Path):
    prev = ""
    # 重复段记法 = 与上一首同调（南戏"同前/前腔"、北套"幺篇"、收尾"尾声"），继承而不立牌
    INHERIT = ("同前", "同上", "同前调", "又一体", "前腔", "幺篇", "尾声", "前腔换头")
    for r in load_json(dataset / "元曲" / "yuanqu.json"):
        title = r["title"]
        rhythmic = _yuanqu_rhythmic(title)
        if rhythmic in INHERIT or rhythmic.startswith(INHERIT):
            rhythmic = prev  # 无论从哪条题面路径抽出，都归并到前调
        if not rhythmic and "・" not in title:
            if title in INHERIT or title.startswith(INHERIT):
                rhythmic = prev
            elif len(title) <= 6:
                rhythmic = title
        prev = rhythmic
        yield {
            "category": "yuan_qu", "title": title, "author": r["author"],
            "dynasty": "元", "paragraphs": r["paragraphs"], "rhythmic": rhythmic,
        }


def it_songci300(dataset: Path):
    """宋词三百首（宋词/宋词三百首.json）：自带 tags，进精选池与书架"""
    p = dataset / "宋词" / "宋词三百首.json"
    if not p.exists():
        print("    [蒙学] 宋词三百首.json 缺失")
        return
    for r in load_json(p):
        yield {
            "category": "ci", "title": r.get("rhythmic", "") or "无题",
            "author": r.get("author", ""), "dynasty": "宋",
            "paragraphs": r["paragraphs"], "rhythmic": r.get("rhythmic", ""),
            "tags": r.get("tags", []),
        }


def it_wudai_ci(dataset: Path):
    """花间集 + 南唐二主词等：结构同形 {title, paragraphs, author, rhythmic, notes}"""
    for p in sorted((dataset / "五代诗词" / "huajianji").glob("huajianji-*.json")):
        for r in load_json(p):
            yield {
                "category": "wudai_ci", "title": r["title"], "author": r["author"],
                "dynasty": "五代", "paragraphs": r["paragraphs"],
                "rhythmic": r.get("rhythmic", ""), "notes": r.get("notes", []),
            }
    nt = dataset / "五代诗词" / "nantang" / "poetrys.json"
    if nt.exists():
        for r in load_json(nt):
            yield {
                "category": "wudai_ci", "title": r["title"], "author": r["author"],
                "dynasty": "五代", "paragraphs": r["paragraphs"],
                "rhythmic": r.get("rhythmic", ""), "notes": r.get("notes", []),
            }


def it_shuimotangshi(dataset: Path):
    p = dataset / "水墨唐诗" / "shuimotangshi.json"
    if not p.exists():
        return
    for r in load_json(p):
        yield {
            "category": "tang_poem", "title": r["title"], "author": r["author"],
            "dynasty": "唐", "paragraphs": r["paragraphs"], "tags": ["水墨唐诗"],
        }


def split_author_dynasty(raw: str) -> tuple[str, str]:
    """「（唐）孟浩然」-> (唐, 孟浩然)；无括号 -> ('', 原文)。（P1-9：原实现把朝代当杂质丢弃）"""
    m = re.match(r"^（([^）]*)）\s*(.*)$", raw)
    if m:
        return m.group(1).strip(), m.group(2).strip()
    return "", raw


def it_qianjiashi(dataset: Path):
    """千家诗：分四卷，卷名即体裁（五言绝句/五言律诗/七言绝句/七言律诗）；组诗（其一/其二）展开为多条。
    author 形如「（唐）孟浩然」——括号内是朝代，提取进 dynasty（原实现整段丢弃致 era 全空，P1-9）。"""
    d = load_json(dataset / "蒙学" / "qianjiashi.json")
    for vol in d["content"]:
        t = s(vol.get("type", ""))
        for r in vol["content"]:
            chapter = s(r.get("chapter", ""))
            dynasty, author = split_author_dynasty(s(r.get("author", "")))
            paras = r.get("paragraphs", [])
            if paras and isinstance(paras[0], dict):
                for sub in paras:
                    sub_t = s(sub.get("subchapter", ""))
                    yield {
                        "category": "qianjiashi",
                        "title": f"{chapter}·{sub_t}" if sub_t else chapter,
                        "author": author, "dynasty": dynasty,
                        "paragraphs": sub.get("paragraphs", []), "type": t,
                    }
            else:
                yield {
                    "category": "qianjiashi", "title": chapter,
                    "author": author, "dynasty": dynasty,
                    "paragraphs": paras, "type": t,
                }


def iter_nested(node):
    """递归产出 (章节标题, 段落列表)：兼容 卷->篇 两层与一层 dict/list 混合。"""
    if isinstance(node, dict):
        yield from iter_nested([node])
        return
    for it in node:
        if not isinstance(it, dict):
            continue
        title = it.get("title") or it.get("chapter") or ""
        paras = it.get("paragraphs") or it.get("content")
        if isinstance(paras, list) and paras and isinstance(paras[0], str):
            yield title, paras
        elif isinstance(paras, list):
            for t2, p2 in iter_nested(paras):
                yield (f"{title}·{t2}" if title and t2 else (t2 or title)), p2


# 蒙学补全：语料里剩余的全部蒙书（文件名是拼音, 书名, 朝代）
# 条例剔除《文字蒙求》：古文字字形字典，546/547 字不在任何可用字体，展示不可行（D-010 条例3）
MENGXUE_BOOKS = [
    ("shenglvqimeng.json", "声律启蒙", "清"),
    ("youxueqionglin.json", "幼学琼林", "明"),
    ("zengguangxianwen.json", "增广贤文", "明"),
    ("zhuzijiaxun.json", "朱子家训", "清"),
    ("qianziwen.json", "千字文", "南北朝"),
    ("dizigui.json", "弟子规", "清"),
    ("baijiaxing.json", "百家姓", "宋"),
]


def it_mengxue_extra(dataset: Path):
    for fname, book, era in MENGXUE_BOOKS:
        p = dataset / "蒙学" / fname
        if not p.exists():
            print(f"    [蒙学] 缺文件: {fname}")
            continue
        d = load_json(p)
        author = d.get("author", "") if isinstance(d.get("author", ""), str) else ""
        body = d.get("content") or d.get("paragraphs")
        if isinstance(body, list) and body and isinstance(body[0], str):
            yield {"category": "mengxue", "title": book, "author": author,
                   "dynasty": era, "paragraphs": body}
        else:
            for chapter, paras in iter_nested(body or []):
                title = f"{book}·{chapter}" if chapter else book
                yield {"category": "mengxue", "title": title, "author": author,
                       "dynasty": era, "paragraphs": paras}


# ---------- 意象标签 v0（循物寻诗）与节气/节日配诗：启发式匹配，先跑通后精修 ----------

# 单字物象：正文字符集命中即打标（快、准）
SINGLE_IMAGERY = "月雪雨云风山江海花柳松竹梅兰菊荷桃雁燕鹤蝉马剑琴酒茶梦愁春秋冬夏夜舟桥楼灯笛箫"

# 复合意象：任意关键词子串命中即打标（启发式，宁滥勿缺，人工精修随阶段推进）
COMPOUND_IMAGERY = {
    "思乡": ["思乡", "乡书", "故乡", "故园", "还乡", "客路", "客心", "归梦"],
    "离别": ["送别", "相送", "离别", "别离", "赠"],
    "边塞": ["边塞", "关山", "胡马", "烽火", "征人", "大漠", "阴山", "玉门关"],
    "田园": ["田园", "村", "耕", "桑", "篱"],
    "隐逸": ["隐", "幽居", "山居", "归去", "闲居"],
    "咏史": ["咏史", "怀古", "兴亡", "六朝", "吴宫"],
    "壮志": ["壮志", "报国", "从军", "封侯"],
    "闺情": ["闺", "罗帐", "玉阶", "红妆", "相思"],
}

# 主题（题材）规则：诗词专用（文/蒙学不参与），关键词子串命中即打标
# 主题词表 v3（批2，四元组）：(细类名, 四门 grp, 标题词, 正文词, 负向词)
#   - 标题词命中即成；正文词需 >=2 处命中（根治单字"酒"扫全库的误伤）；负向词命中即放弃。
#   - v3 变化：新增 春/夏/秋/冬四时 + 写雪/写雨/写月/写花 + 爱国/儿童 共 10 条（12→22 细类，
#     对齐 RESEARCH-2026-10-26 收编蓝本的"高频 22 条"；写人/哲理/咏物细类（梅荷菊柳）延批）；
#     「送别」标题词改前缀制（"^送/^别"），原单字"送/别"全题匹配把全库 7.6% 扫成送别（25449 篇），
#     收窄后 18483 篇（5.5%），样本抽检全部为真离别；"写月"加月份负词（防"十月菊"式记月纪事题）。
#   - 探针台账：tools/datapack/theme_report.py（改前后各跑一次，看计数/样本/覆盖率）。
THEME_RULES = [
    ("山水",     "写景", ("山水", "溪山", "山行", "游山"), ("溪", "峰", "瀑布", "烟霞", "林泉", "山色", "云壑", "青山", "岫", "泉石"), ()),
    ("田园",     "写景", ("田园", "村居", "归园", "归田"), ("耕", "桑", "篱", "炊烟", "牧童", "田家", "禾"), ("塞", "戍", "军")),
    ("春季",     "写景", ("春",),
     ("春风", "春雨", "春色", "春花", "春草", "春柳", "春水", "春寒", "春莺", "春燕",
      "春山", "春夜", "春意", "春归", "残春", "初春", "暮春", "新春", "芳草", "东风"), ()),
    ("夏季",     "写景", ("夏",),
     ("夏日", "夏夜", "夏景", "夏木", "夏云", "夏雨", "榴花", "荷风", "纳凉", "乘凉",
      "避暑", "熏风", "薰风", "绿荫", "竹床", "蒲扇", "浮瓜", "暑", "炎天"), ()),
    ("秋季",     "写景", ("秋",),
     ("秋风", "秋雨", "秋声", "秋色", "秋雁", "秋鸿", "秋花", "秋草", "秋夜", "落叶",
      "西风", "霜", "雁", "菊"), ("秋千",)),
    ("冬季",     "写景", ("冬",),
     ("冬", "隆冬", "寒冬", "岁暮", "岁寒", "朔风", "北风", "冰雪", "腊", "严寒"), ()),
    ("写雪",     "写景", ("雪",), ("雪",), ()),
    ("写雨",     "写景", ("雨",), ("雨",), ("雨霖铃",)),
    ("写月",     "写景", ("月",),
     ("明月", "月光", "月色", "月华", "月明", "嫦娥", "婵娟", "玉兔", "桂魄", "孤月"),
     # 负词：抽象"岁月/日月"；X月纪事题（九月九日/十月菊），八月保留（八月十五赏月题多）
     ("岁月", "日月", "正月", "二月", "三月", "四月", "五月", "六月", "七月", "九月", "十月", "腊月")),
    ("写花",     "写景", ("花",), ("花", "蕊", "瓣", "芳菲", "落英", "群芳", "百花"),
     ("花间集",)),
    ("送别",     "抒怀", ("^送", "^别", "送别", "相送", "别离", "赠别", "留别",
                          "赋别", "惜别", "话别"), ("长亭", "折柳", "劳劳", "南浦", "别离", "歧路"), ()),
    ("思乡",     "抒怀", ("思乡", "乡思", "旅思"), ("乡书", "故乡", "故园", "还乡", "客路", "客心", "归梦", "望乡", "砧"), ()),
    ("相思",     "抒怀", ("相思", "怀人", "忆内"), ("相思", "红豆", "锦书", "两地", "鸿雁", "雁字"), ()),
    ("悼亡",     "抒怀", ("悼亡", "挽歌", "哭"), ("悼亡", "挽", "孤坟", "哀词"), ()),
    ("咏史怀古", "抒怀", ("咏史", "怀古", "览古", "六朝", "金陵"), ("兴亡", "前朝", "六朝", "吴宫", "故垒", "秦淮"), ()),
    ("爱国",     "抒怀", ("爱国", "忧国", "家国"),
     ("报国", "社稷", "汗青", "丹心", "忧国", "家国", "国破", "国恨", "九州",
      "王师", "遗民", "胡尘", "中原", "故国", "神州", "山河"), ()),
    ("闺怨",     "抒怀", ("闺怨", "闺情", "春怨"), ("罗帏", "绣帘", "空闺", "画眉", "深院", "玉阶"), ()),
    ("饮酒",     "咏物", ("饮", "对酒", "醉后", "酒后"), ("酒", "醉", "樽", "酌", "觞", "醅", "盏"), ()),
    ("咏物",     "咏物", (), (), ()),  # 特判：标题以"咏"开头（且非咏史/咏物怀古）单独打
    ("边塞",     "世情", ("塞下", "塞上", "从军", "出塞", "入塞"), ("关山", "胡马", "烽火", "征人", "大漠", "阴山", "玉门", "楼兰", "燕然", "羌笛"), ()),
    ("节令",     "世情", ("人日", "元日", "上元", "清明", "寒食", "重九", "七夕", "中秋", "腊八", "除夕", "冬至"),
     ("春节", "元日", "元宵", "清明", "端午", "七夕", "中秋", "重阳", "除夕", "冬至", "岁暮", "新正", "守岁", "寒食", "立春", "立秋"), ()),
    ("儿童",     "世情", ("^童", "^稚", "^儿", "牧童", "^小娃"),
     ("儿童", "童稚", "稚子", "童子", "孩提", "小娃", "儿曹", "牧童", "纸鸢", "风筝"), ()),
]

POETRY_CATEGORIES = ("tang_poem", "song_poem", "ci", "qing_ci", "wudai_ci", "qianjiashi",
                     "shijing", "chuci", "han_wei", "gushi19")

CALENDAR_KEYWORDS = {
    "solar_term": {
        "立春": ["春", "东风"], "雨水": ["雨"], "惊蛰": ["雷", "蛰"], "春分": ["春分", "燕"],
        "清明": ["清明"], "谷雨": ["谷雨", "茶"], "立夏": ["夏"], "小满": ["麦"],
        "芒种": ["芒种", "麦"], "夏至": ["夏至", "荷"], "小暑": ["暑", "蝉"], "大暑": ["暑"],
        "立秋": ["秋"], "处暑": ["处暑", "凉"], "白露": ["白露", "露"], "秋分": ["秋分", "月"],
        "寒露": ["寒露", "菊"], "霜降": ["霜", "枫"], "立冬": ["冬"], "小雪": ["小雪", "雪"],
        "大雪": ["大雪", "雪"], "冬至": ["冬至", "寒"], "小寒": ["小寒", "寒梅"], "大寒": ["大寒", "冰"],
    },
    "festival": {
        "春节": ["元日", "爆竹", "屠苏", "守岁", "桃符", "迎春"],
        "元宵": ["元宵", "上元", "花灯", "灯市"],
        "清明": ["清明"],
        "端午": ["端午", "端阳", "屈原", "粽", "龙舟", "汨罗"],
        "七夕": ["七夕", "鹊桥", "牵牛", "乞巧"],
        "中秋": ["中秋", "婵娟", "月圆", "桂花"],
        "重阳": ["重阳", "登高", "茱萸"],
        "除夕": ["守岁", "除夕", "岁除", "爆竹"],
        "腊八": ["腊八", "粥"],
    },
}

# 名句种子：(题目关键词, 名句关键词)。命中即把该句登记为该诗的"名句"；
# 未命中的打印跳过。首版覆盖三百首/诗经/楚辞/纳兰（精选库可解析），宋词条目仅全量库生效。
FAMOUS_LINES = [
    ("静夜思", "床前"), ("春晓", "春眠不觉晓"), ("登鹳雀楼", "白日依山尽"),
    ("相思", "红豆生南国"), ("望庐山瀑布", "飞流直下三千尺"), ("早发白帝城", "朝辞白帝彩云间"),
    ("望天门山", "天门中断楚江开"), ("独坐敬亭山", "众鸟高飞尽"), ("春夜喜雨", "好雨知时节"),
    ("绝句", "两个黄鹂鸣翠柳"), ("鸟鸣涧", "人闲桂花落"),
    ("黄鹤楼送孟浩然之广陵", "烟花三月下扬州"), ("赠汪伦", "桃花潭水深千尺"),
    ("春望", "国破山河在"), ("月夜忆舍弟", "露从今夜白"),
    ("登高", "无边落木萧萧下"), ("登岳阳楼", "昔闻洞庭水"), ("望岳", "会当凌绝顶"),
    ("枫桥夜泊", "月落乌啼霜满天"), ("寒食", "春城无处不飞花"), ("滁州西涧", "独怜幽草涧边生"),
    ("塞下曲", "月黑雁飞高"), ("游子吟", "慈母手中线"), ("题都城南庄", "桃花依旧"),
    ("乌衣巷", "旧时王谢堂前燕"), ("望洞庭", "湖光秋月两相和"), ("浪淘沙", "九曲黄河万里沙"),
    ("赋得古原草送别", "离离原上草"), ("暮江吟", "一道残阳铺水中"), ("忆江南", "日出江花红胜火"),
    ("江雪", "千山鸟飞绝"), ("山行", "停车坐爱枫林晚"),
    ("泊秦淮", "烟笼寒水月笼沙"), ("江南春绝句", "千里莺啼"), ("蜂", "采得百花成蜜后"),
    ("回乡偶书", "少小离家老大回"), ("咏柳", "碧玉妆成一树高"),
    ("凉州词", "黄河远上白云间"), ("凉州词", "葡萄美酒夜光杯"), ("出塞", "秦时明月汉时关"),
    ("从军行", "青海长云暗雪山"), ("芙蓉楼送辛渐", "一片冰心在玉壶"), ("别董大", "莫愁前路无知己"),
    ("渭城曲", "渭城朝雨"), ("九月九日忆山东兄弟", "每逢佳节倍思亲"),
    ("竹里馆", "独坐幽篁里"), ("鹿柴", "空山不见人"),
    ("山居秋暝", "空山新雨后"), ("使至塞上", "大漠孤烟直"), ("终南别业", "行到水穷处"),
    ("黄鹤楼", "日暮乡关何处是"), ("次北固山下", "海日生残夜"),
    ("将进酒", "君不见黄河之水"), ("琵琶引", "同是天涯沦落人"),
    ("关雎", "关关雎鸠"), ("蒹葭", "蒹葭苍苍"), ("桃夭", "桃之夭夭"), ("采薇", "昔我往矣"),
    ("离骚", "路漫漫"),
    # 古文观止名段（文类名段：今日页亮选段，详情见全文）
    ("兰亭集序", "永和九年"), ("桃花源记", "晋太元中"), ("归去来兮辞", "田园将芜"),
    ("归去来兮辞", "悟已往之不谏"), ("滕王阁序", "落霞与孤鹜"), ("岳阳楼记", "先天下之忧而忧"),
    ("醉翁亭记", "醉翁之意不在酒"), ("师说", "古之学者必有师"), ("马说", "世有伯乐"),
    ("陋室铭", "山不在高"), ("爱莲说", "出淤泥而不染"), ("小石潭记", "青树翠蔓"),
    ("赤壁赋", "清风徐来"), ("后赤壁赋", "霜露既降"), ("出师表", "先帝创业未半"),
    ("报任安书", "人固有一死"), ("过秦论", "秦孝公据崤函"), ("阿房宫赋", "六王毕，四海一"),
    ("郑伯克段于鄢", "多行不义"), ("曹刿论战", "一鼓作气"), ("邹忌讽齐王纳谏", "吾妻之美我者"),
    ("冯谖客孟尝君", "长铗归来乎"), ("触龙说赵太后", "父母之爱子"), ("谏太宗十思疏", "居安思危"),
    ("答司马谏议书", "而议事每不合"), ("读孟尝君传", "世皆称孟尝君能得士"),
    ("游褒禅山记", "世之奇伟"), ("记承天寺夜游", "积水空明"), ("喜雨亭记", "亭以雨名"),
    ("超然台记", "凡物皆有可观"), ("六国论", "不知天下之势"), ("伶官传序", "忧劳可以兴国"),
    ("秋声赋", "欧阳子方夜读书"), ("五柳先生传", "先生不知何许人"), ("与朱元思书", "风烟俱净"),
    ("三峡", "自三峡七百里中"), ("答谢中书书", "山川之美"), ("卜居", "千里之驹"),
    ("渔父", "沧浪之水"), ("祭十二郎文", "吾年未四十"), ("柳子厚墓志铭", "士穷乃见节义"),
    ("原道", "博爱之谓仁"), ("原毁", "其责己也重以周"), ("进学解", "业精于勤"),
    ("送孟东野序", "不得其平则鸣"), ("送李愿归盘谷序", "大丈夫之遇知于天子"),
    ("相州昼锦堂记", "仕宦而至将相"), ("卖柑者言", "金玉其外"), ("五人墓碑记", "亦以明死生之大"),
    ("深虑论", "虑天下者"), ("瘗旅文", "连峰际天"), ("梅圣俞诗集序", "穷者而后工"),
    ("吊古战场文", "浩浩乎"), ("讨武曌檄", "一抔之土"), ("与韩荆州书", "生不用封万户侯"),
    ("谏逐客书", "臣闻吏议逐客"), ("画堂春", "一生一代一双人"),
    ("货殖列传序", "仓廪实而知礼节"), ("滑稽列传", "一鸣惊人"), ("伯夷列传", "天道无亲"),
    ("长相思", "山一程"), ("木兰花令", "人生若只如初见"), ("画堂春", "一生一代一双人"),
    # 全量库才有的宋词/宋诗
    ("水调歌头", "明月几时有"), ("念奴娇", "大江东去"), ("雨霖铃", "执手相看泪眼"),
    ("声声慢", "寻寻觅觅"), ("满江红", "怒发冲冠"), ("钗头凤", "红酥手"),
    ("鹊桥仙", "金风玉露一相逢"), ("破阵子", "醉里挑灯看剑"), ("永遇乐", "千古江山"),
    ("青玉案", "蓦然回首"), ("卜算子", "零落成泥碾作尘"), ("游山西村", "山重水复疑无路"),

    # ---------- P1-9 扩容（D-019）：经典短句 + 清降配名句精选 + 古文补遗 ----------
    # 四书五经短句（篇目整类在库；题锚用篇名——title 不含书名，P1-9 修正）
    ("学而篇", "学而时习之"), ("为政篇", "温故而知新"), ("里仁篇", "德不孤"),
    ("雍也篇", "知者乐水"), ("述而篇", "必有我师"), ("泰伯篇", "士不可以不弘毅"),
    ("子罕篇", "逝者如斯"), ("卫灵公篇", "己所不欲"),
    ("梁惠王上", "老吾老"), ("公孙丑上", "恻隐之心"), ("滕文公下", "富贵不能淫"),
    ("告子上", "我所欲也"), ("告子下", "生于忧患"), ("尽心上", "穷则独善其身"),
    ("大学", "大学之道"), ("大学", "日日新"), ("大学", "止于至善"),
    ("中庸", "天命之谓性"), ("中庸", "博学之"), ("中庸", "慎其独"),
    # 老子精选（corpus/laozi.json 新篇目）
    ("老子·第一章", "道可道"), ("老子·第八章", "上善若水"), ("老子·第九章", "金玉满堂"),
    ("老子·第十六章", "致虚极"), ("老子·第二十二章", "曲则全"), ("老子·第二十四章", "企者不立"),
    ("老子·第三十三章", "知人者智"), ("老子·第三十六章", "柔弱胜刚强"), ("老子·第四十章", "反者道之动"),
    ("老子·第四十一章", "大音希声"), ("老子·第四十四章", "知足不辱"), ("老子·第四十六章", "祸莫大于不知足"),
    ("老子·第五十八章", "祸兮福之所倚"), ("老子·第六十三章", "报怨以德"), ("老子·第六十四章", "千里之行"),
    ("老子·第七十三章", "天网恢恢"), ("老子·第七十六章", "柔弱者生之徒"), ("老子·第七十八章", "受国之垢"),
    ("老子·第八十一章", "信言不美"),
    # 纳兰性德精选（清词降配：qing_ci 258 整类 -> 名句种子制进池，D-019）
    ("浣溪沙", "谁念西风独自凉"), ("浣溪沙", "身向云山那畔行"),
    ("蝶恋花", "辛苦最怜天上月"), ("蝶恋花", "又到绿杨曾折处"),
    ("采桑子", "谁翻乐府凄凉曲"), ("采桑子", "明月多情应笑我"),
    ("临江仙", "飞絮飞花何处是"), ("如梦令", "万帐穹庐人醉"), ("如梦令", "正是辘轳金井"),
    ("菩萨蛮", "朔风吹散三更雪"), ("菩萨蛮", "问君何事轻离别"),
    ("南乡子", "泪咽却无声"),
    ("减字木兰花", "相逢不语"), ("清平乐", "风鬟雨鬓"),
    ("虞美人", "曲阑深处重相见"),
    # 幽梦影精选（219 则 -> 名段种子制进池）
    ("幽梦影", "读经宜冬"), ("幽梦影", "经传宜独坐读"), ("幽梦影", "隙中窥月"),
    ("幽梦影", "艺花可以邀蝶"), ("幽梦影", "楼上看山"), ("幽梦影", "天下有一人知己"),
    ("幽梦影", "梅边之石宜古"), ("幽梦影", "藏书不难"), ("幽梦影", "文章是案头之山水"),
    ("幽梦影", "才子而富贵"), ("幽梦影", "傲骨不可无"), ("幽梦影", "能闲世人之所忙者"),
    ("幽梦影", "春听鸟声"), ("幽梦影", "上元须酌豪友"), ("幽梦影", "雨之为物"),
    ("幽梦影", "因雪想高士"), ("幽梦影", "花不可见其落"),
    ("幽梦影", "貌有丑而可观者"),
    # 五代词精选（wudai_ci 543 -> 名句种子制进池；冯延巳不在数据集，见 D-019 遗留）
    ("虞美人", "春花秋月何时了"), ("浪淘沙", "帘外雨潺潺"), ("浪淘沙", "往事只堪哀"),
    ("相见欢", "无言独上西楼"), ("相见欢", "林花谢了春红"), ("乌夜啼", "昨夜风兼雨"),
    ("破阵子", "四十年来家国"), ("清平乐", "别来春半"), ("望江南", "多少恨"),
    ("菩萨蛮", "花明月暗笼轻雾"), ("捣练子", "深院静"),
    ("摊破浣溪沙", "菡萏香销翠叶残"),
    ("谒金门", "风乍起"), ("菩萨蛮", "人人尽说江南好"),
    ("菩萨蛮", "洛阳城里春光好"), ("女冠子", "四月十七"), ("思帝乡", "春日游"),
    ("菩萨蛮", "小山重叠金明灭"), ("梦江南", "梳洗罢"), ("临江仙", "金锁重门荒苑静"),
    # 古文补遗（guwenguanzhi.json 缺收的课标名篇）
    ("伤仲永", "泯然众人"), ("湖心亭看雪", "雾凇沆砀"), ("三峡", "两岸连山"),
    # 古诗十九首（批4 陈列层）：先秦至汉经典，种子走 corpus/gushi19.json 文本锚点
    ("行行重行行", "与君生别离"), ("青青河畔草", "盈盈楼上女"),
    ("青青陵上柏", "忽如远行客"), ("今日良宴会", "人生寄一世"),
    ("西北有高楼", "但伤知音稀"), ("涉江采芙蓉", "忧伤以终老"),
    ("明月皎夜光", "弃我如遗迹"), ("庭中有奇树", "路远莫致之"),
    ("迢迢牵牛星", "脉脉不得语"), ("东城高且长", "燕赵多佳人"),
    ("驱车上东门", "人生忽如寄"), ("去者日以疏", "古墓犁为田"),
    ("生年不满百", "为乐当及时"), ("凛凛岁云暮", "锦衾遗洛浦"),
    ("孟冬寒气至", "愁多知夜长"), ("明月何皎皎", "照我罗床帏"),
    # 老子补全章的常引句（批4：稳定锚点点到即止；主体靠搜索/书架到达）
    ("老子·第六十四章", "千里之行"), ("老子·第七十七章", "天之道"),
    ("老子·第八十章", "小国寡民"), ("老子·第七十章", "知我者希"),
    # ---------- 三书名段种子（批5/D-024：庄子·史记·世说，锚点=标题子串+正文子串） ----------
    ("庄子·逍遥游", "鲲之大"), ("庄子·逍遥游", "藐姑射"),
    ("庄子·齐物论", "梦为胡蝶"), ("庄子·养生主", "文惠君"),
    ("庄子·大宗师", "相濡以沫"), ("庄子·秋水", "濠梁"),
    ("庄子·秋水", "井蛙"), ("庄子·至乐", "鼓盆"),
    ("垓下之围", "楚歌"), ("鸿门宴", "项庄拔剑"),
    ("乌江自刎", "何面目见之"), ("桃李不言", "桃李不言"),
    ("毛遂自荐", "三寸之舌"), ("完璧归赵", "怒发上冲冠"),
    ("太史公赞", "高山仰止"), ("大风歌", "大风起兮"),
    ("徙木立信", "予五十金"),
    ("言语·", "小时了了"), ("言语·", "柳絮因风"),
    ("言语·", "木犹如此"), ("言语·", "克复神州"),
    ("雅量·", "坦腹"), ("雅量·", "大破贼"),
    ("文学·", "相煎何太急"), ("捷悟·", "绝妙好辞"),
    ("伤逝·", "人琴俱亡"), ("任诞·", "乘兴而行"),
    ("自新·", "朝闻夕死"), ("俭啬·", "钻其核"),
    ("惑溺·", "卿卿"), ("假谲·", "梅林"),
    # ---------- 名句表扩容（D-024，点名录入第二批；锚点=标题子串+正文子串，逐条真库命中） ----------
    # 唐 · 名篇名联
    ("登幽州台歌", "前不见古人"), ("春江花月夜", "海上明月"),
    ("将进酒", "天生我材"), ("行路难", "直挂云帆"),
    ("梦游天姥吟留别", "摧眉折腰"), ("蜀道难", "难于上青天"),
    ("茅屋为秋风所破歌", "大庇天下寒士"), ("闻官军收河南河北", "漫卷诗书"),
    ("江南逢李龟年", "落花时节"), ("逢入京使", "马上相逢"),
    ("白雪歌送武判官归京", "万树梨花"), ("送杜少府之任蜀州", "海内存知己"),
    ("望月怀远", "海上生明月"), ("过华清宫", "一骑红尘"),
    ("遣怀", "十年一觉"), ("寄扬州韩绰判官", "二十四桥"),
    ("赠别", "春风十里"), ("锦瑟", "庄生晓梦"),
    ("锦瑟", "此情可待"), ("夜雨寄北", "巴山夜雨"),
    ("无题", "心有灵犀"), ("无题", "春蚕到死"),
    ("雁门太守行", "黑云压城"), ("赤壁", "铜雀春深"),
    ("登乐游原", "夕阳无限好"), ("钱塘湖春行", "早莺争暖树"),
    ("卖炭翁", "满面尘灰"), ("问刘十九", "天欲雪"),
    ("大林寺桃花", "芳菲尽"), ("长恨歌", "比翼鸟"),
    ("长恨歌", "回眸一笑"),
    # 宋诗
    ("题西林壁", "庐山真面目"), ("泊船瓜洲", "明月何时照我还"),
    ("元日", "新桃换旧符"), ("梅花", "暗香来"),
    ("登飞来峰", "身在最高层"), ("饮湖上初晴后雨", "淡妆浓抹"),
    ("惠崇春江晚景", "鸭先知"), ("六月二十七日望湖楼醉书", "黑云翻墨"),
    ("示儿", "告乃翁"),
    ("游园不值", "红杏出墙来"), ("小池", "小荷才露"),
    ("晓出净慈寺送林子方", "映日荷花"), ("春日", "万紫千红"),
    ("观书有感", "源头活水来"), ("乡村四月", "绿遍山原"),
    ("宿新市徐公店", "追黄蝶"),
    ("雪梅", "三分白"), ("题临安邸", "杭州作汴州"),
    ("过零丁洋", "自古谁无死"), ("山园小梅", "暗香浮动"),
    ("约客", "处处蛙"), ("秋夜将晓出篱门迎凉有感", "遗民泪尽"),
    ("十一月四日风雨大作", "铁马冰河"), ("病起书怀", "位卑未敢"),
    ("四时田园杂兴", "昼出耘田"),
    # 宋词
    ("如梦令", "浓睡不消残酒"), ("一剪梅", "红藕香残"),
    ("醉花阴", "黄花瘦"), ("武陵春", "载不动"),
    ("声声慢", "愁字了得"), ("水调歌头", "悲欢离合"),
    ("念奴娇", "人生如梦"), ("江城子", "十年生死"),
    ("江城子", "老夫聊发"), ("渔家傲", "浊酒一杯"),
    ("苏幕遮", "碧云天"), ("浣溪沙", "无可奈何"),
    ("蝶恋花", "槛菊愁烟"), ("鹊桥仙", "久长时"),
    ("虞美人", "几多愁"), ("浪淘沙", "流水落花"),
    ("雨霖铃", "千种风情"),
    # 诗经 / 楚辞 / 汉魏 / 元曲
    ("子衿", "悠悠我心"), ("击鼓", "死生契阔"),
    ("无衣", "与子同袍"), ("鹿鸣", "呦呦鹿鸣"),
    ("黍离", "中心摇摇"), ("月出", "月出皎兮"),
    ("硕鼠", "硕鼠硕鼠"), ("伐檀", "坎坎伐檀"),
    ("芣苡", "采采芣苡"),
    ("离骚", "九死其犹未悔"), ("湘夫人", "袅袅兮秋风"),
    ("少司命", "悲莫悲兮"),
    ("短歌行", "对酒当歌"), ("龟虽寿", "老骥伏枥"),
    ("观沧海", "星汉灿烂"),
    ("天净沙", "枯藤老树"), ("山坡羊", "宫阙万间"),
]


def fill_birth_death(conn):
    n = 0
    for name, birth, death in BIRTH_DEATH:
        cur = conn.execute(
            "UPDATE authors SET birth_year = ?, death_year = ? WHERE name = ?",
            (birth, death, name),
        )
        n += cur.rowcount
    print(f"  生卒年回填: {n} 位作者")


def backfill_era(conn):
    """era 空 && 作者朝代可映射 -> 按作者朝代回填（千家诗等源缺朝代的篇目，P1-9）。"""
    rows = conn.execute(
        "SELECT p.id, a.dynasty FROM poems p JOIN authors a ON a.id = p.author_id "
        "WHERE IFNULL(p.era,'')='' AND IFNULL(a.dynasty,'')<>''"
    ).fetchall()
    for pid, dyn in rows:
        conn.execute("UPDATE poems SET era=? WHERE id=?", (ERA_MAP.get(dyn, dyn), pid))
    print(f"  era 回填: {len(rows)} 篇（按作者朝代）")


def extract_two_clause(paragraph: str) -> str | None:
    """从一段里抽「两句型」名句（X，Y。）：恰一个逗号、两侧等长、≥4 字。
    「两句类型（一个'，'算一句）」为今日页名句的标准形态（用户定案）。"""
    import re as _re

    parts = _re.split(r"(?<=[。！？])", paragraph)
    for seg in parts:
        seg = seg.strip(" 　")
        if not seg or "，" not in seg:
            continue
        seg = seg + ("。" if not seg.endswith(("。", "！", "？")) else "")
        left, _, right = seg[:-1].partition("，")
        l, r = left.strip(" 　"), right.strip(" 　")
        if len(l) == len(r) and len(l) >= 4 and not any(c in "，,、；;：:" for c in l + r):
            return seg
    return None


def classify_genre(title: str) -> str:
    """古文文体 13 类（《古文辞类纂》框架；杂记兜底）——古文观止 222 篇标注（D-011 欠账项）。
    标题判定优先序：辞赋→哀祭→诏令→碑志→传状→箴铭→颂赞→赠序/序跋→奏议→书说→论辨→杂记。"""
    t = title.strip()
    if not t:
        return "杂记"
    if t.endswith(("赋", "骚")):
        return "辞赋"
    if any(k in t for k in ("祭", "吊", "哀词", "哀辞", "殇")):
        return "哀祭"
    if any(k in t for k in ("诏", "令", "檄", "誓", "移")):
        return "诏令"
    if any(k in t for k in ("碑", "墓表", "墓志", "圹志", "阡表")):
        return "碑志"
    if t.endswith("传") or "行状" in t or "事略" in t:
        return "传状"
    if t.endswith(("铭", "箴")):
        return "箴铭"
    if t.endswith(("颂", "赞")):
        return "颂赞"
    if t.endswith("序"):
        if t.startswith(("送", "赠")):
            return "赠序"
        return "序跋"
    if t.endswith(("表", "疏", "策", "对", "议", "章")):
        return "奏议"
    if len(t) > 1 and t.endswith("书"):
        return "书说"
    if t.endswith(("论", "说", "辨", "辩", "驳", "解")):
        return "论辨"
    if t.endswith("记"):
        return "杂记"
    return "杂记"


def fill_genre(conn):
    rows = conn.execute("SELECT id, title FROM poems WHERE category='guwen'").fetchall()
    for pid, title in rows:
        conn.execute("UPDATE poems SET genre=? WHERE id=?", (classify_genre(title), pid))
    n = len(rows)
    # 分布统计（打印给建库日志）
    dist = dict(conn.execute("SELECT genre, count(*) FROM poems WHERE category='guwen' GROUP BY genre ORDER BY 2 DESC"))
    print(f"  古文文体 13 类标注: {n} 篇 | 分布 {dist}")


# 通行本异文手工覆盖（谨慎清单，逐条一正一负验证；不动整体校勘）——用户读感"与课本不一致"
TEXT_OVERRIDES = [
    ("静夜思", "床前看月光", "床前明月光"),
    ("静夜思", "举头望山月", "举头望明月"),
    ("江南春绝句", "千里莺啼绿映江", "千里莺啼绿映红"),
    ("枫桥夜泊", "江枫渔父", "江枫渔火"),  # 通行本作"渔火"（唐诗三百首源为"渔父"）；全唐诗另一行已同作"渔火"
    ("泊船瓜洲", "自绿江南岸", "又绿江南岸"),  # 千家诗源异文"自绿"；通行"又绿"（批5/D-024）
    ("鹊桥仙", "长久时", "久长时"),  # 全宋词秦观《鹊桥仙》源"长久时"；通行"久长时"（批5/D-024）
]

# 标题入库修正（谨慎清单）：源数据错字逐条点名，不做模糊匹配 —— P1-2
TITLE_FIXES = [
    ("在岳咏蝉", "在狱咏蝉"),  # 唐诗三百首源数据错字（骆宾王五言律诗）
    ("杜少府之任蜀州", "送杜少府之任蜀州"),  # 千家诗/唐诗三百首源题脱"送"字（批5/D-024）
    ("钱唐湖春行", "钱塘湖春行"),  # 千家诗源"钱唐"湖；通行"钱塘"（批5/D-024）
    ("晓出浄慈送林子方二首·其二", "晓出净慈寺送林子方·其二"),  # 千家诗源：浄变体+脱"寺"（批5/D-024）
]

_ORD = "一二三四五六七八九十百"


def norm_title(title: str) -> str:
    """组诗分篇标题归一（P1-4，D-014）：「出塞 一」「咏蝉  其一」→「出塞·其一」；
    题内剩余空格并作「·」（乐府题"杂曲歌辞 踏歌行"→"杂曲歌辞·踏歌行"）。
    组诗热度合并的正则与名句补种的排除规则都依赖"·其N"形态，改这里须同步 smooth_grouped_popularity。"""
    t = title
    m = re.search(r"\s+其?([" + _ORD + r"]+)$", t)
    if m and m.start() > 0:
        t = t[:m.start()] + "·其" + m.group(1)
    return t.replace(" ", "·").replace("　", "·")


def clean_placeholder_title(title: str) -> str:
    """缺字占位标题归一：数据集以「□」(U+25A1) 标记碑损/阙佚之字，整题皆□在界面
    形同渲染豆腐块（用户报障"部分显示方框"）。只处理纯占位：题根全为□ → 「佚题」，
    保留「·其后缀」（组诗序号或词调名，如 □□□・七娘子 → 佚题·七娘子）；
    部分缺字（如「□后圃成三绝句」「元□□」）保留校勘原貌，不虚构内容。
    注意：改名后不再命中名句池的 '%□%' 排除，须同步排除「佚题%」。
    P2-5：空题根——元曲散套以引句/序数作题时题根为空，入库后残留「·」头
    （如「·夕阳一带山如画」），剥去行头分隔符保留引句原貌。"""
    if set(title) <= {"□", "　", " "}:
        return "佚题"
    stripped = title.lstrip("·・")  # P2-5：空题根去「·」头
    if not stripped and set(title) - {"·", "・"}:
        return "佚题"
    if not stripped:
        return "佚题"
    head = re.split(r"[·・]", stripped, maxsplit=1)[0]
    if head and set(head) <= {"□"}:
        return "佚题·" + stripped[len(head) + 1:]
    return stripped


def clean_brief(brief: str) -> str:
    """诗人小传去重（P0-3）：维基文库 desc 是两种句读版本以连续空格连排，去标点后同文只留一份；
    真正不同的段落以「；」连接，不再裸露空格串。"""
    parts = [p.strip() for p in re.split(r"\s{2,}|\u3000+", brief or "") if p.strip()]
    out: list[str] = []
    seen: set[str] = set()
    for p in parts:
        key = re.sub(r"[^\u4e00-\u9fff]", "", p)
        if not key or key in seen:
            continue
        seen.add(key)
        out.append(p)
    return "；".join(out) if out else brief


def apply_text_overrides(conn):
    n = 0
    for title, old, new in TEXT_OVERRIDES:
        rows = conn.execute("SELECT id, paragraphs FROM poems WHERE title = ?", (title,)).fetchall()
        for pid, paras_json in rows:
            if old not in paras_json:
                continue
            paras = json.loads(paras_json)
            paras = [p.replace(old, new) for p in paras]
            conn.execute("UPDATE poems SET paragraphs=? WHERE id=?", (j(paras), pid))
            n += 1
    print(f"  异文覆盖: {n} 处（{[(t, o, nw) for t, o, nw in TEXT_OVERRIDES]}）")


def smooth_grouped_popularity(conn):
    """组诗热度合并（保守抗噪）：仅对"同分类 + 同作者 + 标题去掉'  其N'后缀后相同"的组，
    把各分篇 popularity 统一为组内最大值。只平滑组诗分裂，不改任何其他数；单篇组零改动。"""
    import re as _re

    rows = conn.execute(
        "SELECT p.id, p.category, IFNULL(a.name,''), p.title, p.popularity FROM poems p "
        "LEFT JOIN authors a ON a.id = p.author_id WHERE p.title LIKE '%其%'"
    ).fetchall()
    groups: dict[tuple, list[int]] = {}
    pops: dict[int, int] = {}
    for pid, cat, author, title, pop in rows:
        base = _re.sub(r"[·\s]*其[一二三四五六七八九十百]+$", "", title.strip())  # 兼容归一后的「·其N」与旧式空格（D-014）
        if base == title.strip():
            continue  # 非组诗分篇（正文含"其"而非分篇标题），不动
        groups.setdefault((cat, author, base), []).append(pid)
        pops[pid] = pop
    updated = 0
    for members in groups.values():
        if len(members) < 2:
            continue
        top = max(pops[pid] for pid in members)
        if top <= 0 or all(pops[pid] == top for pid in members):
            continue
        for pid in members:
            conn.execute("UPDATE poems SET popularity=? WHERE id=?", (top, pid))
        updated += len(members)
    print(f"  组诗热度合并: {len(groups)} 组，改 {updated} 行")


def two_clauses_around(paragraph: str, hint: str) -> str | None:
    """长散文段抽不出标准两句型时，以含锚句的分句为中心截两句（P1-9）：
    爱莲说锚"出淤泥而不染" -> 「予独爱莲之出淤泥而不染，濯清涟而不妖，」。"""
    if not paragraph or hint not in paragraph:
        return None
    parts = [p for p in re.split(r"(?<=[，。！？；：])", paragraph) if p.strip()]
    idx = next((i for i, p in enumerate(parts) if hint in p), None)
    if idx is None:
        return None
    if len(parts) == 1:
        return paragraph
    pair = [parts[idx], parts[idx + 1]] if idx + 1 < len(parts) else [parts[idx - 1], parts[idx]]
    out = "".join(pair).strip()
    return out if len(out) >= 6 else None


def fill_famous_lines(conn):
    n = 0
    # 第一轮：手写种子（优先；line=sentence anchor）
    for title_hint, line_hint in FAMOUS_LINES:
        row = conn.execute(
            "SELECT id, paragraphs FROM poems WHERE title LIKE ? AND paragraphs LIKE ? ORDER BY id LIMIT 1",
            (f"%{title_hint}%", f"%{line_hint}%"),
        ).fetchone()
        if not row:
            print(f"    [名句] 未命中（跳过）: {title_hint} / {line_hint}")
            continue
        pid, paras_json = row
        paras = json.loads(paras_json)
        # 优选含种子句的段落；两句型不足时回退整段（名句宁长勿缺）
        anchor = next((p for p in paras if line_hint in p), None)
        pair = extract_two_clause(anchor or "")
        if not pair or line_hint not in pair:
            # 抽取失败或抽出的两句不含锚（长散文段）——以锚句为中心截两句
            pair = two_clauses_around(anchor or "", line_hint) or pair
        if not pair:
            pair = next((extract_two_clause(p) for p in paras if extract_two_clause(p)), None)
            pair = pair or anchor
        if not pair or len(pair) < 6:
            continue
        conn.execute(
            "INSERT OR IGNORE INTO famous_lines(poem_id, line, source) VALUES (?,?,'manual')",
            (pid, pair),
        )
        n += 1
    seeded = n
    # 第二轮：popularity 自动补种到目标量 —— 陈列层优先，全藏层高热度诗只做末位补充
    TARGET = 400
    CURATED_CATS = "('shijing','chuci','qing_ci','youmengying','lunyu','sishu','han_wei','qianjiashi','wudai_ci','guwen','laozi','gushi19','zhuangzi','shishuo','shiji','mengxue')"
    rank = conn.execute(
        "SELECT id FROM poems WHERE title NOT LIKE '%其%' AND title NOT LIKE '%□%' AND title NOT LIKE '佚题%' "  # 组诗分篇/缺字题/佚题不入名句池
        "ORDER BY "
        f"CASE WHEN category IN {CURATED_CATS} OR tags LIKE '%\"唐诗三百首\"%' THEN 0 ELSE 1 END, "
        "popularity DESC NULLS LAST, id LIMIT 3000"
    ).fetchall()
    for (pid,) in rank:
        if n >= TARGET:
            break
        if conn.execute("SELECT 1 FROM famous_lines WHERE poem_id = ?", (pid,)).fetchone():
            continue
        row = conn.execute("SELECT paragraphs FROM poems WHERE id = ?", (pid,)).fetchone()
        paras = json.loads(row[0])
        pair = next((extract_two_clause(p) for p in paras if extract_two_clause(p)), None)
        if not pair or len(pair) < 10 or len(pair) > 40:
            continue
        conn.execute(
            "INSERT OR IGNORE INTO famous_lines(poem_id, line, source) VALUES (?,?,'auto')",
            (pid, pair),
        )
        n += 1
    auto = n - seeded
    print(f"  名句表: 种子 {seeded} + 自动补 {auto} = {n} 条（两句型标准）")


def annotate(conn):
    """单遍扫描正文：打意象标签 + 主题标签 + 填 calendar_map。"""
    # 意象词表
    names = list(SINGLE_IMAGERY) + list(COMPOUND_IMAGERY)
    for n in names:
        conn.execute("INSERT OR IGNORE INTO imagery(name) VALUES (?)", (n,))
    name_ids = {n: i for i, n in conn.execute("SELECT id, name FROM imagery")}

    # 主题词表（含四门 grp）
    grp_of = {n: grp for n, grp, *_ in THEME_RULES}
    theme_names = list(grp_of)
    for n in theme_names:
        conn.execute(
            "INSERT INTO themes(name, grp) VALUES (?,?) ON CONFLICT(name) DO UPDATE SET grp=excluded.grp",
            (n, grp_of[n]),
        )
    theme_ids = {n: i for i, n in conn.execute("SELECT id, name FROM themes")}

    poetry_cats = set(POETRY_CATEGORIES)
    cal_keys = [(kind, key, kws) for kind, m in CALENDAR_KEYWORDS.items() for key, kws in m.items()]
    cal_hits: dict[tuple, list[int]] = {}

    pi_rows = []
    pt_rows = []
    for pid, title, paras_json, cat in conn.execute("SELECT id, title, paragraphs, category FROM poems"):
        # 瘦身（SCHEMA 7）：body 列已删，正文由 paragraphs 还原（与 Kotlin Poem.body 同构）
        body = "\n".join(json.loads(paras_json))
        chars = set(body)
        hits = [SINGLE_IMAGERY[i] for i, ch in enumerate(SINGLE_IMAGERY) if ch in chars]
        hits += [name for name, kws in COMPOUND_IMAGERY.items() if any(k in body for k in kws)]
        for h in hits:
            pi_rows.append((pid, name_ids[h]))
        if cat in poetry_cats:
            theme_hits = theme_hits_for(title, body)
            for t in theme_hits:
                pt_rows.append((pid, theme_ids[t]))
            for kind, key, kws in cal_keys:
                ok = False
                for k in kws:
                    if len(k) == 1:
                        if k in chars:
                            ok = True
                            break
                    elif k in body:
                        ok = True
                        break
                if ok and len(cal_hits.get((kind, key), [])) < 20:
                    cal_hits.setdefault((kind, key), []).append(pid)

    conn.executemany("INSERT OR IGNORE INTO poem_imagery(poem_id, imagery_id) VALUES (?,?)", pi_rows)
    conn.executemany("INSERT OR IGNORE INTO poem_theme(poem_id, theme_id) VALUES (?,?)", pt_rows)

    # 节气/节日配诗池（D-018 遗留：池自 id 序抽取偏唐）——时代混合再截 20：
    # 全量候选按朝代分桶轮转抽 20 首，桶内保持 id 序；先秦「经典整类」 dni等比例自然进入。
    era_of = dict(conn.execute("SELECT id, IFNULL(era,'') FROM poems"))
    ERA_SEQ = ("先秦", "汉", "魏晋", "南北朝", "隋", "唐", "五代", "宋", "元", "明", "清")
    for (kind, key), ids in cal_hits.items():
        buckets: dict[str, list[int]] = {}
        for pid in ids:  # ids 天然 id 序（扫描序）
            buckets.setdefault(era_of.get(pid, ""), []).append(pid)
        order = [e for e in ERA_SEQ if e in buckets] + sorted(set(buckets) - set(ERA_SEQ))
        picked: list[int] = []
        idx = {e: 0 for e in order}
        while len(picked) < 20 and buckets:
            added = False
            for e in order:
                if idx[e] < len(buckets[e]):
                    picked.append(buckets[e][idx[e]])
                    idx[e] += 1
                    added = True
                    if len(picked) >= 20:
                        break
            if not added:
                break
        for pid in picked:
            conn.execute(
                "INSERT INTO calendar_map(kind, key, name, poem_id) VALUES (?,?,?,?)",
                (kind, key, key, pid),
            )


# ---------- 组装 ----------

CORE_SOURCES = [
    ("唐诗三百首", lambda ds: it_tang300(ds, author_desc(ds, "authors.tang.json"))),
    ("千家诗", lambda ds: it_qianjiashi(ds)),
    ("宋词三百首", lambda ds: it_songci300(ds)),
    ("诗经", lambda ds: it_shijing(ds)),
    ("楚辞", lambda ds: it_chuci(ds)),
    ("五代词", lambda ds: it_wudai_ci(ds)),
    ("论语", lambda ds: it_chapters(ds, "论语/lunyu.json", "lunyu", "先秦")),
    ("四书五经", lambda ds: it_chapters(ds, "四书五经/mengzi.json", "sishu", "战国")),
    ("四书五经", lambda ds: it_chapters(ds, "四书五经/daxue.json", "sishu", "先秦")),
    ("四书五经", lambda ds: it_chapters(ds, "四书五经/zhongyong.json", "sishu", "先秦")),
    ("古文观止", lambda ds: it_guwen(ds)),
    ("古文补遗", lambda ds: it_guwen_extra(ds)),
    ("老子", lambda ds: it_laozi(ds)),
    ("古诗十九首", lambda ds: it_gushi19(ds)),
    ("庄子", lambda ds: it_zhuangzi(ds)),
    ("史记", lambda ds: it_shiji(ds)),
    ("世说新语", lambda ds: it_shishuo(ds)),
    ("幽梦影", lambda ds: it_youmengying(ds)),
    ("纳兰词", lambda ds: it_nalan(ds)),
    ("曹操诗集", lambda ds: it_caocao(ds)),
    ("蒙学补全", lambda ds: it_mengxue_extra(ds)),
]

FULL_EXTRA_SOURCES = [
    ("全唐诗", lambda ds, d: it_tang_song(ds, "poet.tang", "tang_poem", "唐", author_desc(ds, "authors.tang.json"))),
    ("全宋诗", lambda ds, d: it_tang_song(ds, "poet.song", "song_poem", "宋", author_desc(ds, "authors.song.json"))),
    ("全宋词", lambda ds, d: it_ci(ds)),
    ("元曲", lambda ds, d: it_yuanqu(ds)),
    ("水墨唐诗", lambda ds, d: it_shuimotangshi(ds)),
]


def fill_popularity(conn, dataset: Path):
    """rank/ 搜索热度（百度/360/必应/谷歌之和）按 作者+标题 回填 popularity 列"""
    import glob
    pop: dict[tuple[str, str], int] = {}
    for f in glob.glob(str(dataset / "rank" / "*" / "*.json")):
        try:
            for r in load_json(Path(f)):
                t, a = s(r.get("title", "")), s(r.get("author", ""))
                v = sum(int(r.get(k) or 0) for k in ("baidu", "so360", "bing", "google"))
                key = (t, a)
                if v > pop.get(key, 0):
                    pop[key] = v
        except Exception:
            continue
    names = dict(conn.execute("SELECT id, name FROM authors"))
    rows = []
    for pid, title, aid in conn.execute("SELECT id, title, author_id FROM poems"):
        v = pop.get((title, names.get(aid, "")))
        if v:
            rows.append((v, pid))
    conn.executemany("UPDATE poems SET popularity = ? WHERE id = ?", rows)
    top = conn.execute("SELECT title FROM poems ORDER BY popularity DESC LIMIT 5").fetchall()
    print(f"  名气回填: {len(rows)} 首 | 热度Top5: {[t[0] for t in top]}")


def build(db_path: Path, dataset: Path, label: str, full: bool, fts: bool = True):
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
    t0 = time.time()
    fill_birth_death(db.conn)
    backfill_era(db.conn)
    fill_genre(db.conn)
    apply_text_overrides(db.conn)
    if full:
        fill_popularity(db.conn, dataset)
        smooth_grouped_popularity(db.conn)  # 必须在名气回填之后（热度为 0 时无组可合）
    fill_famous_lines(db.conn)
    annotate(db.conn)
    print(f"  [{label}] 意象打标 + 节气节日配诗完成（{time.time()-t0:.0f}s）："
          f"意象 {db.conn.execute('SELECT count(*) FROM imagery').fetchone()[0]} 类，"
          f"配诗键 {db.conn.execute('SELECT count(DISTINCT key) FROM calendar_map').fetchone()[0]} 个")
    db.finish(label, fts=fts)
    total = sum(db.counts.values())
    print(f"  [{label}] 合计 {total} 条，重出剔除 {sum(db.dups.values())} 条 -> {db_path}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dataset", required=True, type=Path, help="chinese-poetry 克隆目录")
    ap.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    ap.add_argument("--font", type=Path, default=Path("C:/Work/scratch/fonts/wenkai.ttf"),
                    help="文楷原始字体（取 cmap 做字形门闩）")
    ap.add_argument("--unihan", type=Path, default=Path("C:/Work/scratch/fonts/Unihan.zip"),
                    help="Unihan.zip（变体映射）")
    args = ap.parse_args()

    if args.font.exists() and args.unihan.exists():
        load_glyph_gate(args.font, args.unihan)
    else:
        print("  ⚠️ 字形门闩未启用（字体或 Unihan 缺失），缺字将原样保留")

    core_out = args.repo / "build-datapack/jigu-core.db"
    full_out = args.repo / "build-datapack/jigu-full.db"
    zip_out = args.repo / "app/src/main/assets/data/jigu.zip"

    print("== 精选库（本地质检用，不进 assets）==")
    build(core_out, args.dataset, "core", full=False)
    print("== 全量库（App 随包，无 FTS）==")
    build(full_out, args.dataset, "full", full=True, fts=False)

    # 全量库压缩后作为 App 唯一随包数据（App 首启解压到内部存储）
    import zipfile
    zip_out.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_out, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        z.write(full_out, "jigu.db")
    # 版本清单：App 从这里读版本号（杜绝 Kotlin 侧硬编码版本与管线脱节的整类 bug）
    version_json = zip_out.parent / "version.json"
    version_json.write_text(
        json.dumps({"data_version": DATA_VERSION, "schema_version": SCHEMA_VERSION},
                   ensure_ascii=False),
        encoding="utf-8",
    )
    print(f"== 打包 {full_out.stat().st_size/1e6:.0f}MB -> {zip_out} ({zip_out.stat().st_size/1e6:.0f}MB) ==")


if __name__ == "__main__":
    sys.exit(main())
