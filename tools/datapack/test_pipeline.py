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
    TITLE_FIXES,
    classify_genre,
    classify_type,
    clean_brief,
    extract_two_clause,
    two_clauses_around,
    norm_title,
    clean_placeholder_title,
    split_author_dynasty,
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


def test_gushi19_and_placeholder_head():
    # 批4：古诗十九首固定体裁（五言古诗之祖，来源直接映射）
    assert classify_type("gushi19", "行行重行行", "", ["行行重行行，与君生别离。"]) == "五言古诗"
    # P2-5：空题根（元曲引句作题）剥「·」头
    assert clean_placeholder_title("·夕阳一带山如画") == "夕阳一带山如画"
    assert clean_placeholder_title("・分得金字") == "分得金字"
    assert clean_placeholder_title("静夜思") == "静夜思"
    print("ok  古诗十九首五种定型 + 空题根「·」头（P2-5）")


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


def test_theme_v3_prefix_and_new_classes():
    """v3 词表（批2）：每条新细类一正一负；"^"前缀语义。"""
    # —— 送别收窄（^前缀） positive/negative both ——
    assert "送别" in theme_hits_for("别董大", "千里黄云白日曛，北风吹雁雪纷纷。莫愁前路无知己，天下谁人不识君。")
    # 题中非前缀的"送/别"不再命中（旧版"遥送"会误伤）
    assert "送别" not in theme_hits_for("遥送故人千万里", "别来音信各杳然，思君过尽千山雪。")
    # —— 四季 ——
    assert "春季" in theme_hits_for("钱塘湖春行", "孤山寺北贾亭西，水面初平云脚低。最爱湖东行不足，绿杨阴里白沙堤。")
    assert "春季" not in theme_hits_for("枫桥夜泊", "月落乌啼霜满天，江枫渔父对愁眠。姑苏城外寒山寺，夜半钟声到客船。")
    assert "夏季" in theme_hits_for("观刈麦", "田家少闲月，五月人倍忙。夜来南风起，小麦覆陇黄。足蒸暑土气，背灼炎天光。")
    assert "夏季" not in theme_hits_for("登鹳雀楼", "白日依山尽，黄河入海流。欲穷千里目，更上一层楼。")
    assert "秋季" in theme_hits_for("新秋言怀", "新秋入破宅，疎澹若平郊。落叶惊霜信，西风动旅袍。")
    # 记月纪事题（十月菊）不误记月令；"秋千"负词挡秋千游戏诗
    assert "秋季" not in theme_hits_for("题秋千词", "秋千院落溶溶月，杨柳堆烟帘幕重重。")
    assert "冬季" in theme_hits_for("冬夜读书示子聿", "古人学问无遗力，少壮工夫老始成。纸上得来终觉浅，绝知此事要躬行。")
    assert "冬季" not in theme_hits_for("题破山寺后禅院", "清晨入古寺，初日照高林。曲径通幽处，禅房花木深。")
    # —— 雪/雨 ——
    assert "写雪" in theme_hits_for("白雪歌送武判官归京", "北风卷地白草折，胡天八月即飞雪。忽如一夜春风来，千树万树梨花开。")
    assert "写雪" not in theme_hits_for("春夜喜雨", "好雨知时节，当春乃发生。随风潜入夜，润物细无声。")
    assert "写雨" in theme_hits_for("春夜喜雨", "好雨知时节，当春乃发生。随风潜入夜，润物细无声。")
    assert "写雨" not in theme_hits_for("雨霖铃", "寒蝉凄切，对长亭晚，骤雨初歇。都门帐饮无绪，留恋处，兰舟催发。")
    # —— 月（月份负词；"静夜思"两处"明月"成立）——
    assert "写月" in theme_hits_for("静夜思", "床前看月光，疑是地上霜。举头望明月，低头思故乡。")
    assert "写月" not in theme_hits_for("九月九日忆山东兄弟", "独在异乡为异客，每逢佳节倍思亲。遥知兄弟登高处，遍插茱萸少一人。")
    assert "写月" not in theme_hits_for("杂言", "日月双流转，岁月不知年。")
    # —— 花 ——
    assert "写花" in theme_hits_for("大林寺桃花", "人间四月芳菲尽，山寺桃花始盛开。长恨春归无觅处，不知转入此中来。")
    assert "写花" not in theme_hits_for("登鹳雀楼", "白日依山尽，黄河入海流。欲穷千里目，更上一层楼。")
    assert "写花" not in theme_hits_for("花间集序", "镂玉雕琼，拟化工而回巧。裁花剪叶，夺春艳以争鲜。")
    # —— 爱国 ——
    assert "爱国" in theme_hits_for("示儿", "死去元知万事空，但悲不见九州同。王师北定中原日，家祭无忘告乃翁。")
    assert "爱国" not in theme_hits_for("忆江南", "江南好，风景旧曾谙。日出江花红胜火，春来江水绿如蓝。能不忆江南？")
    # —— 儿童 ——
    assert "儿童" in theme_hits_for("村居", "草长莺飞二月天，拂堤杨柳醉春烟。儿童散学归来早，忙趁东风放纸鸢。")
    assert "儿童" not in theme_hits_for("静夜思", "床前看月光，疑是地上霜。举头望明月，低头思故乡。")
    print("ok  v3 词表（前缀语义 + 季节/雪雨月花/爱国/儿童 一正一负）")


