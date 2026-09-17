# AutoScript

> AI 或新开发者接手时，先阅读 [`AI接手说明.md`](AI接手说明.md)，再阅读 [`项目交接与后续计划.md`](项目交接与后续计划.md)。两份文档记录了当前 Git 现场、最新 UI 状态、已知缺口、测试边界和后续顺序。

新一代 Android 自动化脚本平台。Studio 使用 Kotlin/Jetpack Compose，运行引擎使用 Rust 与 PUC Lua 5.4；首个交付阶段仅支持用户明确授权设备上的 Root 自动化。

## 当前阶段

R0 二十三批基础能力已落地并接入统一门禁：

- Studio 与 Runner 是两个独立 Android 应用。
- Studio 已建立参考易编精灵紧凑风格的工作台与底部“开发者 / 我的”导航；项目卡片可展开并提供真实的编辑、运行、资源设置、备份导出、改名和删除操作，学习项目和开发者后台仅明确显示为预留入口。
- 本地 `.asproject` 备份仅包含权威清单、源码和已登记资源，不携带生成物与编辑器状态；导入执行条目数、单文件、总解压量、规范路径、重复项、声明闭包和资源格式校验，始终分配新项目 ID，并清除云端身份、签名和授权字段，绝不覆盖已有项目。
- `release-packager`已建立“项目源码→不可变发布目录”边界：手写Lua执行PUC Lua 5.4语法校验，可视化项目从权威Flow重新编译；发布元数据、设计分辨率、Lua和按路径排序的资源由SHA-256发布ID统一绑定，输出目录拒绝覆盖并可独立复验。
- Runner模板可通过`autoscript.releaseDir`注入已复验发布目录，自动采用发布包的applicationId、名称与版本；启动时再次严格解析清单、核对APK身份/版本及所有载荷摘要，将资源安全物化后自动提交同一Runtime运行链。普通构建仍保持无项目的模板模式。
- `project.json.capabilities`已贯穿Studio运行计划、发布格式v3、AIDL、JNI与Rust执行器：发布ID绑定规范排序后的能力集合；数量、名称、重复项和篡改均失败关闭；Lua发起宿主调用前按API契约校验，未声明能力以`CAPABILITY_DENIED`终止当前任务，不能到达Root、截图或资源后端。
- 独立Runner已改为显式启动，不再因服务连接自动执行脚本；RootDaemon的`停止/启动中/已认证/失败`真实状态通过AIDL状态快照与回调送达Runner和Studio，Root未就绪时运行按钮失败关闭。Runner紧凑展示发布身份、服务/Root运行条件、引擎状态与有界错误诊断，并提供运行、停止和刷新控制。
- 用户显式运行脚本后，独立Runner会把运行服务提升为低打扰前台Service；通知展示运行/停止状态，点击可返回Runner，并提供直接停止脚本的可见入口。Android 13以上会在界面提示通知权限，但拒绝通知不作为脚本启动硬门禁；脚本停止或失败后立即撤下前台状态，Service采用`START_NOT_STICKY`且进程被杀后绝不自动恢复用户脚本。
- Runner运行条件页逐项展示Root、通知和悬浮窗权限；可拖动悬浮控件支持紧凑/展开、暂停、继续和停止。暂停通过独立优先控制路径贯穿AIDL、JNI和Rust，业务任务及计时器被冻结，停止控制和Host超时仍保持活动。
- 冻结发布格式v3可携带摘要绑定的`runnerUi`和可视化source map：Studio项目设置提供严格JSON作者入口，传统Android View配置宿主支持文本、整数、布尔和单选字段，本地值按releaseId隔离并作为单行`RunnerConfig`注入；发布与Runner双重核对source map生成代次，运行错误可从Lua行号映射到`flowId/nodeId`，Runner同时展示有界生命周期日志。
- 两个应用通过版本化 AIDL 绑定到独立 `:runner` 进程。
- Rust workspace 已建立状态机、坐标、Root 协议及严格 Flow JSONL codec。
- Flow 加载器按显式 block 所有权与 `orderKey` 建图，保留原始字节，并区分结构错误、`depth` 冲突和非规范物理行序。
- `project.json` 已有严格解析、多 Flow 清单校验和项目级 `nodeId/blockId` 唯一性检查。
- Flow编译器已支持`task.noop`、类型化`flow.call`、`task.sleep`以及点击/滑动/按键基础输入节点，确定性生成Lua、runtime stripped source map和唯一`generation.json`记录；节点参数范围与`project.json.capabilities`均由Rust编译硬门禁复验。
- 可视化Flow已支持`control.if`、`control.repeat`、有硬上限的`control.while`、`variable.set`和`variable.copy`；编译器以显式`childBlocks`迭代生成嵌套Lua，不依赖`depth`且不使用受用户深度控制的Rust递归。Studio可向空的满足/否则/循环体子block直接插入积木。
- 真实视觉积木链已覆盖截图缓存/释放、取点颜色、区域找色、区域找图和基础字库OCR；Rust编译器生成唯一Lua执行语义并把命中状态、坐标、颜色及OCR结果写回Flow变量。图片和字库只能选择`project.json.resources`中类型匹配的登记资源，矩形、颜色、相似度和能力声明均在生成前失败关闭。
- 扩展像素积木已公开单点比色、多点找色、颜色计数与全部找色；多点参数以结构化数组保存，Studio提供严格的`x,y,颜色,容差;…`紧凑编辑格式，编译器硬限制1至64个偏移点、1至256个结果，并继续复用同一套Rust像素核。
- 旧版像素能力已通过独立`Legacy`命名空间和四个可视化积木公开：`duoDianZhaoSe`、`duoDianBiSe`、`getRectColorNum`和`getRgbColor`在Flow中保存结构化参数，编译时才生成严格旧字符串；保留宽高ROI、完整32位偏移、5种方向、逐通道容差、百分比向上取整及整数返回语义，并保存多点找色的完整`count/first/points`结果和便捷派生变量。
- 同一份正式视觉项目已建立可重复端到端验收：项目清单和Flow JSONL经Rust编译、生成Lua、注入确定性截图/模板/ASGLYPH字库并由真实Runtime Executor执行；Flow内部逐项核对取色、找色、找图和OCR结果后才发出成功标记，终态同时断言全部任务资源租约释放。
- Studio项目设置已形成资源、Runner动态配置与能力闭环：可通过系统文件选择器流式导入图片和`ASGLYPH v1`字库、查看有界缩略图/尺寸/字形数、编辑启动表单、保存积木能力声明，并按清单并发代次安全删除未引用资源。导入自动规范化扩展名和重名路径，限制图片为32 MiB、4096边长和4194304像素，字库为8 MiB；资源、动态配置或能力变化会立即使旧生成物失效。
- Android可视化项目已通过AIDL调用同一个Rust Flow编译器，按显式结构投影紧凑节点树，编译诊断可定位`flowId/nodeId/line`，成功生成后校验代次和摘要再进入统一Runner运行链。
- 积木编辑器已支持稳定ID新增、子树删除、同block移动、类型化`flow.call`参数、撤销/重做及多Flow创建与引用安全删除；保存严格执行“PFD草稿→Rust全项目校验→精确旧源码冲突检测→generation stale→原子写入”，编译与运行会先提交全部脏Flow。
- Studio积木目录由`schema/block-catalog/blocks`单一声明生成：包含分类、搜索词、属性编辑器、可用默认值、能力要求、子block和逐版本迁移；紧凑选择器支持分类/关键词搜索和缺失能力禁用，通用属性面板按目录渲染，并提供点、矩形、颜色、项目图片及字库专用编辑器。目录节点集合与Rust编译器支持集合由测试强制完全一致，未知或更高版本节点失败关闭，迁移后的Flow保持未保存状态并仍须经过Rust草稿校验。
- Flow源、Lua和source map使用SHA-256绑定；任一精确字节变化都会被判定为stale。
- 生成Lua必须通过vendored PUC Lua 5.4.9真实文本编译，语法校验不会执行脚本。
- 生成物通过同目录暂存、文件同步、崩溃自动释放的操作系统锁和`generation.json`最后提交；Flow保存后可立即撤下权威记录并标记stale。
- `lua-runtime`固定使用mlua的`lua54 + vendored`组合，当前锁定PUC Lua 5.4.9；同一VM内已支持多Task协程、yield/resume、指令预算抢占、内存/协程上限及危险标准库裁剪。
- `runtime-scheduler`已实现强类型Task/Request/Timer/Resource标识、公平运行队列、父子取消、detached任务、结构化Outcome及有上限的跨线程完成队列。
- stop与Task取消使用独立优先控制通道；Host等待必须携带超时，迟到结果按`requestId + taskGeneration`丢弃，不会恢复已取消或已超时协程。
- Timer使用可注入单调时钟、精确下一截止点与条件变量唤醒，不做固定周期轮询；周期Timer按原始节拍推进、合并漏拍且禁止回调重入，暂停恢复会平移截止点。
- Task资源租约可在父子Task和Outcome间显式复制/转移；完成、失败和取消都会确定性清理，取消已联测为“Lua 5.4关闭线程并执行`__close`，随后释放资源”。
- `runtime-executor`已把生成Lua、同步Math、异步Host请求、无轮询Timer、超时、取消和终态串成执行闭环，并有确定性虚拟Host端到端测试。
- `automation-core`已提供`AutomationBackend`、`CaptureBackend`、`OcrEngine`窄接口，以及有界不可变FramePool、Task资源租约与公平原子InputArbiter。
- `engine-core`以一个会话一个Lua VM组合执行器、输入仲裁和帧池；`engine-jni`使用generation句柄、有界命令通道和独立Lua线程隔离JNI边界。
- AIDL协议为v14，Runner `:runner` Service已真实调用Rust会话，并按Rust返回的单调时钟deadline及包含Root后端状态的AIDL回调工作，不做固定周期轮询；失败终态会把有界Lua/引擎诊断经JNI与AIDL推送给Studio，重置会话会清除旧诊断。项目图片、字库和可视化Flow草稿均通过PFD导入，不走大ByteArray Binder传输。
- `root-protocol`提供HMAC-SHA256认证、单调序列、重放拒绝、payload上限和结构化命令白名单；`root-daemon-core`落实首包Hello、`SO_PEERCRED` UID契约、能力协商、空闲退出和只派发合法命令的状态机。
- Android构建自动交叉编译并打包arm64-v8a与x86_64 JNI库；`package-probe`检查双ABI、stored、16KiB ZIP对齐、ELF机器类型和APK Signing Block。
- Lua与调度模块已通过Android API 24的arm64与x86_64交叉检查。
- 项目、Flow信封、节点语义和脚本API使用四套独立Schema。
- 二十五个脚本API由JSON声明生成Rust注册表、契约快照、Lua stub和离线HTML文档；API 1.5已包含基础字库、现代/旧版像素视觉和有界连续帧采集。
- `pixel-vision`按实际像素比较量执行预算，颜色结果最多返回256点；支持`stepX/stepY`和旧版5种确定性搜索方向。
- 模板按规范化RGBA内容的SHA-256复用预处理，优先比较最多4个锚点并按最低相似度安全早退；原图和预处理内存同时计入模板预算。
- `pixel-vision`可在严格旧字符串与结构化颜色/采样点间双向无损转换；规范输出与旧复制界面一致且不带尾随`#`，无法表达的alpha、非零锚点偏移及数量越界会失败关闭。
- `Screen.captureSeries`通过精确Task定时器逐帧请求Root截图，不做固定轮询；启动时按首帧几何预留最坏内存，限制为最多120帧、60秒和120 FPS，并返回实际时间戳、丢帧数及partial标记。
- `glyph-ocr`已实现有界`ASGLYPH v1`字库解析、颜色容差二值化、确定性分行分字、逐字匹配、置信统计与任务级字库租约；ONNX高级OCR仍留在后续阶段。
- `glyph-maker`可把严格点阵JSON确定性生成`ASGLYPH v1`与HTML预览，并自动裁掉外围空白；`project.json.resources`已成为图片和字库资源的类型化清单。
- `RuntimeClient.startProject`会先校验并按规范路径排序注册全部项目资源，再启动生成Lua；部分装载失败会停止会话，不运行不完整项目。
- 统一 Schema 门禁检查全部 JSON、唯一 `$id`、本地 `$ref` 与脚本 API 声明。
- 登录、后台、免 Root、在线授权和热更新均未实现。

