# Studio 旧页面及关联死代码清理

2026年10月20日。按用户“没用的页面都删了”“还有相关没用的代码”的要求，删除已确认没有生产入口的旧页面、分支和资源。沿用 ADR0126 的单编辑入口，不改变项目格式、执行协议或识别算法。

## 本轮删除范围

上一轮已删除旧“图片与标注”页 `ImageToolsScreen`，图片库改用真实悬浮资源浏览器。本轮继续清理关联代码，没有重做图片库或再增加替代入口。

- `EditorEntryDialogs.kt` 中不可达的旧文件管理、工具设置、图像识别占位页，以及专属列表、选择框、默认假数据和布局帮助函数。
- `EditorDock.kt` 中没有生产调用的本地内存树、旧节点生成/编码/解码、私有 Saver、自造命令分发与内外两套编辑分支。现在必须传入正式可视化节点；保存和校验仅禁用操作，不关闭连续编辑窗。
- 上述旧文件页专属的目录模型、文件打开/删除回调和重复目录扫描。共享路径边界函数保留，`ProjectFileCatalog.kt` 收敛为 `ProjectFilePaths.kt`，不削弱 canonical 目录包含检查。
- 没有调用方的旧 `RuntimeAudioImporter.kt`、旧首页最近项目计算、只供旧测试使用的菜单模型、识别/取色/选区包装函数和旧变量提示生成器。仍在使用的提示解析器保留。
- 14个在 Android 生产代码、XML 和测试中均无使用方的 drawable XML：`editor_annotation_24`、`editor_capture_camera_24`、`editor_file_move_24`、`editor_file_paste_24`、`editor_image_processing_24`、`editor_menu_24`、`editor_recognition_preview_24`、`ic_arrow_right_24`、`ic_content_cut_24`、`package_folder_open_20`、`package_java_plugin_24`、`visual_redo_24`、`visual_save_24`、`visual_undo_24`。

按本轮16份生产 Kotlin 快照逐文件对照，`ReadAllLines` 物理行统计为10492行至9035行，净减少1457行，不计测试和文档。此统计不包含上一轮已经删除的页面。

只删除测试中的旧实现专属用例，保留有效行为及路径安全测试，增加退休声明、资源和正式程序树约束。历史目录映射同步记录两项明确改名，`SourceLayoutTest` 仅允许这两项固定改名，仍校验186个目标唯一、存在、包名及目录边界；未禁用测试或放开任意重命名。

## 明确保留

真实图片库、图片工作台、截图、录制、插件管理、项目资源删除、项目音频资源及播放继续使用现有入口。函数库、变量、循环、判断、跳转、调试、源码编辑以及生产悬浮编辑会话仍保留。

旧项目备份、受限源码往返、历史提示解析、运行引擎和协议兼容不是死代码，不因页面退休而删除。Manifest 声明的 `MainActivity` 即使没有普通 Kotlin 调用方，也保留为系统入口。没有删除用户项目、图片或脚本，没有改动 Rust/API/model 或默认 full 视觉配置，也没有构建独立 ARM 候选包。

## 构建与静态验证

- `build.cmd -Verify` 最终通过，开发 APK 已更新安装到 MuMu 0，保留应用数据；包布局、原生库和仓库引用检查通过。
- Studio 单测841项，0失败、0错误、1项既有跳过。首次验证因历史目录测试未识别文件改名失败，修正固定白名单后重跑全部通过，不把首次失败当作通过。
- Lint 为0错误、33警告、11提示；不是零警告。专项 Android 验收包重新构建通过。
- 验证日志：`build/studio-unused-ui-build.log`、`build/studio-unused-ui-final-build.log`、`build/studio-unused-ui-test-apk.log`、`build/studio-unused-ui-test-apk-final.log`。

## MuMu 实际回归

MuMu 0，API32，x86_64，720×1280。仅使用自有公开合成 QA 工程，不运行私人脚本；每组最终结果均报告 `pass=true`、`cleanup=true`、`INSTRUMENTATION_CODE: -1`。共13项实际用例：

