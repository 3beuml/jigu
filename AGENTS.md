# AGENTS — 汲古（Jigu）

> 任何智能体在本项目开工前先读这份。**功能验收由用户本人在真机上做**（魅族 21 Note / Android 16），
> agent 只负责构建 + 安装（adb push + pm install，禁用 adb install）+ 分步验收清单。

## 项目是什么

中文古诗词·古文 Android 阅读器（Kotlin + Compose，minSdk 26 / target 36）。
定位：**一天一首诗，跟着节气过日子；把诗送进生活，而不是把用户拉进 App**。
无社区、无账号、无信息流、全离线。GitHub: 3beuml/jigu，Apache-2.0。

**全部产品决策见 `docs/DECISIONS.md`（D-001~D-042）**——动手前必读；`docs/REFERENCES.md` 含开源语料库与同类 App 数据源地图（D-036 调查专项）。
**产品需求事实源：`docs/REQUIREMENTS-2026-11-19-需求分析与路线图.md`**（用户访谈定稿：搜索=灵魂功能，四形态模型，几枝式首页，数据优先路线）。
**参考资料与参照 App 研究状态见 `docs/REFERENCES.md`**。

## 硬约束（违反即返工）

1. **数据版本单一事实源**：管线改 schema/内容 → build.py 的 SCHEMA_VERSION/DATA_VERSION 必须递增；
   build.py 自动产出 `assets/data/version.json`，App 从这里读版本号决定是否重拷库——
   **Kotlin 侧已无硬编码版本**（该坑已结构性消灭）。改完 schema 记得同步 build.py 里的建表语句。
2. **包名永不可改**：`com.jigu.app`。
3. **签名**：`C:\Work\keystore\jigu-release.jks` 唯一副本不可再生；凭据在项目
   `keystore.properties`（gitignored）；绝不入库、密码不进对话。
4. **令牌**：`C:\Work\.jigu_token.txt`（单仓库 Contents 权限），失效/用后由用户重新生成并吊销。
5. **装包**：`adb push + pm install`（MSYS_NO_PATHCONV=1），禁用 adb install。
6. **收录条例 D-010**：任何数据扩容先对照三层模型与五条条例（不收整本大部头、
   编者身份、公版、结构化可行、不重复）；《文字蒙求》因展示不可行已下架，勿加回。

## 数据管线（改数据必走）

```bash
python tools/datapack/build.py --dataset C:/Work/scratch/chinese-poetry   # 全量库 zip 进 assets
python tools/datapack/build_calendar.py                                   # 节气/节日 JSON
python tools/fonts/subset_font.py --font C:/Work/scratch/fonts/wenkai.ttf --supplement C:/Work/scratch/fonts  # 字体子集（--supplement 用 Jigmo 补 Ext-B+ 生僻字形）
python tools/datapack/test_pipeline.py                                    # 单元测试（必跑）
python tools/datapack/inspect_db.py --full -n 2                           # 抽查
```

数据集克隆在 `C:\Work\scratch\chinese-poetry`；`assets/data/` 为构建产物已 gitignore。

## 本机坑（新增，叠加 DSH skill android-build-install）

1. **assets 大改后增量打包残留旧数据**（APK 虚涨一倍）→ `clean assembleDebug`。
2. **数据集文件名是拼音**（shenglvqimeng.json），中文文件名会"缺文件"。
3. **自查脚本勿命名 inspect.py**——遮蔽 stdlib inspect（已改名 inspect_db.py）。
4. **大库解压在后台线程**，新 DAO 查询函数开头必须 `awaitData()`。
5. **Glance**：TextStyle.fontFamily 是枚举、Spacer 用 defaultWeight、actionRunCallback 在 appwidget 包。
6. **网络窗口**：GitHub/Met 时通时断；克利夫兰 API 稳定可用；推送失败本地 ahead 安全，恢复即补。
7. **千家诗组诗**段落是嵌套 dict（subchapter），装载器已展开。
8. **Git Bash 下 adb push 远端路径必须加 `MSYS_NO_PATHCONV=1` 前缀**：否则 `/data/...` 被 MSYS
   转成 Windows 路径，push 报"成功"但文件不在设备目标位置（pm install 报 Can't open file）。
