# -*- coding: utf-8 -*-
"""批17：全唐诗补编（tangbubian.txt）解析入库语料 —— v6（按编配置，确定性）。

## 为什么要按编配置（2026-11-28，D-050）

前几版用「前瞻 + 散文判别」猜层级，反复误伤：诗句「今朝是何夕，良人谁难觌」被判成小传、
卷内小集题「同音韵诗」被判成作者。原因是五个子编的层深本来就不同，靠形态猜必然出错。
实测各编结构后改为**显式配置**（编 → 作者缩进 / 诗题缩进），不再猜：

| 编 | 范围(行) | 作者缩进 | 诗题缩进 | 备注 |
|---|---|---|---|---|
| 第一编 补全唐诗 | 6331–6609 | 2 | 3 | 无小传 |
| 补全唐诗拾遗 | 6610–6863 | 3 | 4 | 组诗总题在 ind0《…》 |
| 第二编 全唐诗补逸 | 6864–8588 | 3 | 4 | 作者后有 ind0 小传 |
| 第三编 全唐诗续补遗 | 8589–10639 | 3 | 4 | 卷锚 ind0/1，作者偶见 ind1 |
| 全唐诗续拾 | 10640–末尾 | 2 | 3 | 作者后有 ind0 小传 |

切分规则（确定性，无启发式）：
- 编锚 → 载入该编的 (author_ind, title_ind)。
- 卷锚 → 清空作者。
- ind == title_ind 或更深（== title_ind+1，子题）→ 诗题（冲刷上一篇）。
- ind == author_ind 且本行不是诗题层 → 作者（含「宣宗皇帝李忱」这类庙号名）。
  若该行带「N首」或书名号 → 组诗总题，更新题根、不换作者。
- ind == 0 且不是编/卷锚 → 正文（仅在已收到诗题之后）；否则是小传，丢弃。
- 行内 【】〖〗 夹注剥除。
"""
import json
import os
import re
import sqlite3
import sys
import unicodedata
from pathlib import Path

SRC = Path(r"C:\Work\scratch\daizhige_p11\tangbubian.txt")
OUT = Path(r"C:\Work\repos\Jigu\tools\datapack\corpus\tangbubian_full.json")
DB = Path(r"C:\Work\repos\Jigu\build-datapack\jigu-full.db")

BODY_START = 6328
DEBUG = bool(os.environ.get("BB_DEBUG"))

# 编配置：(起始行, 显示名, 作者缩进, 诗题缩进)，按出现顺序
BOOKS = [
    (6331, "全唐诗补编·补全唐诗", 2, 3),
    (6610, "全唐诗补编·补全唐诗拾遗", 3, 4),
    (6864, "全唐诗补编·全唐诗补逸", 3, 4),
    (8589, "全唐诗补编·全唐诗续补遗", 3, 4),
    (10640, "全唐诗补编·全唐诗续拾", 2, 3),
]

JUAN_RE = re.compile(r"^(全唐诗补逸|全唐诗续补遗|全唐诗续拾)卷[一二三四五六七八九十百]+")
VOL_PLAIN = re.compile(r"^卷[一二三四五六七八九十百]+(\[|　|$)")
CJK = re.compile(r"[\u4e00-\u9fff]")
# 小传固定结尾（用于丢弃小传，不参与切分）
BIO_TAIL = re.compile(r"(诗[一二三四五六七八九十百]+首[。．]?$|存诗[一二三四五六七八九十百]+首|补残诗|无诗[。．]?$)")
PROSE_HINT = re.compile(r"(《全唐诗小传》|小传|今按|按《|据《|本传|列传|《旧唐书》|《新唐书》|《唐诗纪事》|《唐才子传》)")


def indent_of(s: str) -> int:
    n = 0
    for ch in s:
        if ch == "\u3000":
            n += 1
        else:
            break
    return n


