# AutoScript 给其他 AI 的接手说明

更新时间：2026-09-15

## 一、直接复制给新 AI 的提示词

```text
你要接手一个正在开发中的 Android 自动化脚本平台 AutoScript。

正式仓库：C:\Users\TY-04\Desktop\autoscript
当前分支：wip/m27
当前基线提交：7ecc784（WIP: M27 UI 对齐现场快照，未验收）
仓库内参考资料：C:\Users\TY-04\Desktop\autoscript\资料

开始前必须完整阅读：
1. AGENTS.md
2. AI接手说明.md
3. 项目交接与后续计划.md
4. README.md
5. docs/m27-ui-plan.md
6. docs/reference-screen-source-map.md

这是正式项目，不要重新搭架构，也不要改写成另一个项目。技术栈固定为 Kotlin/Compose Studio 壳 + safe Rust 引擎 + PUC Lua 5.4 + Rust 像素视觉/基础字库 OCR；OpenCV 与 ONNX 只作为后续窄适配层。首阶段只做 Root，minSdk 24，arm64-v8a 首发，x86_64 用于 MuMu。

当前目标是收口 M27 UI，先跑通和测试完整流程，再补齐全部脚本能力。

Git 现场：
- M27 大量代码已经保存在 7ecc784 快照提交中；
- 在这个快照之上，还有一整批未提交的 M27 页面对齐改动：24 个已跟踪文件被修改，1 个文件被删除（已 git rm 的 ProjectFilesScreen.kt），另有 30 个未跟踪新文件（25 个矢量 drawable、ProjectSourceFiles.kt、LegacyFunctionCatalog.kt、ProjectSourceFilesTest.kt、docs/m27-page-alignment.md、本文件）；
- 这批改动按 docs/m27-page-alignment.md 的 B0–B5 批次逐页对齐了新版易编精灵与旧版一键玩的源码，并把源文件管理、文件弹窗、备份槽位从内存态/假数据接到了真实 ProjectStore；
- Flow 路径策略已改为“单层 + 允许中文名”，分组是虚拟视图（.studio/source-groups.json），flow-ir、ProjectStore 和 project.schema.json 三处规则已对齐，cargo test -p flow-ir 通过；
- 这批改动从未执行过 Gradle，Kotlin 能否编译尚未验证，这是下一步第一道门禁；
- 资料/ 是大型未跟踪参考资料目录，绝对不要执行 git add .，也不要把 APK、JAR、旧工程和反编译文件误提交到 Git。

禁止：
- git reset --hard、git checkout -- .、git clean -fd；
- 删除未跟踪资料或覆盖用户改动；
- 全仓库机械格式化；
- 用户没有明确说“构建/打包”时运行 Gradle；
- 未经允许 commit、push、改远端或引入在线服务；
- 绕过 Schema/能力契约直接向 Lua 暴露 Java、Root、shell 或 native 指针；
- 把 资料/自动化平台新项目 当成当前正式工程。

你的第一批任务顺序：
1. 只读检查 git status、git diff，并读 docs/m27-page-alignment.md（第 0、4、6、7 节是当前进度真相）；不要丢弃任何未提交改动。
2. 下一步是第一次 Gradle 构建，必须先得到用户明确许可。获准后运行 scripts/m27-gate.ps1，把编译错误逐个修掉——这批 Kotlin 代码量很大且从未编译过，预期会有一批 import/签名类错误，属于正常收尾，不要因此推翻设计或重写页面。
3. 构建通过后在 MuMu 127.0.0.1:16384 跑 scripts/m27-ui-smoke.ps1，再做项目交接文档第 9 节的 16 项手工 UI 验收，产出截图目录和 JSON 报告。smoke 需要工作台里已有名为 VisualSmoke 的可视化项目，且至少有一个积木节点。
4. 验收中发现的视觉差异按 docs/m27-page-alignment.md 的逐页表格修，不要凭截图猜，行为必须同时核对参考源码/XML。
5. M27 完成后，先核实并修复三个阻塞完整流程的问题：Timer.every 回调完成路径、StopPlan.release_pointer_ids 被 JNI 丢弃、InputArbiter 未接入真实输入路径。
6. 然后跑通“创建项目→编辑→保存→Flow 编译→Root 截图/识别→输入→日志→备份→release→独立 Runner”的完整流程，最后才进入 M28 全能力补齐。

UI 参考规则：新版主页面看 资料/易编精灵/10_页面布局 和截图；旧版悬浮程序树、函数/插件弹窗看 资料/易编精灵/16_yijianwan分析、17_yijianwan截图、18_插件弹窗。行为必须同时核对源码/XML，不能只凭截图猜。

请先回复：
- 你识别到的正式仓库、分支和 HEAD；
- 当前未提交改动的范围；
- M27 当前准确状态；
- 你准备执行的第一批只读检查；
- 明确承诺现在不构建、不清理、不提交。

在完成这份复述前不要修改代码。
```