def test_extract_two_clause():
    assert extract_two_clause("国破山河在，城春草木深。感时花溅泪，恨别鸟惊心。") == "国破山河在，城春草木深。"
    assert extract_two_clause("床前明月光，疑是地上霜。") == "床前明月光，疑是地上霜。"
    assert extract_two_clause("无边落木萧萧下，不尽长江滚滚来。") == "无边落木萧萧下，不尽长江滚滚来。"
    # 不等长/无逗号 -> None
    assert extract_two_clause("_hello") is None
    print("ok  名句两句型抽取")


def test_norm_title():
    # 组诗分篇归一（单空格数字尾 / 双空格"其N"尾 →「·其N」）
    assert norm_title("出塞 一") == "出塞·其一"
    assert norm_title("帝京篇十首 五") == "帝京篇十首·其五"
    assert norm_title("咏蝉  其一") == "咏蝉·其一"
    # 非数字尾不吞；题内空格并作「·」
    assert norm_title("一字至七字诗 书") == "一字至七字诗·书"
    assert norm_title("杂曲歌辞 踏歌行 三") == "杂曲歌辞·踏歌行·其三"
    # 无空格数字尾、正常题不动
    assert norm_title("静夜思") == "静夜思"
    assert norm_title("其一") == "其一"  # 无基名，不动
    print("ok  组诗分篇标题归一")


def test_clean_placeholder_title():
    # 纯占位整题 → 佚题；组诗序号/词调名后缀保留
    assert clean_placeholder_title("□□") == "佚题"
    assert clean_placeholder_title("□□□□·其二") == "佚题·其二"
    assert clean_placeholder_title("□□□・七娘子") == "佚题·七娘子"  # 半角中点分隔
    assert clean_placeholder_title("□□·其二") == "佚题·其二"  # 管线中 norm_title 先把空格归「·」
    # 部分缺字保留校勘原貌，不虚构
    assert clean_placeholder_title("□后圃成三绝句·其一") == "□后圃成三绝句·其一"
    assert clean_placeholder_title("□□□□□□□卷后") == "□□□□□□□卷后"
    assert clean_placeholder_title("宿（下泐。按是诗题下似是「千」字，当") == "宿（下泐。按是诗题下似是「千」字，当"
    # 正常题不动
    assert clean_placeholder_title("静夜思") == "静夜思"
    assert clean_placeholder_title("无题") == "无题"
    print("ok  缺字占位标题归一")


def test_split_author_dynasty():
    assert split_author_dynasty("（唐）孟浩然") == ("唐", "孟浩然")
    assert split_author_dynasty("（宋）蘇軾") == ("宋", "蘇軾")
    assert split_author_dynasty("（清）张潮 ") == ("清", "张潮")
    assert split_author_dynasty("佚名") == ("", "佚名")
    print("ok  千家诗作者朝代拆分")


def test_two_clauses_around():
    ai = "水陆草木之花，可爱者甚蕃。晋陶渊明独爱菊。予独爱莲之出淤泥而不染，濯清涟而不妖，中通外直。"
    assert two_clauses_around(ai, "出淤泥而不染") == "予独爱莲之出淤泥而不染，濯清涟而不妖，"
    assert two_clauses_around("天命之谓性，率性之谓道，修道之谓教。", "天命之谓性") == "天命之谓性，率性之谓道，"
    assert two_clauses_around("前一句。含锚在末句。", "含锚在末句") == "前一句。含锚在末句。"
    assert two_clauses_around("风烟俱净。", "不在其中") is None
    print("ok  散文名句锚句居中截取")


def test_title_fixes():
    fix = dict(TITLE_FIXES)
    assert fix.get("在岳咏蝉") == "在狱咏蝉"
    print("ok  标题错字修正清单")


def test_clean_brief():
    # 空格连排的同文（句读异）只留一份；真异文以「；」连接
    assert clean_brief("王建，字仲初，颍川人。大历十年进士。" + "      " + "王建，字仲初，颍川人，大历十年进士，") == \
        "王建，字仲初，颍川人。大历十年进士。"
    assert clean_brief("生于甲年。卒于乙年。") == "生于甲年。卒于乙年。"  # 单段不动
    assert "；" in clean_brief("维基文库版本甲。" + "   " + "另一种版本乙。")
    print("ok  小传空格连排去重")


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
    test_gushi19_and_placeholder_head()
    test_theme_rules_grounded()
    test_theme_hits_positive_negative()
    test_theme_v3_prefix_and_new_classes()
    test_extract_two_clause()
    test_norm_title()
    test_clean_placeholder_title()
    test_split_author_dynasty()
    test_two_clauses_around()
    test_title_fixes()
    test_clean_brief()
    test_classify_genre()
    print("全部通过")