9. **真机签名互斥**：debug/release 两签名包不能互相覆盖安装（INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
   真机现装 debug 签名 v0.2.0（2026-11-06）；换 release 版须先卸载（收藏清空，favorites 无导出）。
   另：Flyme 禁 run-as（DEBUGGABLE 包也报 unknown package）；adb 偶发 protocol fault 掉线，
   kill-server 重启即恢复。
10. **GitHub API**：匿名 60/hr 限额易尽（contents+search 同池）；`C:\Work\.jigu_token.txt` 是
    单仓库 fine-grained，**对公共大仓库无效**；>1MB 文件 contents API 返回空 content——
    改走 `git/blobs/{sha}`（先 contents 拿 sha）。网络窗口抖动用"探测循环+稀疏克隆
    （blob:none + 文件级 sparse-checkout）+ 限额重置定时"三接力（D-033 实战）。
11. **殆知阁 txt**：部分文件是 UTF-8-mojibake（GBK 误读）——`s.encode('gbk').decode('utf-8')`
    反向还原（含 PUA 时逐字兜底）；目录繁简混杂（諸子/诸子并存）、文件别名（商君书=商子、
    晏子春秋在史藏/传记、列子在道藏、春秋左传在儒藏/春秋）；白文本带【夹注】需剥。
    校验环范式：verify 脚本归一化（t2s+异体表+去标点）+ 双锚裁窗 + difflib（verify_j11.py）。
12. **Compose 导航可观察性**：`currentRoute(nav)` 直读 `nav.currentBackStackEntry` **不可观察**
    （route 启动定格后永不更新 → dock 子页不隐藏、tab 恒选中，D-025 起潜伏至 D-038 才爆）——
    必须 `nav.currentBackStackEntryAsState().value`。
13. **LazyColumn 分组头 key 重复必崩**（`Key "h_X" was already used`）：分组数据非连续出现
    同组名时 `sec != lastSection` 判重失效——分组模式先按组排序渲染 + seen 集合去重兜底
    （唐诗三百首体裁交错 208 处，D-038）。
14. **第二台手机 adb 装**：`INSTALL_FAILED_USER_RESTRICTED` = 机上"USB 安装"授权未开，
    adb 绕不过，须人在机上开（设置→开发者选项→USB 安装）；两台机分辨率不同（1080×2376 /
    ~1220×2800），**input tap 坐标不可跨机复用**。

## 构建与发版

```bash
export JAVA_HOME='C:\Program Files\Java\jdk-21'
./gradlew.bat --no-daemon assembleDebug        # 日常
./gradlew.bat --no-daemon clean assembleDebug  # assets 变更后必须 clean
python tools/release/upload_release.py --tag vX.Y.Z --apk app/build/outputs/apk/release/app-release.apk
```

版本号规则（D-009）：versionCode = major×10000+minor×100+patch；发版必升；
git tag 对应 GitHub Release（draft，正文用户自写）。CI：`.github/workflows/build.yml` push 自动构建。

## 当前待办

**跨会话交接先读：**（一眼现状/提交时间线/待验收清单/挂账隐患/下一步）。
见 docs/DECISIONS.md 尾部与 docs/REQUIREMENTS-2026-11-19-需求分析与路线图.md（产品需求事实源）。
现状摘要（2026-11-20 夜班）：库=39.73 万条（DATA 31 / SCHEMA 8，v0.2.2 未发 Release）。
已落地：D-043 R1/R2（搜索升级/诗人主页+关注）+ 批16 史记整本；D-044 R3 几枝化（诗历/搜索
前置/关注轻加权）；D-044a 作者索引逻辑修正（分栏域化+朝代时序）；D-045 R4 情境推送（心情/
状态/可选联网天气 open-meteo 城市制）；D-046 夜班批次（dock 全路由常驻仅卡片预览退场/R5 名句
体系分组收藏/R7 排版行距页边/R8 古文段落目录+续读/R9 书架长按置顶）。**需求路线图 R1-R9
全部交付**；美术终止（D-040）；吕览挂账；补编二期候专场。
**遗留**：favorites 跨版本 id 漂移隐患（follows 已按姓名重解析，favorites 未改）；名句 auto
精修；宋诗平仄/文白对照/五经整本等候选挂账；真机验收 D-044~D-046 三批（v0.2.2 已装 468QBGES222HV，2026-11-20 09:31）。
旧待办：纸纹质感、小传白话化三选一、favorites 跨版本 id 漂移隐患；TTS 已否决（D-013）。
