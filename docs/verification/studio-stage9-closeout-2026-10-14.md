# Studio 第九阶段：开发收口

2026-10-14。范围是当前九阶段Studio开发计划的代码收口，不是发布验收，也不代表老版所有能力百分之百复刻。用户决定先开发，测试、Lint、完整APK构建和设备安装后置；独立Runner继续暂缓。

## 本轮修复

### 启动需求不再被本次未输出的触点积木误阻断

`RuntimeProjectPlan.fromVisualSnapshot`在核对生成代次、Lua摘要和source-map摘要之后，从真实位置记录读取插件/积木身份，再与正式积木参数对应。按下、移动、弹起及找图命中后的hold/pressRelease才要求持续单指特性；普通点击、长按和滑动不冒充持续触点。

完整编译仍保守统计所有已输出插件定义，不宣称分支可达性；单步只统计其独立生成物实际输出的身份，禁用子树不产生新需求。历史source-map缺entries时继续保守检查项目，但遵守祖先禁用；显式损坏位置、未知身份、坏层级明确报错，不当成空需求。没有搜索Lua、改项目能力声明或增加协议字段。运行服务仍是最终权限与设备能力校验者。

实现：`runtime/VisualInputRequirements.kt`、`runtime/RuntimeProjectPlan.kt`。

### 原生界面预览归编辑会话所有

既有Compose参数窗仍使用原来的Dialog管理；原生脚本界面预览补入同一会话的有界子窗口集合，与参数窗共同最多16个，不占运行变量观察窗的独立名额。截图/系统选择暂隐后恢复原View树，关闭会话清理并拒绝迟到注册。预览打开时，保存关闭编辑器要求先完成/关闭预览，与其他参数窗一致。

预览自行最小化和启动提交中的隐藏状态优先，方向/尺寸变化不重新显示已隐藏的提交窗；显示异常提供可关闭的提示，不改已保存设计。不调整Runner或实际运行UI管理器。

实现：`editor/EditorOverlaySession.kt`、`designer/ProjectInterfacePreview.kt`。

### 草稿和图片交接保护

- 无效项目标识的编辑打开请求不再无条件停止已有编辑服务；无会话时才退出。悬浮权限不足沿既有保存关闭请求处理，不以新请求直接替换旧草稿。
- 编辑服务截图交接、可视化截图选点及保留的项目工具截图路径，在IO解码内部登记位图所有权；即使回主线程前被取消，finally仍能释放未交付位图。已交付的位图由原窗口管理，所属临时截图继续清理。
- 读取页面参数的返回/窗外/标题关闭/取消统一经过未保存确认；超长变量名不再截取成另一名称，保持有界输入并明确拒绝，保存仍要求64字符以内的已声明字符变量。

### 纯视觉首页收口

首页仅接收Modifier、绘制灵动环画布，不再接收ProjectStore、运行状态及项目操作回调。删除4个无生产调用的旧首页标题/状态卡片/入口卡片/按钮私有函数（58行）、其专属样式/导入及旧homeStudioEntries；更新过时的“首页应有功能入口”测试。项目和我的的真实入口、最近项目工具、历史Lua兼容路由不删除。

## 代码路径核对矩阵

以下是源码入口/保护检查，不是设备操作结果。路径均相对Studio源码职责目录。