## 二、当前真实状态速记

| 项目 | 当前状态 |
|---|---|
| 正式仓库 | `C:\Users\TY-04\Desktop\autoscript` |
| 分支 | `wip/m27` |
| HEAD | `7ecc784` |
| 快照 | M27 UI 现场已做本地 WIP 提交，尚未验收 |
| 未提交改动 | 24 个已跟踪文件修改 + 1 个删除（`ProjectFilesScreen.kt`，已 `git rm`）+ 30 个未跟踪新文件；内容是 M27 页面对齐 B0–B5 |
| 未跟踪目录 | `资料/`，仅供参考，不应直接提交 |
| 最近构建 | **从未构建**。`cargo test -p flow-ir` 通过；Gradle 未执行，Kotlin 是否可编译未知 |
| 下一门禁 | 用户授权后执行 `scripts/m27-gate.ps1` |
| 下一设备 | MuMu `127.0.0.1:16384`，API 32、x86_64、720×1280 |
| 逐页进度 | 见 [`docs/m27-page-alignment.md`](docs/m27-page-alignment.md) 第 4 节状态列 |

## 三、为什么不能只发一句“继续开发”

新 AI 如果只看到“继续开发”，很容易发生以下错误：

- 在 `资料/自动化平台新项目` 旧工程里修改，而不是正式仓库；
- 看到旧交接文档里的 `main/63e255d`，误判当前分支；
- 对 `wip/m27` 执行 reset 或重新生成项目，丢掉快照之后的修改；
- 把几百 MB 的 `资料/` 加入 Git；
- 把内存态源文件对话框误判成真实持久化；
- 未经允许直接跑 Gradle；
- 在已知 Timer、触点释放和输入仲裁问题未处理时宣称完整流程通过。

因此每次换 AI，都应发送第一节的完整提示词，并要求它先复述现场再动代码。

## 四、文档优先级

发生冲突时按以下顺序判断：

1. 用户当前最新明确要求；
2. 仓库根目录 `AGENTS.md`；
3. 本文件记录的当前 Git 现场；
4. `项目交接与后续计划.md` 的架构、进度和验收清单；
5. `README.md` 的能力总览；
6. `docs/` 中的专项说明；
7. `资料/` 中的设计、旧工程和参考产品分析。

`资料/` 是证据和历史输入，不是当前实现的架构真相。

## 五、每次交接都要更新的四行

交接前重新执行只读检查，并更新：

```text
当前分支：wip/m27
当前 HEAD：7ecc784（WIP: M27 UI 对齐现场快照，未验收）
未提交源码：M27 页面对齐 B0–B5 + 源码逐页核对 + 截图比对修正；详见 docs/m27-page-alignment.md
最后一次通过的构建/设备报告：2026-09-16 build\m27-20260916-094738\，主机门禁全绿 + MuMu UI smoke 8/8；
                              手工验收未做，清单见 docs/m27-manual-acceptance.md
```

如果没有报告文件，只能写“文档记载曾通过”或“尚未验证”，不能凭印象写“已完成”。
