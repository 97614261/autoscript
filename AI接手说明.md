# 智构接手说明

以用户最新决定和 [AGENTS.md](AGENTS.md) 为准，不把历史阶段计划当作新的操作授权。

## 先读与定位

- 正式仓库是当前 autoscript，资料/ 中的参考工程不是开发目标。
- 阅读 [README](README.md)、[文档索引](docs/README.md) 和 [第九阶段代码收口](docs/verification/studio-stage9-closeout-2026-10-14.md)。
- Studio 源码与测试按职责分目录，package 未改；查找旧位置使用 [路径映射](docs/source-layout-map.json)。
- Android工程位于[android/](android/README.md)，Android Studio打开这个目录；根gradlew仅转发，根打包命令不变（[ADR0057](docs/adr/0057-contained-android-workspace.md)）。
- Rust源码、工具链、Cargo配置和忽略的缓存统一在[rust/](rust/README.md)；运行Cargo先进入该目录，契约资料根传`..`。Android从仓库根构建，原打包命令不变（[ADR0056](docs/adr/0056-contained-rust-workspace.md)）。
- 先检查 git status 和 git diff；工作区包含连续开发改动，未经要求不重置、提交或推送。

## 当前边界

- 当前最新批次是[识图后续1–6](docs/verification/studio-recognition-followup-2026-10-14.md)：专用结果读取、结构化色点/范围、颜色文字等待、原屏结果动作、真实逐字预览、共享预览期限与取消。新增运行契约见[ADR0110](docs/adr/0110-studio-recognition-results-and-preview.md)，Data旧预算不变。不把上一轮“已接线”当本轮或设备验收结论。

- 最新授权的识图1–6批见[识图代码补强](docs/verification/studio-recognition-completion-2026-10-14.md)和[ADR0109](docs/adr/0109-studio-recognition-workflow.md)：图像入口/来源/组件错误、像素灰度等待组合、结果读取、OCR配置，全部复用既有运行契约；仍只编译和静态检查，不能当设备验收或老版全功能对齐。

- 当前先完成Studio开发，只做源码及测试源码编译与静态检查；测试执行、Lint、完整打包、设备安装与独立Runner后置。build.cmd -Verify会测试并安装MuMu，当前不要运行，README的完整门禁命令也留待用户确认进入测试阶段。
- 本轮九阶段Studio开发收口见[第九阶段记录](docs/verification/studio-stage9-closeout-2026-10-14.md)，后续按[集中验收清单](docs/reference/studio-acceptance-checklist.md)由用户确认进入测试阶段。代码收口不是设备或发布验收，长期可选组件/独立Runner计划仍有独立缺口。设置基线及第八阶段恢复快照仍见[Studio项目设置](docs/guides/studio-project-settings.md)及[清理记录](docs/verification/studio-settings-cleanup-2026-10-14.md)。
- 可视化是唯一新项目入口；旧 Lua 编辑页已退休，历史 Lua 工程、备份和执行协议继续兼容。
- 首版 Root 自动化，免 Root、账号/市场/云端、插件流程模板库延期。
- 主机测试和 Debug 构建通过不代表设备布局、触点释放、灰度/OCR精度、厂商兼容或正式发布已验收。
- 旧工程转换器保持独立，未知语义保留并阻断编译；不能用空操作冒充转换成功。

## 数据和清理

资料/、截图/、用户工程及本地测试证据不自动删除，不执行私人脚本。
过期交接文档和废弃 UI 已按用户要求清理；删除范围和恢复方式见 [清理记录](docs/verification/repository-cleanup-2026-10-10.md)。当前手册、ADR、契约、构建脚本和活跃兼容逻辑保留。
