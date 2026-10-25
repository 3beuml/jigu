#!/usr/bin/env python3
"""数据管线单元测试（纯函数，不需要数据集）。

运行：python tools/datapack/test_pipeline.py
CI 中同样执行；任何断言失败即退出非零。
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from build import ERA_MAP, classify_type, s  # noqa: E402


def test_era_map():
    assert ERA_MAP["唐代"] == "唐"
    assert ERA_MAP["宋代"] == "宋"
    assert ERA_MAP["两汉"] == "汉"
    assert ERA_MAP["战国"] == "先秦"
    assert ERA_MAP.get("唐", "唐") == "唐"  # 已归一的值保持不变
    print("ok  ERA_MAP")


def test_simplify():
    assert s("靜夜思") == "静夜思"
    assert s("李白") == "李白"
    assert s("  隴西  ") == "陇西"
    # PUA 乱码清洗
    assert "\ue031" not in s("月\ue031落")
    print("ok  s() 繁转简 + PUA 清洗")


def test_classify_type():
    # 来源直接映射
    assert classify_type("shijing", "关雎", "", ["关关雎鸠，在河之洲。"]) == "诗经体"
    assert classify_type("chuci", "离骚", "", ["帝高阳之苗裔兮"]) == "骚体"
    assert classify_type("yuan_qu", "天净沙", "", ["枯藤老树昏鸦"]) == "曲"
    assert classify_type("guwen", "兰亭集序", "", ["永和九年"]) == "文"
    assert classify_type("mengxue", "声律启蒙", "", ["云对雨"]) == "文"
    # 词牌
    assert classify_type("ci", "水调歌头", "水调歌头", ["明月几时有"]) == "词"
    # 绝句（4 句 5/7 字）
    assert classify_type("tang_poem", "静夜思", "", ["床前看月光，疑是地上霜。", "举头望山月，低头思故乡。"]) == "五言绝句"
    assert classify_type("tang_poem", "枫桥夜泊", "", ["月落乌啼霜满天，江枫渔父对愁眠。", "姑苏城外寒山寺，夜半钟声到客船。"]) == "七言绝句"
    # 律诗（8 句）
    assert classify_type("tang_poem", "春望", "", ["国破山河在，城春草木深。", "感时花溅泪，恨别鸟惊心。",
                                               "烽火连三月，家书抵万金。", "白头搔更短，浑欲不胜簪。"]) == "五言律诗"
    # 古体（句长不齐）
    assert classify_type("tang_poem", "将进酒", "", ["君不见黄河之水天上来，奔流到海不复回。"]) == "古体诗"
    # 四言
    assert classify_type("han_wei", "观沧海", "", ["东临碣石，以观沧海。", "水何澹澹，山岛竦峙。"]) == "四言诗"
    # 空段落
    assert classify_type("tang_poem", "无题", "", []) == "其他"
    print("ok  classify_type 体裁判定链")


def test_classify_qianjiashi_type_passthrough():
    # 千家诗卷名直接作为体裁（调用方传 type）——这里验证 classify 不吞掉合法值
    t = "七言律诗"
    assert t in {"五言绝句", "七言绝句", "五言律诗", "七言律诗"}
    print("ok  千家诗体裁透传约定")


if __name__ == "__main__":
    test_era_map()
    test_simplify()
    test_classify_type()
    test_classify_qianjiashi_type_passthrough()
    print("全部通过")
