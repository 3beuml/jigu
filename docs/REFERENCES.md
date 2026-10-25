# 汲古 · 参考资料档案（软件 / 仓库 / 数据 / 工具）

> 用途：开发汲古时反复要参照的软件、开源仓库、数据集与工具，集中登记。
> 每条含：是什么 / 对汲古的意义 / 链接 / 许可与可用性 / **研究状态**。
> 状态标记：✅已深入（读过代码/源码） ◐了解（README/资料级） ❑仅知存在（待深入）
> 维护约定：有新参照就补条目；状态变了就更新。最后更新 2026-10-25。

## 一、UI / 产品参照（竞品与同路产品）

### 雅言 —— UI 第一参照 ◐
- 是什么：iOS 独占古诗词 App，"古书样式"中式美学、每日一诗随身书房、沉浸竖排、横竖切换、
  循物寻诗（意象检索）、鉴赏页（译文/注释/历代评点/相关诗词/趣谈）、拼音点读、长文折叠。
- 对汲古：D-007 定为 UI 主参照——书页质感、竖排、鉴赏页结构都从它取意。
- 链接：https://apps.apple.com/sg/app/id6761681916 ；开发者自荐帖 https://meta.appinn.net/t/topic/86046
- 状态：◐ 仅公开资料级了解。**缺真实界面逐屏拆解**（封闭源码，需用户截图/描述补充）。
- 待深入：古书样式的具体要素（纸张色值/分栏/页眉页脚/翻页方式）；首页与鉴赏页的信息层级。

### 京墨 jingmo —— 功能与数据组织参照 ◐
- 是什么：开源中华文化阅读 Android App（Kotlin+Compose+Material You），模块：诗文/名句/汉字/
  词语/成语/歇后语/绕口令/传统节日/传统色/节气/人物；GPL-3.0。
- 对汲古：书库模块化结构、离线数据库组织、Material You 落地方式的直接范本。
- 链接：https://github.com/hefengbao/jingmo ；官网 https://jingmo.ruaruan.com
- 状态：◐ **README 与功能清单级，代码未克隆未研读**（这是当前书库设计与京墨存在差距的原因之一）。
- 待深入：①模块页的内部结构（列表/详情/检索怎么组织）；②数据库 schema 与 assets 打包方式；
  ③widget 实现；④GPL 边界：只读思路，禁止抄代码。

### 西窗烛 —— 市场标杆 ◐
- 是什么：古诗词学习工具+原创社区，百万级文库，首页诗词卡片流+每日主题，分享卡片完成度最高。
- 对汲古：卡片排版、文库分类导航、订阅制商业模式样本（我们走的是它的反面：无社区）。
- 链接：https://xichuangzhu.com
- 状态：◐ 资料级。待深入：分享卡片的版式细节（需用户截几张卡片样例）。

### 几枝 —— 传统色与 widget 参照 ◐
- 是什么：独立开发，传统中国色+古诗词，iOS/安卓桌面与锁屏诗词小组件。
- 对汲古：widget 排版（阶段5）、传统色运用（阶段4）。
- 链接：https://jizhi.yichenlab.com/zh-CN/widget
- 状态：❑ 仅商店页信息。待深入：widget 的分行/留白规则。

### 岛读 —— 每日一篇形态参照 ◐
- 是什么：极简每日阅读 App（原名「日课」），一日一文+日历翻页仪式感。
- 对汲古：今日页"纯沉浸"形态的来源；翻页仪式感可借鉴。
- 状态：❑ 资料级。

### 岁时记 / 时历 —— 日期引擎最近邻 ❑
- 岁时记：iOS 节气物候手账（与汲古日期引擎概念正面相邻）；https://apps.apple.com/us/app/id6800766614
- 时历：iOS 日历，含二十四番花信风/七十二候；https://apps.apple.com/cn/app/id1441863229
- 状态：❑ 均未实际体验（iOS 独占）。

### 今日诗词 jinrishici —— 推荐逻辑参照 ◐
- 是什么：免费开放 API，按地点/时间/节日/季节/天气智能推荐诗句；服务闭源、SDK 开源。
- 对汲古：情境推荐思路的来源；我们的离线版已实现节气/节日维度。
- 链接：https://www.jinrishici.com ；开源前身 https://github.com/xenv/gushici
- 状态：◐ API 文档级。待深入：无（思路已消化，后续天气维度再说）。

### aspoem —— 现代极简排版参照 ❑
- 是什么：开源现代诗词网站（Next.js），作者因"诗词站 UI 老"而建；拼音/注释/译文排版友好。
- 对汲古：Web 端极简诗词排版的免费范本（源码可读）。
- 链接：https://github.com/meetqy/aspoem ；https://aspoem.com
- 状态：❑ 待深入：它的 Typography/间距系统。

## 二、开源仓库与官方资源（已用 / 可用）

