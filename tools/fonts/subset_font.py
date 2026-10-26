#!/usr/bin/env python3
"""霞鹜文楷子集化：按语料实际字符集裁剪字体，产出 app/src/main/res/font/wenkai_regular.ttf。

用法：
  python tools/fonts/subset_font.py --font <LXGWWenKai-Regular.ttf> \
      [--dataset build-datapack/jigu-full.db] [--supplement Jigmo*.ttf 所在目录/文件列表]

字符集 = 数据库全文 + 界面文案 + ASCII + 常用中文标点。

缺字处理分两层：
  - 内容层：build.py 的字形门闩（Unihan 变体映射成文楷有字形的通行字）
  - 字体层（本工具）：门闩也覆盖不了的生僻字（多为 Ext-B+ 异体），先由 wenkai:
    原字体检查，再从补充字体 Jigmo 三件套（Ext A-U 全覆盖，UPM 1024）子集化、
    按 1000/1024 缩放后嫁接进文楷子集。
  嫁接后仍是单一 wenkai_regular.ttf，App/Widget/分享卡片零改动全部受益。
"""

import argparse
import sqlite3
import sys
from pathlib import Path

from fontTools.subset import Subsetter, Options, load_font, save_font
from fontTools.misc.transform import Transform
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.ttLib.tables._c_m_a_p import cmap_format_12

REPO = Path(__file__).resolve().parents[2]
UI_TEXT = (
    "汲古今日书库我的收藏做卡片朗读搜索诗题目作者句子篇返回横竖排序类无结果"
    "筛选全部朝代唐宋元明清汉魏晋近现代星期一二三四五六日月年今昨明"
    "设置主题深色浅色跟随系统关于版本数据来源许可隐私致谢上线阶段"
    "推荐节气节日春节元宵端午七夕中秋重阳除夕腊八立春雨水惊蛰春分清明"
    "谷雨立夏小满芒种夏至小暑大暑处暑白露秋分寒露霜降立冬小雪大雪冬至小寒大寒"
    "首相关评点小传辞赋风雅颂比兴意象合集名句开卷有益"
)

TARGET_UPM = 1000  # 霞鹜文楷 UPM；Jigmo 是 1024，字形步进按比例缩放


def collect_chars(db_path: Path) -> set[str]:
    chars: set[str] = set(UI_TEXT)
    db = sqlite3.connect(db_path)
    for (text,) in db.execute(
        "SELECT title || author_alias || paragraphs || tags || notes || brief FROM ("
        " SELECT p.title, IFNULL(a.name,'') AS author_alias, p.paragraphs, p.tags, p.notes, p.body AS brief"
        " FROM poems p LEFT JOIN authors a ON a.id = p.author_id)"
    ):
        chars.update(ch for ch in text if ord(ch) >= 0x20)
    for (text,) in db.execute("SELECT name || IFNULL(brief,'') FROM authors"):
        chars.update(ch for ch in text if ord(ch) >= 0x20)
    for (text,) in db.execute("SELECT name || note FROM imagery"):
        chars.update(ch for ch in text if ord(ch) >= 0x20)
    for (text,) in db.execute("SELECT name || note FROM calendar_map"):
        chars.update(ch for ch in text if ord(ch) >= 0x20)
    db.close()
    # 控制可见 ASCII + 常用标点与符号
    chars.update(chr(c) for c in range(0x20, 0x7F))
    chars.update("，。！？；：、「」『』（）《》〈〉“”‘’—…·～【】〖〗％‰°℃")
    chars.update("０１２３４５６７８９")
    return chars


def _subset_opts(clean: bool = False) -> Options:
    opts = Options()
    opts.notdef_outline = True
    opts.recalc_bounds = True
    if clean:
        # 补充字形只做轮廓搬运：名字表/GSUB/GPOS 等一概不要，减小体积消除嫁接冲突
        opts.name_IDs = []
        opts.name_legacy = False
        opts.name_languages = []
        opts.layout_features = []
        opts.drop_tables += ["GSUB", "GPOS", "GDEF", "BASE", "kern", "DSIG", "FFTM"]
    else:
        opts.name_IDs = ["*"]
        opts.drop_tables += ["DSIG"]
    # 纵向度量（vhea/vmtx）App 用不到且嫁接后需逐字形补配，直接瘦身丢弃
    opts.drop_tables += ["vhea", "vmtx"]
    return opts


def _harvest_scaled_glyphs(jfont, target_upm: int) -> dict[str, tuple[object, tuple[int, int]]]:
    """抽出（子集化后的）补充字体全部字形，轮廓与步进缩放到目标 UPM。"""
    from fontTools.misc.transform import Transform

    scale = target_upm / jfont["head"].unitsPerEm
    tfm = Transform().scale(scale)
    out: dict[str, tuple[object, tuple[int, int]]] = {}
    glyph_set = jfont.getGlyphSet()
    for name in jfont.getGlyphOrder():
        if name == ".notdef":
            continue
        pen = TTGlyphPen(None)
        glyph_set[name].draw(TransformPen(pen, tfm))
        adv, lsb = jfont["hmtx"][name]
        out[name] = (pen.glyph(), (int(round(adv * scale)), int(round(lsb * scale))))
    return out


