# -*- coding: utf-8 -*-
"""产物质量报告：作者名形态、题名形态、样本、异常统计。"""
import json
import re
from collections import Counter

OUT = r"C:\Work\repos\Jigu\tools\datapack\corpus\tangbubian_full.json"
d = json.load(open(OUT, encoding="utf-8"))
ps = d["pieces"]

print("总条数:", len(ps))
print("总字数:", sum(sum(len(x) for x in p["paragraphs"]) for p in ps))
print("作者数:", len({p["author"] for p in ps}))
print("节数:", len({p["section"] for p in ps}))

authors = Counter(p["author"] for p in ps)
print("\n作者 Top15:", authors.most_common(15))

# 可疑作者：含「诗/集/卷/歌」等题名特征字
susp = [a for a in authors if re.search(r"[诗卷集]", a) and a != "无名氏"]
print(f"\n可疑作者（含 诗/卷/集）: {len(susp)}")
print("  样本:", susp[:20])

# 题名可疑：过长 / 含句读
long_t = [(p["author"], p["title"]) for p in ps if len(p["title"]) > 30]
print(f"\n超长题名(>30): {len(long_t)}")
for x in long_t[:8]:
    print("   ", x[0], "|", x[1][:55])

# 正文可疑：像小传
def bioish(p):
    f = p["paragraphs"][0]
    return len(f) > 40 and f.count("。") <= 2 and re.search(r"(人|字|仕|卒|补诗)", f[:40])

bio_like = [(p["author"], p["title"], p["paragraphs"][0][:50]) for p in ps if bioish(p)]
print(f"\n正文疑似小传: {len(bio_like)}")
for x in bio_like[:8]:
    print("   ", x)

# 段落数分布
print("\n段落数:", Counter(len(p["paragraphs"]) for p in ps).most_common(6))

import random
random.seed(11)
print("\n=== 随机 10 条 ===")
for p in random.sample(ps, 10):
    print(f"\n[{p['section'][:30]}] {p['author']} 《{p['title']}》")
    print("   " + p["paragraphs"][0][:68])
