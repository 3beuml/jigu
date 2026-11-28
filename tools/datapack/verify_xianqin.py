# -*- coding: utf-8 -*-
"""批18 质量报告。"""
import json
import random
import re
from collections import Counter

OUT = r"C:\Work\repos\Jigu\tools\datapack\corpus\xianqin.json"
d = json.load(open(OUT, encoding="utf-8"))
ps = d["pieces"]

print("总条数:", len(ps))
print("总字数:", sum(sum(len(x) for x in p["paragraphs"]) for p in ps))
authors = Counter(p["author"] for p in ps)
print("作者数:", len(authors))
print("作者 Top12:", authors.most_common(12))

# 无名氏占比
print("\n无名氏条数:", authors.get("无名氏", 0), f"({authors.get('无名氏',0)/len(ps)*100:.1f}%)")

# 可疑：作者名过长/像诗句
susp = [a for a in authors if len(a) > 8 or re.search(r"[，。]", a)]
print("可疑作者:", len(susp), susp[:10])

# 题名可疑
long_t = [p for p in ps if len(p["title"]) > 30]
print("\n超长题名:", len(long_t))
for p in long_t[:5]:
    print("   ", p["author"], "|", p["title"][:50])

# 正文里残留校记
def dirty(p):
    for x in p["paragraphs"]:
        if "〖" in x or "○《" in x or "（○" in x:
            return True
    return False

dd = [p for p in ps if dirty(p)]
print("\n正文残留校记:", len(dd))
for p in dd[:5]:
    print("   ", p["author"], "|", p["title"], "|", p["paragraphs"][0][:60])

# 正文过短
short = [p for p in ps if sum(len(x) for x in p["paragraphs"]) < 10]
print("\n超短正文(<10字):", len(short))
for p in short[:5]:
    print("   ", p["author"], "|", p["title"], "|", p["paragraphs"])

print("\n=== 随机 10 条 ===")
random.seed(5)
for p in random.sample(ps, 10):
    print(f"\n[{p['dynasty']}·{p['section'][:26]}] {p['author']} 《{p['title']}》")
    print("   " + p["paragraphs"][0][:72])