- `dialogsOnly` 五项：真实日志/记录设置/函数库的全屏还原；变量嵌套草稿与确认放弃；循环预设和取消；图像配置全屏及取消；源码草稿全屏保持与确认放弃。未把本轮跳过的横竖屏测试算作通过。
- `imageLibraryOnly` 七项：三次真实编辑入口打开和返回；目录切换；搜索与工作台返回保持；导入打开目录选择而非项目设置；返回后编辑器可继续操作且项目未变化。导入用例取消系统选择，不冒充完成图片文档导入。
- `editorControlOnly` 一项组合用例：自有脚本运行球展开/收起，两轮编辑窗打开、最小化、球恢复、关闭交接，涵盖运行及暂停状态；运行提示保持，停止后清理球和提示。

控制球组合用例首次在打开编辑器之前失败：系统报告的原生窗口已移位，Accessibility root 仍保留旧坐标及不可见标记。只修正验收包的坐标读取：新版系统仅对本包原生 `LinearLayout`、与系统窗口等尺寸的根节点按观测差值平移；API24/25 保留既有所属窗口处理，不扩大到 Compose 弹窗或其他包，不猜偏移、不缩放，仍要求真实点击后状态变化。正式 App 没有因此变更运行球行为；重跑组合用例通过。

设备日志：`build/studio-unused-ui-dialogs-mumu.log`、`build/studio-unused-ui-image-library-mumu.log`、`build/studio-unused-ui-editor-control-mumu.log`（首次失败）、`build/studio-unused-ui-editor-control-mumu-final.log`（重跑通过）。测试工程均已清理，独立验收包已移除，恢复智构主界面，没有清除正式 App 数据。

## 恢复与范围边界

删除前的本轮生产源码及14个图标快照在忽略目录 `build/studio-unused-ui-before-20261020/`。`main/` 按原相对路径保存，`res/drawable/` 保存原图标；可用于人工恢复本次清理前内容，后续清理 build 目录会删除这些快照。没有执行 Git 提交。

本次结果是已确认无生产入口的旧页面及其关联代码完成清理，不宣称整个仓库不存在任何其他冗余代码。仍在使用的共享组件和兼容逻辑不会仅凭名称含旧版词语或普通文本引用次数少而删除。

## 第二轮复查

同日按用户“再过过”复查 Studio 页面入口、成员方法、Manifest 系统入口及 Android 代码/XML 的资源引用。没有再确认可删除的旧页面或 drawable；继续移除8个正式界面没有调用的旧方法及一个废弃枚举值，生产 Kotlin 额外净减52行：

- 删除 `RuntimeProjectPlan.forSingleStep`。这是通过字符串替换生成 Lua 来设置检查点的旧方案；当前单步使用 `compileVisualNodeTest` 独立编译所选积木及子树，读取独立生成物并校验项目、插件、节点及摘要。本次没有改变现行单步行为，也没有删除编译器和引擎的调试兼容。
- 删除 `StudioRunEntry.opensInterface` 和未使用的 `LAUNCH` 值。项目启动仍由真实配置窗口服务处理，编辑运行和单步仍明确区分；测试改验这两条真实接线，不再只测试无人调用的判断函数。
- 删除 `VisualEditorState` 的 `selectionSubtreeSize`、`elseIfBranchCount`、`flowCallTarget`、`flowCallArguments`、`updateFlowCall` 五个包装方法。测试直接验证当前复制选区、节点参数读取及通用参数修改接口，并补查参数深拷贝不被调用方后续修改污染。
- 删除没有使用方的 `VisionRunStatistics.displayAlgorithm` 旧标签映射，统计解析及实际日志展示保持；同步修正 `EditorDock` 中仍描述旧 Lua 页面和旧图片入口的注释。

Android 生命周期/布局回调、仍用于结构操作测试的分支增删、构造合成工程的 `insertNoop`、高亮队列预算检查及历史 Lua 计划兼容均保留，不将测试支撑和系统回调当作废弃 UI。没有修改运行格式、Rust 层、模型、依赖或用户数据。

本轮 `build.cmd -Verify` 通过并更新安装到 MuMu 0。Studio 单测839项，0失败、0错误、1项既有跳过；相对首轮减少的两项仅验证已删除的旧 Lua 字符串改写方法，独立编译单步的摘要、陈旧上下文和原生成物不改写测试仍保留。Lint 仍为0错误、33警告、11提示；专项验收包构建、包布局、仓库引用及空白检查通过。

