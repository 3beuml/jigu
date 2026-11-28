# -*- coding: utf-8 -*-
"""批18：先秦汉魏晋南北朝诗（逯钦立辑校）解析入库语料。

## 体例（2026-11-28 侦察，D-051）

底本 C:/Work/scratch/daizhige_p11/xianqin_wei.txt（3.35MB / 41,466 行）：
- 1..170 行 = 目录 + 前言（简介/序/评价文章），正文自「先秦诗卷一」（第 171 行）起。
- 正文缩进树（全角空格）：
    卷标题（ind0，如「先秦诗卷一」「隋诗卷十」）
      类目（ind2，◎释氏 / ◎郊庙 / 无名类）
        作者（ind2，如「僧法宣」「释慧净」；先秦部分无作者）
          小传（ind2，〈…〉包裹，作者之后）
            诗题（ind2，【…】全括号包裹；〖…〗是注文/校记）
              正文（ind2，句号断句；多段并列）
- **注意**：正文与诗题同为 ind2，靠括号形态区分：【】= 题，〖〗= 注/序，其余=正文。
- 诗题为空的（先秦歌谣）用类目名兜底。

## 朝代映射（由卷标题前缀决定）
先秦诗→先秦 / 全汉诗→汉 / 魏诗→魏 / 晋诗→晋 / 宋诗→南朝宋 / 齐诗→南朝齐 /
梁诗→南朝梁 / 北魏诗→北魏 / 北齐诗→北齐 / 北周诗→北周 / 陈诗→南朝陈 / 隋诗→隋

## 输出
{"comment": ..., "pieces": [{"category","title","author","dynasty","section","paragraphs"}]}
"""
import json
import re
import sqlite3
import sys
import unicodedata
from pathlib import Path

SRC = Path(r"C:\Work\scratch\daizhige_p11\xianqin_wei.txt")
OUT = Path(r"C:\Work\repos\Jigu\tools\datapack\corpus\xianqin.json")
DB = Path(r"C:\Work\repos\Jigu\build-datapack\jigu-full.db")

BODY_START = 170   # 「先秦诗卷一」在第 171 行（1-based），0-based = 170

# 卷标题 → (朝代, 分卷名)
JUAN_MAP = [
    ("先秦诗", "先秦"),
    ("全汉诗", "汉"),
    ("魏诗", "魏"),
    ("晋诗", "晋"),
    ("宋诗", "南朝宋"),
    ("齐诗", "南朝齐"),
    ("梁诗", "南朝梁"),
    ("北魏诗", "北魏"),
    ("北齐诗", "北齐"),
    ("北周诗", "北周"),
    ("陈诗", "南朝陈"),
    ("隋诗", "隋"),
]
JUAN_RE = re.compile(
    r"^(" + "|".join(k for k, _ in JUAN_MAP) + r")卷([一二三四五六七八九十]+)\s*$"
)
CLASS_RE = re.compile(r"^◎(.+)$")
NOTE_RE = re.compile(r"^〖.*〗$")
CJK = re.compile(r"[\u4e00-\u9fff]")
# 小传包裹：〈…〉
BIO_RE = re.compile(r"^〈.*〉$")


def indent_of(s: str) -> int:
    n = 0
    for ch in s:
        if ch == "\u3000":
            n += 1
        else:
            break
    return n


def clean_body(s: str) -> str:
    """正文清洗：
    - 去掉行尾的出处/校记括注（○《…》/（《…》）/（此句…○…）等），
      这类括注是逯书的校勘记，不是诗；
    - 剥行内〖…〗；
    - 保留正常诗句（含（文苑作X）这类单字异文注时，整段保留，仅在不含句号时剥离）。
    """
    t = s.replace("\u3000", "").strip()
    # 1) 剥行内 〖…〗 校记
    t = re.sub(r"〖[^〗]*〗", "", t)
    # 2) 从第一个「（…○…）」或「（○…」起截断（出处注总是带 ○）
    m = re.search(r"[（(][^（）()]*○", t)
    if m and m.start() > 8:
        t = t[: m.start()].rstrip("，、； ")
    # 3) 行尾「（○…）」/「（《…》）」单独清一次
    t = re.sub(r"[（(]○[^）)]*[）)]\s*$", "", t)
    t = re.sub(r"[（(]《[^）)]*[）)]\s*$", "", t)
    return t.strip()


def clean_title(s: str) -> str:
    t = s.strip()
    if t.startswith("【") and t.endswith("】"):
        t = t[1:-1]
    t = re.sub(r"〖[^〗]*〗", "", t)
    t = re.sub(r"\s+", "", t)
    return t.strip()


def clean_author(s: str) -> str:
    """作者行清洗：去 ◎、括号注、〈…〉附注、以及残留的【】〖〗。"""
    t = s.strip().replace("\u3000", "")
    t = re.sub(r"^◎", "", t)
    t = re.sub(r"【[^】]*】", "", t)
    t = re.sub(r"〖[^〗]*〗", "", t)
    t = re.sub(r"〈[^〉]*〉", "", t)
    t = re.sub(r"[（(][^）)]*[）)]", "", t)
    return t.strip()