## 构建

M26完整主机门禁与MuMu回归已脚本化；主机脚本会生成带SHA-256和逐步结果的独立产物目录，设备脚本默认只操作指定的`127.0.0.1:16384`：

```powershell
.\scripts\m26-gate.ps1
.\scripts\m26-device-smoke.ps1 -ArtifactRoot .\build\m26-YYYYMMDD-HHMMSS
```

设备已安装同一批产物、仅需重跑回归时可增加`-SkipInstall`。设备脚本硬校验MuMu的API 32、x86_64与720×1280，避免误操作同时连接的真机。

M27调整为完整UI对齐批：覆盖工作台、项目文件工作台、Lua/积木编辑、脚本界面设计、资源工具、运行环境、备份、打包、Runner、悬浮控制、错误页和本地“我的”；登录、学习项目和开发者后台只做明确的预留页面。详细范围、顺序与验收见[`docs/m27-ui-plan.md`](docs/m27-ui-plan.md)。

```powershell
.\gradlew.bat :apps:studio-android:assembleDebug :apps:runner-template-android:assembleDebug
cargo test --workspace
cargo clippy --workspace --all-targets -- -D warnings
cargo run -p api-codegen -- check .
cargo run -p schema-check -- .
cargo run -p glyph-maker -- tools/glyph-maker/examples/basic.json dictionaries/main.asglyph glyph-preview.html
cargo test -p flow-compiler
cargo run -p package-probe -- apps/runner-template-android/build/outputs/apk/debug/runner-template-android-debug.apk
cargo run -p release-packager -- prepare examples/hello-project build/releases/hello-runner com.autoscript.hello 1 1.0.0
.\gradlew.bat :apps:runner-template-android:assembleDebug -Pautoscript.releaseDir=build/releases/hello-runner
cargo run -p package-probe -- --require-embedded-release apps/runner-template-android/build/outputs/apk/debug/runner-template-android-debug.apk
```

要求：JDK 17、Android SDK 36、minSdk 24、Rust 1.96.0。Android 首发 ABI 为 `arm64-v8a`，`x86_64`仅用于模拟器开发。

## 目录

```text
apps/       Studio 与独立 Runner 应用
android/    可复用 Android 模块
engine/     Rust 引擎与协议 crate
schema/     项目、Flow信封、节点语义与脚本API独立契约
tools/      API代码生成器、Flow编译器、发布准备器、Schema门禁与APK/ELF打包风险探针
docs-site/  不打包进Android App的离线HTML能力文档
```

## R0边界

当前阶段已形成可编译、可测试的Root执行链、安全边界、像素视觉与基础字库OCR。基础字库已通过宿主测试闭环和Android双ABI构建，但真机字体样本的精度与性能仍需在功能阶段完成后统一验收；ONNX高级OCR尚未实现。登录、后台、免Root、在线授权和热更新仍只保留边界，不在当前实现范围。