def strip_notes(s: str) -> str:
    s = re.sub(r"【[^】]*】", "", s)
    s = re.sub(r"〖[^〗]*〗", "", s)
    return s.strip()


def head_before_note(s: str) -> str:
    return re.split(r"[【〖]", s, maxsplit=1)[0].strip()


def clean_title(raw: str) -> str:
    t = raw.strip()
    if t.startswith("【") and t.endswith("】"):
        t = t[1:-1]
    t = strip_notes(t)
    t = re.sub(r"^(.+?)\s*【其([一二三四五六七八九十]+)】(.*)$", r"\1·其\2\3", t)
    t = t.replace("\u3000", "")
    t = re.sub(r"\s+", "", t)
    return t.strip("　 ")


def clean_paragraphs(paras):
    out = []
    for p in paras:
        p = strip_notes(p).replace("\u3000", "").strip()
        if p:
            out.append(p)
    return out


def is_group_title(s: str) -> bool:
    t = strip_notes(head_before_note(s))
    if not t:
        return False
    if re.search(r"[（(]?[一二三四五六七八九十百]+首[）)]?$", t):
        return True
    return "《" in t and "》" in t


def clean_author(raw: str) -> str:
    """作者行清洗：取【前部分、去括号注、去「N首」尾。"""
    t = strip_notes(head_before_note(raw)).replace("\u3000", "").strip()
    t = re.sub(r"[（(][^）)]*[）)]", "", t).strip()
    t = re.sub(r"[一二三四五六七八九十百]+首$", "", t).strip()
    t = re.sub(r"\[[^\]]*\]$", "", t).strip()
    return t


# 卷内集题（原书体例：这些是"某集合"的题，不是人名）→ 归到无名氏，
# 题名补上集名以便区分（如「残诗集·冬出敦煌郡…」）。
COLLECTION_TITLE = re.compile(r"(残诗集|赵志集|道情诗|述怀拟古诗)$")
NON_PERSON = re.compile(
    r"^(道士|女道士|沙门|僧|尼|无名氏|佚名|缺名.*|卷[一二三四五六七八九十百]+|"
    r"宋人著作中所载.*|宋以后著作中所载.*|.*神仙鬼怪诗|友人|.*友邦.*)$"
)


def normalize_author(name: str) -> str:
    """把非人名的「作者」归一：集题→无名氏；泛称→无名氏。"""
    if not name:
        return "无名氏"
    if COLLECTION_TITLE.search(name) or NON_PERSON.match(name):
        return "无名氏"
    return name


def looks_like_bio(paras) -> bool:
    if not paras:
        return False
    first = paras[0]
    if len(first) < 12:
        return False
    if BIO_TAIL.search(first):
        return True
    if PROSE_HINT.search(first):
        return True
    return False