# 泛称（不是具体人名）→ 归无名氏
GENERIC_AUTHOR = re.compile(
    r"^(无名氏|佚名|缺名.*|道士|女道士|沙门|僧|尼|释.*|神仙|鬼.*|.*谚语附|"
    r"郊庙歌辞|燕射歌辞|鼓吹曲辞|横吹曲辞|相和歌辞|清商曲辞|舞曲歌辞|琴曲歌辞|杂曲歌辞|杂歌谣辞|近代曲辞|新乐府辞)$"
)


def main() -> int:
    lines = SRC.read_text(encoding="utf-8").split("\n")
    if len(lines) < BODY_START + 1000:
        print("源文件行数异常", file=sys.stderr)
        return 1

    # 下一条非空行索引
    next_idx = [-1] * len(lines)
    nxt = -1
    for i in range(len(lines) - 1, -1, -1):
        next_idx[i] = nxt
        if lines[i].strip():
            nxt = i

    pieces = []
    dropped = {"no_body": 0, "short": 0}
    dynasty = "先秦"
    juan = ""
    klass = ""
    author = ""
    title = ""
    paras = []

    def flush():
        nonlocal title, paras
        if not title:
            paras = []
            return
        ps = [p for p in (clean_body(x) for x in paras) if p and CJK.search(p)]
        chars = sum(len(p) for p in ps)
        if not ps:
            dropped["no_body"] += 1
        elif chars < 4:
            dropped["short"] += 1
        else:
            section = "先秦汉魏晋南北朝诗·" + (klass or juan or dynasty)
            pieces.append({
                "category": "han_wei",
                "title": title,
                "author": author or "无名氏",
                "dynasty": dynasty,
                "section": section,
                "paragraphs": ps,
            })
        title = ""
        paras = []

    for i in range(BODY_START, len(lines)):
        raw = lines[i]
        b = raw.strip()
        if not b:
            continue

        # 1) 卷标题（ind0）
        m = JUAN_RE.match(b)
        if m:
            flush()
            prefix, num = m.group(1), m.group(2)
            dynasty = dict(JUAN_MAP)[prefix]
            juan = f"{prefix}卷{num}"
            klass, author = "", ""
            continue

        # 2) 类目 ◎xxx
        if CLASS_RE.match(b):
            flush()
            klass = clean_author(b)
            author = ""
            continue

        # 3) 小传 〈…〉：丢弃
        if BIO_RE.match(b):
            continue

        # 4) 注文 〖…〗：不改变状态
        if NOTE_RE.match(b):
            continue

        # 5) 诗题 【…】
        if b.startswith("【") and b.endswith("】"):
            flush()
            title = clean_title(b)
            if not title:
                title = klass or juan
            continue

        # 6) 作者行判定：本行短、单行成句、且**下一条非空行是〈小传〉或【诗题】**
        #    （作者后面先跟小传，再跟题；也有的直接跟题）
        nj = next_idx[i]
        ntext = lines[nj].strip() if nj >= 0 else ""
        is_name_shape = (
            len(b) <= 14
            and "。" not in b
            and not re.search(r"[，、；：？！]", b)
            and CJK.search(b) is not None
        )
        if is_name_shape and (ntext.startswith("【") or ntext.startswith("〈")):
            flush()
            author = clean_author(b)
            continue

        # 7) 正文
        if not title:
            title = klass or juan or "佚题"
        paras.append(b)

    flush()

    # 查重
    def norm(s: str) -> str:
        s = unicodedata.normalize("NFKC", s or "")
        return re.sub(r"[\s·・、，。！？；：\"'（）()《》【】〈〉—…\-]", "", s)

    existing = set()
    if DB.exists():
        con = sqlite3.connect(f"file:{DB}?mode=ro", uri=True)
        for t, a in con.execute(
            "SELECT p.title, IFNULL(a.name,'') FROM poems p LEFT JOIN authors a ON a.id=p.author_id"
        ):
            existing.add((norm(t), norm(a)))
        con.close()

    kept, dup = [], 0
    seen = set()
    for p in pieces:
        # 泛称作者归一
        if not p["author"]:
            p["author"] = "无名氏"
        elif GENERIC_AUTHOR.match(p["author"]):
            p["author"] = "无名氏"
        key = (norm(p["title"]), norm(p["author"]))
        if key in existing or key in seen:
            dup += 1
            continue
        seen.add(key)
        kept.append(p)

    doc = {
        "comment": (
            "批18 先秦汉魏晋南北朝诗（D-051）：底本 C:/Work/scratch/daizhige_p11/xianqin_wei.txt"
            "（逯钦立辑校，中华书局本，殆知阁整理）。五十三卷分十二段（先秦/汉/魏/晋/宋/齐/梁/"
            "北魏/北齐/北周/陈/隋），共 41,466 行。切分：卷标题(ind0) → 类目◎ → 小传〈〉丢弃 →"
            "诗题【】 → 正文；〖〗注文与行尾（○出处）剥除。与现库按 题名+作者 归一去重。"
        ),
        "pieces": kept,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")

    total_chars = sum(sum(len(x) for x in p["paragraphs"]) for p in kept)
    from collections import Counter
    print("源行数:", len(lines))
    print("粗切条数:", len(pieces))
    print("查重丢弃:", dup)
    print("入库条数:", len(kept))
    print("正文字数:", total_chars)
    print("丢弃分布:", dropped)
    print("朝代分布:", dict(Counter(p["dynasty"] for p in kept)))
    print("作者数:", len({p["author"] for p in kept}))
    print("输出:", OUT)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
