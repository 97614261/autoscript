# Studio 旧界面入口盘点

更新：2026-10-02 已执行归档和活跃 UI 改名。下文是 2026-10-01 盘点记录，原清单的 2 个弹窗、13 个小组件已从生产源码移出；额外归档仅依赖废弃 Lua 标题栏的 1 个按钮组件和 5 个配色常量，合计 14 个组件。另归档 Lint 与引用核对确认未用的 27 个 drawable、2 个 string（1 个 values 文件）。当前清单及恢复方式见 [归档说明](../archive/studio-ui/2026-10-02/README.md)。没有永久删除实现或用户数据。

活跃命名已调整：`EditorDock.kt`、`PluginFileManagerDialog.kt`、`EditorPromptDialogs.kt`、`FunctionCatalog.kt`；函数库入口为 `FunctionLibraryDialog`。Lua 调试、兼容解析和有入口的预留页面继续保留。

日期：2026-10-01。范围：`apps/studio-android/src/main/java/com/autoscript/studio` 的生产源码，结合主导航、项目编辑入口和弹窗分派；不是设备逐页点击测试。调用检查包含普通调用、尾随 lambda 和函数引用，文档注释中的名字不算调用。没有删除代码或用户数据。

## 结论与计数

- **2 个没有入口的旧弹窗**：可列为后续清理候选，不代表两个完整页面文件都能删除。
- **13 个没有调用的 UI 小组件**：不是 13 个页面；分散在仍被使用的源码文件里。
- **1 套 Lua 编辑分支仍有入口**：保留作源码调试，不是废页面。其中 4 个旧子面板不走当前可视化主流程，见下表。
- 当前可视化编辑器的旧全屏诊断界面已被之前批次移除，不计入现存无入口页面。

### 无入口弹窗：2 个

| 函数 | 所在文件 | 说明 |
| --- | --- | --- |
| `LegacyInputDialog` | `LegacyPromptDialogs.kt` | 旧单行输入弹窗，只有定义和注释，没有生产调用；同文件里的提示、选项弹窗仍在使用。 |
| `RecognitionPreviewDialog` | `VisualImageRecognitionDialog.kt` | 旧图像预览/拖动选区弹窗，没有调用；正式图像配置和图片工具仍在使用。 |

### 无调用小组件：13 个

| 文件 | 函数 | 数量 |
| --- | --- | --- |
| `EditorEntryDialogs.kt` | `DenseVariableSelector`、`DenseRuntimeMode`、`SectionCard`、`SettingSwitch`、`EntryEditField`、`CompactChoice`、`RadioLine` | 7 |
| `LegacyScriptDock.kt` | `LegacyDialogTitle`、`LegacyDialogRows`、`LegacyValueBox`、`LegacyDialogControl` | 4 |
| `LuaEditorScreen.kt` | `LuaWorkbenchHeader` | 1 |
| `ProjectSettingsDialog.kt` | `ProjectSettingsButton` | 1 |

这些文件主体均有调用，不能整文件删除。`editor_float_ball_logo.xml` 是已被灵动环替换的旧图标素材，不是页面。

### Lua 调试分支：仍保留，不混入可视化主入口

`ProjectPage` 在项目模式为 `LUA` 时仍进入 `LuaEditorScreen`；它的源码编辑、文件选择和工具弹窗有真实入口。

| 旧子面板 | 当前可视化对应入口 | 状态 |
| --- | --- | --- |
| `ImageRecognitionPage` | `VisualImageRecognitionDialog` + 图片工具联动 | Lua/缺少可视化宿主回调时的旧兼容分支。 |
| `DataBackfillPage` | `VisualDebugInspector` | 可视化宿主通过 `onOpenDebugTool` 转向正式调试面板；旧通用回填页仍是兼容分支。 |
| `VariableCheckPage` | `VisualDebugInspector` | 旧页显示未接入说明，不应当作已完成变量检查。 |
| `RuntimeVariablesPage` | `VisualDebugInspector` | 旧页显示尚未开放说明；可视化已接真实 Runtime 快照。 |

## 名称旧，但当前实际使用的界面

- `LegacyScriptDock`：当前正式可视化编辑弹窗和吸附球，不是废页。
- `LegacySourceManagerDialog`：标题点击后的插件文件/分组选择，不是旧源码管理废页。
- `LegacyFunctionLibrary`（实现位于 `FunctionLibraryDialog.kt`）：当前函数库和详情入口。
- `EditorEntryDialog`：仍承载共享工具、判断、跳转、调试等入口；不能整块删除。
- `ProjectPage` 中 `floatingEditorProject` 的旧编辑宿主还有文件打开等兼容调用，不能仅因主编辑入口改为直接弹窗就删除。
- 项目设置、资源管理、图片工具、界面设计器、脚本界面预览、备份和打包界面仍有入口。

## 预留/说明页，不等于无人调用

学习项目、开发者后台、登录/注册、更新等说明或边界页面有导航入口；部分功能尚未接服务端或真实录制输入流。它们属于产品范围中的预留边界，不列入上面的 2 个无入口弹窗，也不能据此宣称后台/登录/录制功能已完成。

## 后续建议（本批不删除）

1. 先清理这 2 个无入口弹窗和 13 个无调用小组件，连同只被它们使用的私有状态/导入；保留所在文件的活跃部分。
2. 将实际活跃的 `Legacy*` 改为清晰的编辑器/插件命名，降低维护误判，协议与持久化字段不变。
3. 等 Lua 调试入口确定退出产品后，再删除 Lua 编辑分支和它专用的兼容面板；现在不要删。
4. 预留说明页是否隐藏需要单独产品决定，不在本次运行控制修复中变动。

## 本批运行控制修复

- `RuntimeClient.startProject` 先读取状态，再通过 StateFlow 等待 Root 就绪通知；最多 20 秒（服务启动期限 15 秒），取消/断连/会话替换/失败终止等待，不进行定期轮询。
- 系统浮球与编辑器浮球使用灵动环；点击展开控制，正常运行不显示相机；系统浮球拖动后贴边，展开与收起保持同一侧。
- 编辑器增加普通暂停/继续路径，单步继续仍校验项目与调试会话。底栏暂停与停止分开。
- 验证包含 Runtime 客户端与服务的单元测试、Lint，独立 Runner Kotlin 编译，以及 `build.cmd -Verify` 的 Studio 测试/Lint/Debug 构建和 MuMu 0 覆盖安装。行为仍需用户在设备上确认，不把源码判断当作首次启动问题已实测消失。
- 最终完整构建成功（1 分 54 秒），MuMu 0 安装成功且保留数据；Studio/Runtime 客户端/服务 Lint 0 错误。新启动等待测试 3 项、浮球状态测试 2 项、品牌资源测试 3 项均通过。