本轮 MuMu 0（API32、x86_64、720×1280）默认验收9项全部通过，报告 `pass=true`、`cleanup=true`、`INSTRUMENTATION_CODE: -1`：编辑窗初始展开、最大化还原、更多菜单稳定、最小化恢复、空闲所选积木单步、运行高亮/暂停单步/继续、停止保留最小化、关闭窗口与球、Root 点击及新鲜截图。只操作自有合成工程，测试后清理该工程并移除验收包，恢复智构首页。

本轮修改前11份源码/测试快照在忽略目录 `build/studio-unused-ui-round2-before-20261020/`，按仓库相对路径保存，可恢复本轮修改；清理 build 会删除快照。日志为 `build/studio-unused-ui-round2-build.log`、`build/studio-unused-ui-round2-test-apk.log` 和 `build/studio-unused-ui-round2-mumu.log`。未执行 Git 提交。

## 第三轮详细复查

同日按用户“再详细过一遍”继续检查入口、弹窗回调、编辑命令发出方、资源及测试辅助代码。Studio 的218份生产 Kotlin 文件纳入保守文件引用分析，入口取自主清单的主 Activity、编辑服务、配置服务和系统请求 Activity。所有文件均可关联到入口，但同名符号会扩大候选引用，因此这个结果不能证明每个方法和分支都实际可达；本轮继续人工追踪了关键分发链。

| 静态纳入区域 | Kotlin 文件数 | 重点核对 |
| --- | ---: | --- |
| 导航 | 11 | 首页与我的真实入口、一次性跨页请求、系统入口 |
| 项目 | 16 | 插件列表、资源及历史工程备份，不混入第二套编辑器 |
| 编辑 | 53 | 标题栏、侧栏、底栏、连续编辑操作及命令发出方 |
| 图像 | 60 | 图片库到工作台、截图取材、配置回填和识别窗口 |
| 界面设计 | 17 | 保留实际设计器和共享窗口 |
| 按键 | 6 | 保留真实选点、录制及权限回调 |
| 运行与日志 | 35 | 变量检查、独立观察窗、运行计划及兼容逻辑 |
| 打包 | 13 | 保留本地模板/组件及真实导出路径 |
| 共享 UI | 7 | 悬浮宿主、关闭/全屏及 Activity 预览边界 |

### 发现与清理

发现旧 `DATA_BACKFILL` 编辑命令只剩声明和处理器，18项现行编辑按钮均不发出该命令。其处理器可设置旧模式，但普通用户已经没有入口；相关辅助函数被旧测试引用，简单的引用计数不能发现这条无入口链。

旧 `VisualDebugInspector` 又在变量检查和运行变量模式开头直接转发到新窗口，后面的旧变量检查分支不可能执行。删除这套旧数据回填窗、重复检查/观察分支、所属 `onBackfill` 回调、状态及两项旧枚举值。变量检查直接调用既有 `VariableCheckDialog`，运行变量继续使用原有独立观察窗；没有减少现行菜单、积木参数编辑、变量声明、图像取材回填或调试协议能力。

仍在使用的 `visualVariableWarnings` 移至职责明确的 `VisualVariableWarnings.kt`，函数内容与清理前逐字比对（仅规范行尾）一致。源码映射记录第三项明确改名，目录测试仅追加这个固定名称，保留186个目标及目录边界校验。另清理9个生产文件中14条未使用导入，修正录制/截图入口的过期注释。与本轮13份生产源码快照对照，净减少222行 Kotlin。

新增“每个编辑命令必须有真实工具栏发出方”的检查，移除只验证旧回填器的专属用例；运行变量的会话变更、迟到读取、空值/不支持值/截断展示测试继续保留。新增真实弹窗回归，明确验证变量检查及独立观察窗，不用旧辅助方法通过来冒充页面可用。

### 保留项与边界

Manifest 系统入口、Android 生命周期/布局回调、历史 Lua 计划和协议、受限源码解析、路径安全与快照身份检查、测试合成工程构造及高亮队列预算检查均保留。已有一次性导航请求、明确延期的 AI/插件模板库及未开放下载器按既有产品约束处理，不为清理恢复旧页面、开放服务或删除稳定预留接口。