def _rename_components(glyph, rename: dict[str, str]):
    for comp in getattr(glyph, "components", []):
        for attr in ("glyphName",):
            name = getattr(comp, attr, None)
            if name and name in rename:
                setattr(comp, attr, rename[name])


def apply_supplement(font, needed: set[str], sources: list[Path]) -> tuple[int, list[str]]:
    """缺字从 Jigmo 嫁接进主字体。返回 (已补字数, 补充后仍缺字符列表)。"""
    todo = {ord(c): c for c in sorted(needed) if ord(c) not in font.getBestCmap()}
    taken: dict[int, str] = {}
    glyphs: dict[str, tuple[object, tuple[int, int]]] = {}
    rename: dict[str, str] = {}

    for src in sources:
        rest = [c for cp, c in todo.items() if cp not in taken]
        if not rest:
            break
        hits = [c for c in rest if ord(c) in TTFont(str(src), lazy=True).getBestCmap()]
        if not hits:
            continue
        opts = _subset_opts(clean=True)
        jfont = load_font(src, opts)
        ss = Subsetter(options=opts)
        ss.populate(text="".join(hits))
        ss.subset(jfont)
        harvested = _harvest_scaled_glyphs(jfont, TARGET_UPM)
        src_cmap = jfont.getBestCmap()
        for cp, gname in src_cmap.items():
            if cp in taken or cp not in todo or gname not in harvested:
                continue
            new_name = f"jig_{cp:05x}"
            if gname == new_name:
                # 理论上不可能撞名（原字符不存在于文楷），保险留着命名规则
                pass
            rename.setdefault(gname, new_name)
            taken[cp] = new_name
            glyphs[new_name] = harvested[gname]

    if not taken:
        return 0, sorted(chr(c) for c in todo)

    # 组件引用改名
    for new_name, (glyph, _adv) in glyphs.items():
        _rename_components(glyph, rename)
    # 嫁接：glyf/hmtx 追加，post 转 3.0 免记名，cmap 补 format 12
    order = list(font.getGlyphOrder())
    glyf = font["glyf"]
    hmtx = font["hmtx"]
    for name, (glyph, (adv, lsb)) in glyphs.items():
        order.append(name)
        glyf[name] = glyph
        hmtx.metrics[name] = (adv, lsb)
    font.setGlyphOrder(order)
    if font["post"].formatType == 2.0:
        font["post"].formatType = 3.0
    best = font.getBestCmap()
    merged = dict(best)
    merged.update(taken)
    if not any(st.format == 12 for st in font["cmap"].tables):
        st = cmap_format_12(12)
        st.platformID = 3
        st.platEncID = 10
        st.format = 12
        st.codeToGlyphName = merged
        font["cmap"].tables.append(st)
    else:
        for st in font["cmap"].tables:
            if st.format == 12:
                st.cmap.update(taken)
    return len(taken), sorted(chr(c) for c in todo if c not in taken)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--font", required=True, type=Path)
    ap.add_argument("--dataset", type=Path, default=REPO / "build-datapack/jigu-full.db")
    ap.add_argument("--supplement", type=Path, nargs="*", default=[],
                    help="Jigmo*.ttf 所在目录，或字体文件列表")
    args = ap.parse_args()

    if not args.dataset.exists():
        print(f"数据库不存在：{args.dataset}（先运行 tools/datapack/build.py）", file=sys.stderr)
        return 1
    chars = collect_chars(args.dataset)
    print(f"字符集规模: {len(chars)}")

    cmap = TTFont(str(args.font)).getBestCmap()
    not_in_font = sorted(ch for ch in chars if ord(ch) not in cmap)
    if not_in_font:
        print(f"⚠️ 原字体不包含 {len(not_in_font)} 个字: " + "".join(not_in_font[:60]))

    opts = _subset_opts()
    font = load_font(args.font, opts)
    ss = Subsetter(options=opts)
    ss.populate(text="".join(chars))
    ss.subset(font)

    missing = sorted(ch for ch in chars if ord(ch) not in font.getBestCmap())
    if missing and args.supplement:
        sources: list[Path] = []
        for p in args.supplement:
            sources += sorted(p.glob("Jigmo*.ttf")) if p.is_dir() else [p]
        fixed, still = apply_supplement(font, missing, sources)
        print(f"补充字形（Jigmo 嫁接，UPM 已缩放 1024→{TARGET_UPM}）: {fixed} 个")
        if still:
            print(f"⚠️ 补充后仍缺 {len(still)} 个: " + "".join(still[:60]))
    elif missing:
        print("⚠️ 未指定 --supplement，缺字仍依赖系统兜底")

    out = REPO / "app/src/main/res/font/wenkai_regular.ttf"
    out.parent.mkdir(parents=True, exist_ok=True)
    font.save(str(out), reorderTables=False)
    print(f"子集字体 -> {out} ({out.stat().st_size/1e6:.1f}MB)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