### chinese-poetry —— 正文数据主库 ✅
- 全唐诗 5.7万/全宋诗 25.4万/全宋词 2.1万/元曲 1.1万/诗经/楚辞/论语/四书/蒙学（含古文观止222篇），
  外加 strains/ 平仄标注 313 文件。MIT。
- 状态：✅ 已深度使用：清洗管线 tools/datapack/build.py；本地克隆 C:\Work\scratch\chinese-poetry。
- 注意：繁简混杂（管线已归一）；《静夜思》等存古本变体；《江南春》"绿映江"变体；清明/小儿垂钓等不在语料。

### android/skills（谷歌官方）—— 开发技能 ✅
- 官方 AI agent 技能库（SKILL.md 开放标准，Apache-2.0）。已装 5 个到 .zcode/skills/：
  compose-theming / navigation-3 / testing-setup / glance-canonical-layouts / edge-to-edge。
- 仓库还有待取用：security、performance(anr/r8)、devtools(android-cli)。
- 链接：https://github.com/android/skills ；本地克隆 C:\Work\scratch\android-skills。

### DSH skill：android-build-install —— 本机打包装机流程 ✅
- 位置：C:\Users\beuml\Desktop\Deepseek\.dsh\skills\android-build-install\SKILL.md
- 内容：打包→装包(push+pm install，禁 adb install)→交验收全流程 + 本机环境事实
  （魅族21 Note / Android 16 / JDK21 / Gradle 8.9 华为云镜像 / adb 路径 / Flyme USB 安装开关）。
- 状态：✅ 已通读并依赖。待办：复制成汲古项目级 skill 并并入汲古包名/路径。

### hitokoto 一言 / 诗泉 chinese-poetry-api —— 模式参考 ◐→✅
- hitokoto：社区投稿+API 运营模式；https://github.com/hitokoto-osc
- 诗泉：Go 高性能古诗词 API（REST/GraphQL）；https://github.com/palemoky/chinese-poetry-api
- 状态：✅ 已克隆研读其 internal/classifier——**体裁判定链**（来源直接映射 > 词牌 > 乐府标题 >
  句数×字数结构分析）与**朝代纪年表**（先秦-2070～清1912）已移植进汲古管线（build.py classify_type）。

### 京墨数据模型补充（2026-10-25 深入）◐→✅
- ClassicPoemEntity：朝代/作者冗余进诗行（浏览零 JOIN）；PeopleEntity：独立人物表带生卒年。
- 汲古对应：poems 加 type/era 冗余列（schema v4）；authors 加生卒年（schema v3）。

## 三、数据集与工具（管线依赖）

| 名称 | 用途 | 许可 | 状态 |
| :-- | :-- | :-- | :-- |
| hungtcs/traditional-chinese-calendar-database | 农历+节气 JSON（备选，未用） | 待查 | ❑（改用 cnlunar 方案） |
| cnlunar (PyPI) | 节气/农历节日预生成（calendar.json） | MIT | ✅ 管线依赖 |
| OpenCC (opencc-python-reimplemented) | 繁转简归一 | Apache-2.0 | ✅ 管线依赖 |
| fonttools + pyftsubset | 霞鹜文楷子集化 | MIT/BSD | ✅ 管线依赖 |
| 霞鹜文楷 LXGW WenKai | 界面正文字体 | SIL OFL | ✅ 已子集化接入（13,092 字 / 6.8MB） |
| chinese_word_rhyme (charlesix59) | 平仄/平水韵（格律工具，远期） | MIT | ❑ |
| pwxcoo/chinese-xinhua | 字典/成语/歇后语（函匣二期候选） | MIT | ❑ |
| LibriVox / Project Gutenberg | 西方诗歌/戏剧音频文本（远期） | 公版 | ❑ |
| 台北故宫 Open Data / Met CC0 / Wikimedia PD-Art | 国画素材（阶段4+） | 开放 | ❑ 清单见 workbench ui-art-resources.md |

## 四、内部档案（workbench 与项目文档）

- 调研报告（5+1 份）：C:\Work\agents\zcode\2026-10-24-jigu-naming\
  competitors.md（竞品）/ resources.md（数据源）/ names-round2.md（命名二轮）/
  daily-apps.md（日常向产品）/ ui-references.md（UI参照）/ ui-art-resources.md（国画素材）
- 决策记录：docs/DECISIONS.md（D-001~D-009，命名/定位/UI/数据/流程全案）
- 本机开发环境：见 DSH skill「环境事实」节（真机/镜像/adb 坑）

## 五、待深入研究清单（按优先级）

1. **京墨代码克隆研读**（书库/数据库/widget 三块思路）——书库设计差距的直接补法
2. **雅言界面拆解**——需要用户提供截图或逐屏描述（古书样式的具体要素）
3. aspoem 源码排版系统
4. 几枝 widget 版式
5. android/skills 补装 security / performance
