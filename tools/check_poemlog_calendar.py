# -*- coding: utf-8 -*-
"""D-053 诗历月历化：离机自检（手机离线时替代真机点击的静态验证）。

检查项：
1. monthGridCells 纯函数存在且逻辑与已验证算法一致（周一起算、补 null 到 7 倍数）。
2. 月历网格用 Row+weight 而非 LazyVerticalGrid（避免与外层 LazyColumn 嵌套滚动冲突）。
3. 圆环/墨点只用现有主题色（primary / onBackground / surfaceVariant），无硬编码色。
4. 「不要抄」清单：源码不含 streak / 补签 / 红点 / 连续 之类文案与逻辑。
5. 日志缺失条目（id 漂移）与时间线为空两种状态分别有文案。
6. 只改动了 PoemLogScreen.kt（其余文件无写入）。
"""
import re
import subprocess
import sys
from pathlib import Path

REPO = Path(r"C:\Work\repos\Jigu")
SRC = REPO / "app/src/main/java/com/jigu/app/ui/today/PoemLogScreen.kt"
text = SRC.read_text(encoding="utf-8")

# 只检查代码行：剔除 // 行注释与 /* */ 块注释，
# 否则「不做 streak/红点」「用 Row 而不是 LazyVerticalGrid」这类说明文字会误报。
def strip_comments(src: str) -> str:
    src = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
    return "\n".join(
        line.split("//", 1)[0] if not line.strip().startswith("//") else ""
        for line in src.splitlines()
    )


code = strip_comments(text)

ok, fail = [], []


def check(name, cond, detail=""):
    (ok if cond else fail).append((name, detail))


# 1) 纯函数与算法
check("monthGridCells 纯函数存在", "internal fun monthGridCells(month: YearMonth)" in text)
check(
    "网格按周一起算（dayOfWeek.value - 1）",
    ".dayOfWeek.value - 1" in text,
    "DayOfWeek.MONDAY.value == 1，减 1 得到前置格数",
)
check("补齐到 7 的倍数", "while (cells.size % 7 != 0)" in text)
check("调用纯函数渲染网格", "monthGridCells(month).chunked(7)" in text)

# 2) 嵌套滚动安全
check("未使用 LazyVerticalGrid", "LazyVerticalGrid" not in code)
check("网格用 Row + weight", "week.forEach { date ->" in text and "Modifier.weight(1f)" in text)

# 3) 颜色来源
hardcoded = re.findall(r"Color\(0x[0-9A-Fa-f]{8}\)", code)
check("无硬编码 Color(0x…)", len(hardcoded) == 0, str(hardcoded))
check("节气圆环用 primary", "MaterialTheme.colorScheme.primary.copy" in text)
check("今日底色用 surfaceVariant", "MaterialTheme.colorScheme.surfaceVariant" in text)

# 4) 不要抄
banned = ["streak", "Streak", "补签", "红点", "连续打开", "打卡"]
hits = [b for b in banned if b in code]
check("无 streak/补签/红点/打卡", len(hits) == 0, str(hits))

# 5) 两种空状态
check("区分「从没打开过」", "还没有记录" in text)
check("区分「记录失联」", "数据包更新后原诗已无法对应" in text)
check("月历对失联条目仍打点", "history.mapTo(HashSet())" in text)

# 6) 只动一个文件
try:
    diff = subprocess.run(
        ["git", "diff", "--name-only", "HEAD"],
        cwd=str(REPO), capture_output=True, text=True, encoding="utf-8", timeout=60,
    ).stdout
    changed = [p for p in diff.splitlines() if p.strip()]
    check("只改 PoemLogScreen.kt", changed == [
        "app/src/main/java/com/jigu/app/ui/today/PoemLogScreen.kt"
    ], str(changed))
except Exception as e:
    check("git diff 可执行", False, str(e))

print("=" * 56)
print(f"通过 {len(ok)} / {len(ok) + len(fail)}")
for name, _ in ok:
    print(f"  ok   {name}")
if fail:
    print()
    for name, detail in fail:
        print(f"  FAIL {name}  {detail}")
    sys.exit(1)
print("\n全部通过")