Android 生产代码及 XML 资源复查未发现额外无引用 drawable。文件级引用分析和关键路径检查未再确认其他可删除的孤立页面；这不等同于全仓库、全部配置和反射/JNI 入口的不可达证明。没有改动 Rust/API/模型、用户项目或资源格式，没有构建独立 ARM 候选包，也没有执行 Git 提交。

### 本轮验证与恢复

- `build.cmd -Verify` 通过并安装到 MuMu 0，保留正式 App 数据；单测839项、0失败、0错误、1项既有跳过。测试总数不变：移除1项旧回填器测试，增加1项真实命令发出方检查。
- Lint 为0错误、33警告、11提示；Debug、原生库、APK布局、仓库引用、目录映射及空白检查通过。单独的 Android 验收包构建通过。
- MuMu 0（API32、x86_64、720×1280）三组共22项通过：6项弹窗、9项编辑控制/单步/高亮/Root、7项图片库；各组最终均为 `pass=true`、`cleanup=true`、`INSTRUMENTATION_CODE: -1`。弹窗新增用例从“更多”实际打开变量检查，切换全屏并关闭，再打开独立运行变量窗，确认回编辑器且源码不变；不冒充运行变量每次赋值采样或全路径识别性能测试。
- 仅操作自有合成工程，测试工程已清理，验收包已移除并恢复智构首页。本轮不包含横竖屏或 ARM 真机验收。

修改前21份源码、测试和约束/映射文件快照保存在忽略目录 `build/studio-unused-ui-round3-before-20261020/`，按仓库相对路径保存，可恢复本轮修改；清理 build 会删除这些快照。构建日志为 `build/studio-unused-ui-round3-build.log`、`build/studio-unused-ui-round3-test-apk.log`；设备日志为 `build/studio-unused-ui-round3-dialogs-mumu.log`、`build/studio-unused-ui-round3-editor-mumu.log`、`build/studio-unused-ui-round3-image-library-mumu.log`。

## 收尾结论

按用户“过完吧”完成剩余菜单、导航、模块、资源及文档契约复查，没有新增可确认删除的生产代码，本次旧页面和关联死代码清理结束。

- 插件管理仍保留八项及既定顺序，模板项按用户决定禁用；现行编辑工具栏保留18项，命令发出方回归检查继续有效。没有为清理减少现有操作。
- 一次性内存导航请求、Auth 稳定接口、离线 Runner 模板、固定视觉组件、Manifest 系统入口、反射/JNI 与历史协议兼容按既有约束保留。未以普通 Kotlin 引用少为由删除这些边界，也没有重新启用旧首页快捷入口、账号或下载功能。
- 18个显式 Android 模块和22个 Rust 工作区成员的清单边界保留；Gradle/sourceSets、Manifest 和 ProGuard 静态搜索未发现已退休页面回流，归档继续排除在构建外。
- 以475份 Android 生产代码、XML 和构建/保留规则文件核对 Studio drawable 引用，没有新增无引用候选。资源变体和模型/许可不作为普通 UI 死资源删除。
- 只读积木一致性检查通过：217个已发布 kind 均匹配精确 Schema/编译器注册。文档契约测试15项全部通过，覆盖当前200个 API 的目录/侧栏/详情一致性及生成安全边界；不是200个 API 的逐参数运行验收。首次沙箱测试为14项通过、目录联接创建因 EPERM 失败；获工具授权在非沙箱临时目录重跑后15项通过，没有跳过或修改该安全测试。日志为 `build/studio-unused-ui-final-docs-test.log` 和 `build/studio-unused-ui-final-docs-test-retry.log`。
- 仓库检查通过：635处本地链接、0处本机专属引用，Rust成员位于规定目录；`git diff --check` 通过，日志为 `build/studio-unused-ui-final-whitespace.log`。

本次收尾仅执行静态检查、文档临时目录测试及补充记录，没有变更生产源码、重新打包或操作设备。当前开发包及839项单测、22项 MuMu 回归仍对应第三轮已完成的版本；33条 Lint 警告仍保留，不宣称全仓库零缺陷或所有设备已验收。后续代码重构或新功能不计入这次废弃页面清理。
