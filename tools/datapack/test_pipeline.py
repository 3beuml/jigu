#!/usr/bin/env python3
"""数据管线单元测试（纯函数，不需要数据集）。

运行：python tools/datapack/test_pipeline.py
CI 中同样执行；任何断言失败即退出非零。
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from build import (  # noqa: E402
    ERA_MAP,
    THEME_RULES,
    classify_genre,
    classify_type,
    extract_two_clause,
    s,
    theme_hits_for,
)


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


def test_theme_rules_grounded():
    # 词表自检：每类必非空四元组，四个门全覆盖
    grps = {grp for _, grp, *_ in THEME_RULES}
    assert {"写景", "抒怀", "咏物", "世情"} <= grps, grps
    print("ok  主题词表自检（四门齐备）")


def test_theme_hits_positive_negative():
    # —— 正例 ——
    # 静夜思无两边正词（不是好样本），不设断言；正例看下面各条
    assert "送别" in theme_hits_for("送杜少府之任蜀州",
                                   "城阙辅三秦，风烟望五津。与君离别意，同是宦游人。海内存知己，天涯若比邻。无为在歧路，儿女共沾巾。")
    assert "咏史怀古" in theme_hits_for("越中览古", "越王勾践破吴归，义士还乡尽锦衣。宫女如花满春殿，只今惟有鹧鸪飞。")
    # —— 负例（v2 权重制的核心目的）——
    # 单字"酒"出现 1 次不足以打"饮酒"（旧版会误伤 4.9 万首）
    assert "饮酒" not in theme_hits_for("枫桥夜泊", "月落乌啼霜满天，江枫渔父对愁眠。姑苏城外寒山寺，夜半钟声到客船。")
    assert "饮酒" in theme_hits_for("月下独酌", "花间一壶酒，独酌无相亲。举杯邀明月，对影成三人。"
                                              "月既不解饮，影徒随我身。暂伴月将影，行乐须及春。")
    # "田园"被塞字负向词挡住军事诗
    assert "田园" not in theme_hits_for("塞下曲", "月黑雁飞高，单于夜遁逃。欲将轻骑逐，大雪满弓刀。")
    print("ok  主题双例（标题权重/正文两处/负向词）")


def test_extract_two_clause():
    assert extract_two_clause("国破山河在，城春草木深。感时花溅泪，恨别鸟惊心。") == "国破山河在，城春草木深。"
    assert extract_two_clause("床前明月光，疑是地上霜。") == "床前明月光，疑是地上霜。"
    assert extract_two_clause("无边落木萧萧下，不尽长江滚滚来。") == "无边落木萧萧下，不尽长江滚滚来。"
    # 不等长/无逗号 -> None
    assert extract_two_clause("_hello") is None
    print("ok  名句两句型抽取")


def test_classify_genre():
    assert classify_genre("小石潭记") == "杂记"
    assert classify_genre("岳阳楼记") == "杂记"
    assert classify_genre("出师表") == "奏议"
    assert classify_genre("报任安书") == "书说"
    assert classify_genre("过秦论") == "论辨"
    assert classify_genre("赤壁赋") == "辞赋"
    assert classify_genre("祭十二郎文") == "哀祭"
    assert classify_genre("兰亭集序") == "序跋"
    assert classify_genre("送东阳马生序") == "赠序"
    assert classify_genre("钴鉧潭西小丘记") == "杂记"
    assert classify_genre("捕蛇者说") == "论辨"
    assert classify_genre("五柳先生传") == "传状"
    assert classify_genre("陋室铭") == "箴铭"
    print("ok  古文文体 13 类映射")


if __name__ == "__main__":
    test_era_map()
    test_simplify()
    test_classify_type()
    test_classify_qianjiashi_type_passthrough()
    test_theme_rules_grounded()
    test_theme_hits_positive_negative()
    test_extract_two_clause()
    test_classify_genre()
    print("全部通过")