def main() -> int:
    lines = SRC.read_text(encoding="utf-8").split("\n")
    if len(lines) < BODY_START + 100:
        print("源文件行数异常", file=sys.stderr)
        return 1

    pieces = []
    dropped = {"no_author": 0, "no_body": 0, "short_body": 0, "bad_title": 0, "bio_as_body": 0}
    book_name, author_ind, title_ind = BOOKS[0][1], BOOKS[0][2], BOOKS[0][3]
    juan = ""
    author = ""
    title = ""
    paras = []
    last = "section"

    def flush():
        nonlocal title, paras
        if not title:
            paras = []
            return
        ps = clean_paragraphs(paras)
        body_len = sum(len(p) for p in ps)
        if not author:
            dropped["no_author"] += 1
        elif not title or len(title) > 40 or not CJK.search(title):
            dropped["bad_title"] += 1
        elif not ps:
            dropped["no_body"] += 1
        elif body_len < 8:
            dropped["short_body"] += 1
        elif looks_like_bio(ps):
            dropped["bio_as_body"] += 1
            if DEBUG:
                print("BIO_DROP:", author, "|", title, "|", ps[0][:60])
        else:
            section = book_name + (f"·{juan}" if juan else "")
            pieces.append({
                "category": "tang_poem",
                "title": title,
                "author": author,
                "dynasty": "唐",
                "section": section,
                "paragraphs": ps,
            })
        title = ""
        paras = []

    # 编边界（行号）
    book_at = {start: (name, ai, ti) for start, name, ai, ti in BOOKS}

    for i in range(BODY_START, len(lines)):
        raw = lines[i]
        b = raw.strip()
        if not b:
            continue
        ind = indent_of(raw)

        # 1) 编锚
        if i in book_at:
            flush()
            book_name, author_ind, title_ind = book_at[i]
            juan, author = "", ""
            last = "section"
            continue

        # 2) 卷锚
        if JUAN_RE.match(b) or (VOL_PLAIN.match(b) and ind <= 2):
            flush()
            juan = strip_notes(b).replace("\u3000", " ")
            author = ""
            last = "section"
            continue

        # 3) 整行夹注/案语
        if b.startswith("【") or b.startswith("〖"):
            continue

        # 4) 诗题层（== title_ind 或更深）
        if ind >= title_ind:
            flush()
            title = clean_title(b)
            last = "title"
            continue

        # 5) 作者层（== author_ind，且不深于诗题层）
        if ind == author_ind or (ind > 0 and ind < title_ind and ind >= author_ind - 1):
            if is_group_title(b):
                # 组诗总题：更新题根，不换作者
                flush()
                last = "group"
                continue
            flush()
            raw_author = clean_author(b)
            # 卷内集题（残诗集/赵志集…）：不是作者，也不当作者 —— 记为无名氏继续收诗
            if COLLECTION_TITLE.search(raw_author):
                author = "无名氏"
                last = "group"
                continue
            author = raw_author
            last = "author"
            continue

        # 6) 顶格行
        if ind == 0:
            if last in ("title", "body"):
                paras.append(b)
                last = "body"
            else:
                last = "skip"   # 作者小传/案语
            continue

        # 7) 其他缩进行：正文续行（含句读且长）
        if last in ("title", "body") and len(b) > 10:
            paras.append(b)
            last = "body"
            continue
        last = "skip"

    flush()

    # 作者缺失记无名氏；题名为空的丢弃
    cleaned = []
    for p in pieces:
        p["author"] = normalize_author(p["author"])
        cleaned.append(p)

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
    for p in cleaned:
        key = (norm(p["title"]), norm(p["author"]))
        if key in existing or key in seen:
            dup += 1
            continue
        seen.add(key)
        kept.append(p)

    doc = {
        "comment": (
            "批17 全唐诗补编（D-050）：底本 C:/Work/scratch/daizhige_p11/tangbubian.txt（殆知阁整理本），"
            "五编全收（补全唐诗/补全唐诗拾遗/全唐诗补逸/全唐诗续补遗/全唐诗续拾）。"
            "切分按编配置（每编的作者层/诗题层缩进显式声明，不做形态猜测）；"
            "顶格行在诗题后计正文、在作者后按小传丢弃；组诗总题只作题根；行内【】〖〗夹注剥除。"
            "与现库按 题名+作者 归一去重。"
        ),
        "pieces": kept,
    }
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(doc, ensure_ascii=False, indent=1), encoding="utf-8")

    total_chars = sum(sum(len(x) for x in p["paragraphs"]) for p in kept)
    from collections import Counter
    per_book = Counter(p["section"].split("·")[1] for p in kept)
    authors = Counter(p["author"] for p in kept)
    print("源行数:", len(lines))
    print("粗切条数:", len(pieces))
    print("查重丢弃:", dup)
    print("入库条数:", len(kept))
    print("正文字数:", total_chars)
    print("丢弃分布:", dropped)
    print("按编:", dict(per_book))
    print("作者数:", len(authors))
    print("作者 Top10:", authors.most_common(10))
    print("输出:", OUT)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


