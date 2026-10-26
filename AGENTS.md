# AGENTS — 汲古（Jigu）

> 任何智能体在本项目开工前先读这份。**功能验收由用户本人在真机上做**（魅族 21 Note / Android 16），
> agent 只负责构建 + 安装（adb push + pm install，禁用 adb install）+ 分步验收清单。

## 项目是什么

中文古诗词·古文 Android 阅读器（Kotlin + Compose，minSdk 26 / target 36）。
定位：**一天一首诗，跟着节气过日子；把诗送进生活，而不是把用户拉进 App**。
无社区、无账号、无信息流、全离线。GitHub: 3beuml/jigu，Apache-2.0。

**全部产品决策见 `docs/DECISIONS.md`（D-001~D-011）**——动手前必读。
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
python tools/artwork/fetch_artworks.py --force                            # 节气配画（需外网）
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

见 `docs/DECISIONS.md` 尾部「当前遗留」。摘要：TTS 朗读、APK 瘦身、纸纹质感、
古文文体标注（222 篇）、名句表扩容（生僻字形已由 Jigmo 嫁接解决）、小传白话化三选一。
