# 项目开发约束

本文件适用于整个仓库。重大架构变化必须先记录 ADR；用户最新明确决定优先，并同步更新文档与约束。

## 产品范围

- Studio 的用户可见 App 名称为“智构”，App 图标与编辑器吸附球使用已确认的蓝紫 3D“灵动环”标志；applicationId、私有数据目录、协议与独立 Runner 项目品牌不随展示名称改变。
- 编辑器与系统运行浮球统一使用“灵动环”，运行时点击展开暂停/继续、停止，不能把浮球点击直接映射成停止。相机仅在用户主动打开截图模式时显示。启动请求等待 Root 就绪通知（有超时且可取消），连接成功不等于后端就绪。

- 用户界面的可视化 Flow 称为“插件”（独立流程文件），不与 Android 扩展插件混淆；持久化 flowId、节点 kind 与协议继续保持兼容。
- Studio 项目列表「启动」若设计了脚本界面，先显示界面，确认后才执行；编辑器「运行」保存草稿后直接执行，使用默认配置且跳过自动界面事件循环。独立 Runner 保持先配置再运行，无界面直接执行。单步调试独立。Studio 与独立 Runner 共享传统 View 脚本界面渲染器。可视化「编辑」直接展开编辑弹窗，最小化才变成吸附球，不展示旧全屏诊断页。
- Studio 设计界面的第二次「运行」在参数校验成功后立即隐藏配置弹窗，再保存、编译并等待 Root 就绪；编辑器直接运行不套用这个隐藏行为。隐藏窗口不得绕过启动检查。
- 启动后运行服务只激活已确认界面的控件/事件，不自动再显示配置界面；显示由脚本显式 `UI.command("window", "show", "")` 控制，隐藏期间的控件更新仍须保留。

- 首个交付阶段仅实现 Root 自动化，不实现 Accessibility、Shizuku 等免 Root 后端。
- 登录、后台、在线授权、支付、市场、云控与热更新只预留稳定接口，未经用户确认不得实现。
- Studio 与 Runner 从第一天分离；Runner 不得依赖项目列表、编辑器、打包器或账号页面。
- 不创建不能编译的空模块或以占位接口冒充已完成功能。

## 固定技术栈

- Android 使用 Kotlin；Studio 使用 Jetpack Compose，脚本动态 UI 使用传统 View。
- 引擎使用 safe Rust；脚本固定为 PUC Lua 5.4。
- 默认像素视觉和字库 OCR 使用 Rust；OpenCV 与 ONNX 只通过窄适配层接入。
- minSdk 固定为 API 24；首发 ABI 为 arm64-v8a，x86_64 仅用于模拟器开发。

## 架构红线

- UI 不得直接依赖 Lua、OpenCV、ONNX 或 RootDaemon 实现。
- `runtime-api`不得依赖JNI；只有`runtime-service`组合`runtime-api`和`engine-jni`。
- 引擎 crate 不得依赖 Android 模块。Android能力通过强类型HostRequest提供。
- Flow编译器属于Studio工具链，Runner只加载冻结后的Lua与运行资源。
- 输入、截图与OCR分别依赖AutomationBackend、CaptureBackend与OcrEngine抽象。
- Lua不得取得任意shell、Context、Binder、文件描述符、native指针或Root命令字符串。

## 并发、安全与数据

- Lua VM固定到单一线程；等待宿主能力时挂起协程，禁止固定周期轮询。
- 所有任务必须有取消、超时、背压与资源上限；停止控制不得进入普通业务队列。
- JNI、AIDL、Lua C API和裸缓冲区均是不可信边界；panic或异常不得穿过FFI。
- RootDaemon只执行结构化白名单命令，使用随机会话身份、重放保护和空闲退出。
- 屏幕帧不得通过图片文件或AIDL ByteArray高频中转。
- 项目、Flow、节点和API使用独立版本；迁移不得通过删除用户数据完成。
- `project.json.capabilities`是唯一能力真相。
- 密钥、Token、密码、服务端地址和用户私密脚本不得进入仓库或普通日志。

## 工程质量

- 确认无生产入口的旧 Studio UI 可移入 `archive/studio-ui/<日期>`，混合文件只抽取废弃声明，记录原位置和恢复方式；归档不得进入 sourceSets/模块列表。有入口的 Lua 调试分支、共享弹窗及预留路由不能仅因名称旧而归档。活跃 UI 使用职责命名，旧数据/协议兼容逻辑保留明确的版本语义。

- 日常 Studio 打包使用 `build.cmd`，构建成功后自动更新安装到 MuMu 的 0 号实例；助手完成代码批次后优先运行 `build.cmd -Verify`，包含 Studio 单元测试、Lint、Debug 构建及 MuMu 安装。安装保留应用数据，不自动卸载；构建或安装失败必须明确报告。原始 Gradle 构建仍用于 CI 或用户明确不要求安装的场景。
- 只创建当前批次真实使用且可构建的模块，Cargo和Gradle模块列表禁止通配空目录。
- 生成的Rust/Kotlin实现只进入构建目录；契约快照、Lua stub与HTML文档可签入并由CI比对。
- 新依赖必须检查维护状态、许可证、minSdk、ABI体积和Android 7兼容性。
- 每批代码至少通过格式化、静态检查、单元测试和Debug构建。
- 性能结论必须记录设备、分辨率、ROI、模板尺寸和P50/P95/P99口径。
