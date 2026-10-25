#!/usr/bin/env python3
"""霞鹜文楷子集化：按语料实际字符集裁剪字体，产出 app/src/main/res/font/wenkai_regular.ttf。

用法：
  python tools/fonts/subset_font.py --font <LXGWWenKai-Regular.ttf> [--dataset <数据集目录>]

字符集 = 数据库全文 + 界面文案 + ASCII + 常用中文标点，缺字由系统衬线兜底。
"""

import argparse
import sqlite3
import sys
from pathlib import Path

from fontTools.subset import Subsetter, Options, load_font, save_font

REPO = Path(__file__).resolve().parents[2]
UI_TEXT = (
    "汲古今日书库我的收藏做卡片朗读搜索诗题目作者句子篇返回横竖排序类无结果"
    "筛选全部朝代唐宋元明清汉魏晋近现代星期一二三四五六日月年今昨明"
    "设置主题深色浅色跟随系统关于版本数据来源许可隐私致谢上线阶段"
    "推荐节气节日春节元宵端午七夕中秋重阳除夕腊八立春雨水惊蛰春分清明"
    "谷雨立夏小满芒种夏至小暑大暑处暑白露秋分寒露霜降立冬小雪大雪冬至小寒大寒"
    "首相关评点小传辞赋风雅颂比兴意象合集名句开卷有益"
)


def collect_chars(db_path: Path) -> set[str]:
    chars: set[str] = set(UI_TEXT)
    db = sqlite3.connect(db_path)
    for (text,) in db.execute(
        "SELECT title || author_alias || paragraphs || tags || notes || brief FROM ("
        " SELECT p.title, IFNULL(a.name,'') AS author_alias, p.paragraphs, p.tags, p.notes, p.body AS brief"
        " FROM poems p LEFT JOIN authors a ON a.id = p.author_id)"
    ):
        chars.update(text)
    for (text,) in db.execute("SELECT name || IFNULL(brief,'') FROM authors"):
        chars.update(text)
    for (text,) in db.execute("SELECT name || note FROM imagery"):
        chars.update(text)
    for (text,) in db.execute("SELECT name || note FROM calendar_map"):
        chars.update(text)
    db.close()
    # 控制可见 ASCII + 常用标点与符号
    chars.update(chr(c) for c in range(0x20, 0x7F))
    chars.update("，。！？；：、「」『』（）《》〈〉“”‘’—…·～【】〖〗％‰°℃")
    chars.update("０１２３４５６７８９")
    return chars


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--font", required=True, type=Path)
    ap.add_argument("--dataset", type=Path, default=REPO / "build-datapack/jigu-full.db")
    args = ap.parse_args()

    if not args.dataset.exists():
        print(f"数据库不存在：{args.dataset}（先运行 tools/datapack/build.py）", file=sys.stderr)
        return 1
    chars = collect_chars(args.dataset)
    print(f"字符集规模: {len(chars)}")

    opts = Options()
    opts.name_IDs = ["*"]
    opts.notdef_outline = True
    opts.recalc_bounds = True
    opts.drop_tables += ["DSIG"]
    font = load_font(args.font, opts)
    ss = Subsetter(options=opts)
    ss.populate(text="".join(chars))
    ss.subset(font)

    out = REPO / "app/src/main/res/font/wenkai_regular.ttf"
    out.parent.mkdir(parents=True, exist_ok=True)
    save_font(font, out, opts)
    print(f"子集字体 -> {out} ({out.stat().st_size/1e6:.1f}MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
