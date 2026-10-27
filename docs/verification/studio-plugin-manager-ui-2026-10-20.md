# 插件管理弹窗更新 · 2026-10-20

## 当前方案（用户再次调整后）

- 将 `EditorDock` 内旧 P06 大标题、八行单选和取消/确定底栏替换为 `project/PluginManagerDialog.kt`，使用共享 `StudioPanelDialog`。
- 恢复原八项及顺序：插件创建、插件删除、插件另存、插件检错、全部插件检错、插件分组、存储为模板、未调用插件；上方显示当前插件和总数，说明点击展开。
- 不再使用双列大卡片或分区占位，使用 32dp 最小行高、12sp 主文字、16dp 图标的多行单列列表。共享标题栏改为紧凑模式；简短说明在宽度/字号允许时同行显示，未开放状态始终可见。大字号允许按内容增高，有界滚动不裁切。
- 删除仅进入既有插件列表选择流程，原删除确认和引用/主插件保护不变。新建/另存/分组仍由既有项目事务执行。
- 两个检错入口分别保留，均沿用原有项目级 Rust 编译校验，不冒充新增单插件隔离编译。“存储为模板”恢复显示但仍为“后续开放”的禁用状态，不新增延期模板能力。
- 不改持久化身份、插件格式、运行、API 或 Rust；不操作私人脚本。

## 本轮验证

- JVM 测试更新为八项原顺序、七项有效动作、模板永不派发、全局禁用及删除确认文案。
- `pluginManagerOnly` 更新为八项紧凑行、说明展开/收起、模板可见禁用、七项独立回调、全局禁用、关闭及 150% 字号布局。
- `build.cmd -Verify` 和 `assembleDebugAndroidTest` 通过。Studio 单测 837 项、0 失败/错误、1 项既有跳过，本批 3 项更新测试全部通过；Lint 0 errors、37 warnings、14 hints（既有项）。共享测试、APK/ABI及仓库引用检查通过，MuMu 0 更新安装成功并保留数据。
- MuMu 0（API 32、x86_64、720×1280）本轮 12 项检查全部通过，`cleanup=true`。已查看真实截图，正常及 150% 字号八行全部可见，无文字遮挡；模板保持禁用，七项有效回调互不混淆。
- 当前安装包 182015965 字节，ZIP 布局/签名/填充 149256 字节；无独立 Runner 内嵌、未另打 ARM 候选包、未更换模型。
- 本轮日志 `build/plugin-manager-rows-build.log`、`build/plugin-manager-rows-test-apk.log`、`build/plugin-manager-rows-mumu.log`；截图 `build/plugin-manager-rows-compact.png` 与 `build/plugin-manager-rows-large-text.png`。测试只检查管理入口，不执行删除或私人脚本。

## 首轮历史验证（仅对应已被替换的六卡片版本）

- `build.cmd -Verify` 通过：Studio 单测 837 项，0 失败/错误，1 项既有跳过；本批新增 3 项通过。Lint 为 0 errors、37 warnings、14 hints（既有项）。共享测试、APK ABI/布局、仓库引用检查及 MuMu 安装通过。
- `assembleDebugAndroidTest` 通过；MuMu 0（API 32、x86_64、720×1280）`pluginManagerOnly` 10 项实际用例全部通过，`cleanup=true`。查看正常/150% 字号截图确认可见文字、操作卡片和固定关闭按钮无遮挡；大字号内容使用独立滚动区，本批用例验证单列重排和关闭，未逐项滚动点击大字号末项。
- 这批设备测试验证入口回调与布局，不执行删除或私人脚本；项目事务和删除保护沿用原实现。未另打 ARM 候选包，未宣称真机验收。
- 本地产物：`build/plugin-manager-build.log`、`build/plugin-manager-test-apk.log`、`build/plugin-manager-mumu.log`；截图 `build/plugin-manager-compact.png` 与 `build/plugin-manager-large-text.png`。
