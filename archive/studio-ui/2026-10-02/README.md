# Studio UI 归档 · 2026-10-02

这里保存确认没有生产入口的旧 UI 实现与资源，方便后续整批删除。**不参与 Gradle/Cargo 编译，也不打入 APK。** 不包含用户脚本或项目数据。

## 已归档

- 2 个旧弹窗：`LegacyInputDialog`、`RecognitionPreviewDialog`。
- 14 个 UI 小组件：上次盘点的 13 个，加上仅被旧 Lua 标题栏调用的 `LuaWorkbenchAction`。
- 5 个只被旧 Lua 标题栏使用的 `LuaChrome*` 配色常量。
- 27 个无引用的 drawable XML（包含被灵动环替换的旧浮球图标）。
- 1 个 values XML，包含 2 个未使用的预览文案。

`fragments/*.kt.txt` 是从仍活跃的 Kotlin 文件中抽出的原始声明与原 import 清单，不是可独立编译的 Kotlin 文件；其私有辅助依赖有些仍留在生产源码中。不要直接改扩展名放进 sourceSet。完整文件/声明清单、原位置和 SHA-256 在 [manifest.json](manifest.json)。

资源先经过 Lint `UnusedResources` 检查，再核对 Kotlin、XML、测试引用以及动态资源加载。没有依靠文件名或“最近没点过”判断废弃。`imageRecognitionDragRegion` 虽然旧预览弹窗不再使用，但还有测试覆盖，保留为图像坐标转换工具；真实图片预览加载器也仍在使用。

## 活跃文件规范命名

| 原文件 | 当前文件 | 当前作用 |
| --- | --- | --- |
| `LegacyScriptDock.kt` | `EditorDock.kt` | 正式编辑弹窗、吸附球与运行控制。 |
| `LegacySourceManagerDialog.kt` | `PluginFileManagerDialog.kt` | 插件文件与分组选择/管理。 |
| `LegacyPromptDialogs.kt` | `EditorPromptDialogs.kt` | 共用提示、选项弹窗。 |
| `LegacyFunctionCatalog.kt` | `FunctionCatalog.kt` | 实际积木与脚本 API 的函数目录。 |

`FunctionLibraryDialog.kt` 原文件名已经规范，入口由 `LegacyFunctionLibrary` 改成 `FunctionLibraryDialog`。相关 UI 类型和调用方同步改名，例如 `EditorTreeNode`、`EditorToolPanel`、`PluginManagerAction`、`FunctionLibraryEntry`。详细映射也在 manifest 中。

旧格式解析、旧参数转换等真正的兼容逻辑继续保留 `legacy` 名称。没有更改项目格式、flowId、节点 kind、协议、编辑命令前缀或保存状态的版本/键名。

## 没有归档的内容

- Lua 编辑及其专用旧兼容面板：还有真实调试入口。
- 正式可视化编辑、共享工具/调试、函数库、插件选择、图片工具、设计器、项目设置、打包/备份。
- 学习、账号、开发者后台等有入口的说明/预留页面：不因为服务端未实现就删除路由。
- 有测试、生成器、XML 或动态注册引用的代码/资源；未把整个工程里“当前没有执行”的后端模块视为废弃。

## 恢复与删除

恢复时按 manifest 的原位置与改名映射，把所需声明放回当前活跃文件，核对其依赖和 import，再执行 `build.cmd -Verify`。资源按原 `src/main/res` 路径恢复。

确认不再需要后，可整目录删除本归档，不影响生产依赖；`UiArchiveTest` 在整目录不存在时跳过归档完整性检查。若只删除部分归档文件，需同步更新 manifest，否则完整性测试会报错。归档目录不得加进 sourceSets 或模块列表。

## 验证

`build.cmd -Verify` 成功（1 分 36 秒）：Studio 单元测试、Lint、Debug 构建全部通过，MuMu 0（`127.0.0.1:16385`）覆盖安装成功，保留数据。归档回归测试 3 项通过；Lint 0 错误、`UnusedResources` 0 项。没有进行人工逐页点击实测。
