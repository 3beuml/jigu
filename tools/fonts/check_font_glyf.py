#!/usr/bin/env python3
"""字体字形完整性自查：找出 glyf 表中引用了不存在字形的复合字。

嫁接补充字形（如从 Jigmo 搬运）时，若复合字引用的部件字形没有被一并搬入
主字体（或引用名没改对），该字在真机上会渲染成方框/空白。
用法：
  python tools/fonts/check_font_glyf.py [字体路径]
  # 缺省检查 app/src/main/res/font/wenkai_regular.ttf
"""

import sys
from pathlib import Path

from fontTools.ttLib import TTFont

REPO = Path(__file__).resolve().parents[2]
DEFAULT_FONT = REPO / "app/src/main/res/font/wenkai_regular.ttf"


def main() -> int:
    font_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_FONT
    font = TTFont(str(font_path), lazy=True)
    order = set(font.getGlyphOrder())
    glyf = font["glyf"]

    # 反查 cmap：字形名 -> 该字形服务的字符
    name_to_chars: dict[str, list[str]] = {}
    for cp, gname in font.getBestCmap().items():
        name_to_chars.setdefault(gname, []).append(chr(cp))

    dangling: list[tuple[str, str]] = []  # (字形名, 悬空部件名)
    composites = 0
    for name in sorted(order):
        glyph = glyf[name]
        if not getattr(glyph, "isComposite", lambda: False)():
            continue
        composites += 1
        for comp in glyph.components:
            if comp.glyphName not in order:
                dangling.append((name, comp.glyphName))

    print(f"字体: {font_path} ({font_path.stat().st_size/1e6:.1f}MB)")
    print(f"字形总数: {len(order)}，复合字: {composites}")
    if not dangling:
        print("✅ 无悬空组件引用")
        return 0

    print(f"❌ {len(dangling)} 处悬空组件引用（这些字会渲染成方框/空白）:")
    shown: set[str] = set()
    for name, comp in dangling:
        chars = "".join(name_to_chars.get(name, ["?"]))
        cp_note = f" U+{ord(chars[0]):04X}" if chars and chars != "?" else ""
        line = f"  {name}{cp_note} '{chars}' -> 缺部件 {comp}"
        print(line)
        shown.add(name)
    return 1


if __name__ == "__main__":
    sys.exit(main())