| 范围 | 核对的生产路径与约束 | 后续验收 |
|---|---|---|
| 首页/项目/我的 | MainActivity、StudioLandingScreens、StudioEntryModel、ProjectPage；纯视觉首页、一次性导航、可视化新项目、历史工程备份 | 返回栈、重复点击、授权拒绝、导入取消 |
| 编辑与运行 | EditorOverlayService/Session、VisualProjectScreen、ScriptLaunchOverlayService、StudioRunEntry、EditorRunReturnGate；编辑直跑保留窗、项目启动先配置、球只恢复编辑、停止独立 | Root等待取消、快结束脚本、暂停/继续、最小化及停止回配置 |
| 编辑数据/源码 | EditorDock、VisualEditorState、SelectedNodeSource、ImageBlockInsertion；参数与结构分离、连续操作、真实前插/子区、源码和加入前后上下文复核 | 嵌套复制/粘贴、撤销、陈旧草稿、恢复与源码往返 |
| 变量/流程/函数库 | VisualVariableCalculationDialog、VisualLoopDialog、FunctionCatalog/FunctionLibraryDialog；参数遮蔽优先级、循环输出、生成目录和只读文档 | 多插件参数/返回、专用循环、声明修改、分支行为 |
| 截图/图像 | VisualProjectScreen、ProjectToolScreens、ImageProcessingWorkbench、ImageRecognitionTestDialog、TestedImageRecognition；原像素、独立副本、有限历史、测试身份、整批校验/一次撤销 | 裁剪坐标、透明度、取消、保存重名、旋转、真实识别精度 |
| 视觉组件 | RuntimeProjectPlan、CompiledVisionRequirements、VisionProviderRepository及RuntimeClient；摘要绑定需求、准备/取消、选择不回退、导入不等于加载 | SDK初始化、坏选择、缺组件、不同ABI/API、首次和热启动 |
| 调试/日志 | RuntimeLogDialog、RuntimeLogLoadState、EditorExecutionTrail、VisualStepState；日志独立、高亮不依赖日志、错误分通道、有界复制/清理 | 动态提示高亮、提示停止清理、历史定位、日志布局与完整导出 |
| 界面设计/读写 | RunnerUiDesignerScreen、DesignerPropertyInputs、VisualUiParameter/CommandDialog、ProjectInterfacePreview；非法输入保留、固定提交、未保存退出、同一渲染器 | 容器/多页、事件和值读取、软键盘、横竖屏、预览会话寿命 |
| 项目设置 | ProjectSettingsDialog/Model、ProjectStore.updateExecutionSettings；原基线、冲突重读、能力/提示/随机策略原子保存，权限归我的 | 外部更新、并发保存、取消、失败重试、旧能力保留 |

## 清理恢复快照

本地忽略文件：`build/cleanup-backups/studio-stage9-home-before-20261014-0886c71f614849d69387b0a228340d5b.zip`，9,510字节。

包含首页清理前的完整混合文件，ZIP条目采用基本文件名：

- `android/apps/studio-android/src/main/java/com/autoscript/studio/navigation/StudioLandingScreens.kt`
- `android/apps/studio-android/src/main/java/com/autoscript/studio/navigation/StudioEntryModel.kt`
- `android/apps/studio-android/src/main/java/com/autoscript/studio/navigation/MainActivity.kt`
- `android/apps/studio-android/src/test/java/com/autoscript/studio/navigation/StudioEntryModelTest.kt`

SHA-256：`4299679D47CDF1C3C112992633099DF1568C616602BB0B887DAE360E4608979D`。先解压至独立临时目录，再只恢复需要的退役声明，不覆盖后续开发中的完整文件。没有删除项目、图片、脚本、模型、许可证或未知本地产物；工作树未替用户提交。

## 验证与边界

新增/更新回归源码覆盖真实输出触点需求、历史禁用子树、跨插件同名节点、损坏位置、摘要绑定、子窗口有界/清理/迟到注册、读取参数关闭确认、纯视觉首页及截图所有权。所有用例留待统一测试阶段执行，不以静态接线断言冒充真实窗口行为。

最终使用本地Gradle 8.14.3离线运行 `:apps:studio-android:compileDebugKotlin` 和 `:apps:studio-android:compileDebugUnitTestKotlin`，development配置，结果 `BUILD SUCCESSFUL in 2m 14s`；200个任务，3个执行、197个保持最新。仅有既有Android系统栏及软键盘API弃用警告。测试源码已编译，不代表测试已执行。

本批文件空白检查（含未跟踪源码/测试）通过；仓库布局检查通过：248个本地链接、0个local-only引用、22个Rust成员，归档未进入构建。没有运行单元测试、Lint、完整APK构建、安装或设备脚本；未提交Git。

后续执行顺序见[Studio集中验收清单](../reference/studio-acceptance-checklist.md)。九阶段开发收口与长期可选组件/独立Runner计划不是同一份完成率，后者的真实设备、包体、性能与峰值内存证据仍保留缺口。
