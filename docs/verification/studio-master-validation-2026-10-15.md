# Studio总计划第7/8阶段验证 · 2026-10-15

后续「改完这些」的最新证据集中在[Android剩余项收口](studio-android-followup-2026-10-15.md)：新增真实外置Provider16项通过，并修正实际清单、ART快照和最大化工作区问题。下文保留当时原始结果；当前状态以首版总账及该收口记录为准，不抹去历史失败。

用户在第4–6阶段开发后要求「执行后续剩下的」，本轮据此执行集中离线门禁及可用设备验收。不运行私人脚本、不清除App数据，不扩独立Runner和下载器。

## 已执行离线门禁

- scripts/check-rust.ps1 -Offline：格式、工作区全部目标严格Clippy、工作区单测/文档测试、schema和生成契约比对通过。schema报告527个JSON文档、187个schema、160个API和177个积木。
- build.cmd -Verify第二轮：Gradle成功，3分12秒，1099任务（26执行）；Studio及关联模块单测、Studio Lint和Debug APK构建通过。APK布局检查随后拒绝超预算候选，未将Gradle成功冒充安装成功。
- JUnit XML汇总：1108项，1106通过、0失败、0错误、2跳过。Studio 752、script-ui 48、project-store 81、runtime-client 31、runtime-service 141、vision-spi 2、vision-components 43；既有Verify脚本另含Runner模板10项兼容回归，不代表推进Runner开发/导出验收。
- 跳过项：Windows缺创建符号链接权限的一项宿主文件边界用例；需显式离线Runner组件矩阵的一项用例。不能算作已通过。
- scripts/audit-studio-functions.ps1：177种已发布积木与schema/编译器登记一致。
- scripts/check-repository-layout.ps1：最终复核500个本地链接、0本地产物引用、22个Rust成员；已跟踪改动git diff --check通过。
- base/opencv/onnx独立配置runtime-service:testDebugUnitTest均成功，分别26秒/19秒/19秒，各82任务；full由上述Verify覆盖。不是四配置APK或外部provider加载验收。
- build-install-mumu-test.ps1、apk-output-recovery-test.ps1通过：实CLI引号/双管道/进程超时、模拟设备定位/安装失败、APK精确备份/输出隔离/备份满时拒绝放行。仅自建临时夹具，测试输出中的模拟安装不计真实安装。

## 集中回归发现并处理

1. Rust契约测试仍为159、Studio积木测试仍为175，按当前真实契约更新为160/177，保留唯一性和版本/目录断言；Rust补API名称唯一性检查。
2. 新增Screen.recognitionValue缺少中文只读文档标题及正确积木别名，补到vision.readresult，不开放API文档直接插入。
3. 图像入口测试仍只接受旧识别列表，不认识已有处理入口和识别结果选择路由；现在先解析明确路由，再断言真实契约和已支持集合。
4. 缩进测试把旧control.if v1原稿与编辑器已迁移v2草稿混比；改为验证拒绝/撤销严格保留创建后的迁移基线。
5. 源码往返测试错误要求JSONL物理行顺序不变；按稳定节点身份比较全部envelope，包括真实orderKey和子区，不放宽参数/身份/层级保真。

第一轮失败记录保留于本说明，第二轮对应测试全部通过，不用删除用例或忽略故障放行。

## 干净APK与安装

默认增量APK布局额外17201054字节，超过16MiB门禁，3份旧备份已满，因此原路径未放行安装。清理请求尚无回复，实际没有删除任何备份；改用仓库既有显式full隔离配置生成干净候选，不修改默认视觉选择，也不覆盖旧候选。

- 构建：development ABI + full视觉；独立Gradle缓存build/profiles/components/development/full/.gradle，312任务（159执行、153缓存），31秒成功。
- 候选：`build/profiles/components/development/full/apps/studio-android/outputs/apk/debug/studio-android-debug.apk`，173171432字节；压缩项总量173000734字节，布局额外170698字节，小于相同16MiB门禁。
- verify-native-packaging.ps1 -Profile development -VisionProfile full通过：arm64-v8a/x86_64、Native动态符号保留、调试段剥离、原始ONNX模型SHA-256一致、无内嵌Runner。
- MuMu 0最初未启动，随后经既有管理器启动并连接127.0.0.1:16384，API32/x86_64；未重启、不清数据。ADB install --no-streaming -r -t成功；MainActivity冷启动Status ok、995ms，确认App进程存在。启动成功不等于页面、Root或脚本验收成功。

默认输出路径仍留有被拒绝的增量候选，不能用它冒充本次已安装的干净包。将来恢复默认构建需按提示明确清理旧备份位或继续使用已隔离配置；本轮清理请求已非完成安装的前提，不必为此删除文件。

## MuMu合成视觉验收

独立测试包com.autoscript.vision.acceptance保留数据更新，首次packageDebugAndroidTest失败后原命令重试成功（17秒/161任务），未据此宣称修复了打包原因。既有Instrumentation只生成内存图片，不采集用户屏幕、不运行项目、不注入输入。

设备为MuMu 0，API32、实际x86_64、显示720×1280；Build型号自报HONOR PGT-AN20不作为ARM真机证据。灰度为96×64合成帧、8×8模板、半开ROI[10,12,86,58]、相似度990‰，30次热样本；OCR均整张合成单行ROI、置信度500‰、单行模型、每项10次热样本。耗时仅适配层调用，**不含Root截图/输入或Studio端到端延迟**。

| 用例/输入 | P50 ms | P95 ms | P99 ms | 结果 |
|---|---:|---:|---:|---|
| 灰度偏移ROI，96×64/8×8 | 0.087 | 0.193 | 0.286 | 命中正确，30次；冷调用42.300ms |
| 大写A–Z，518×56 | 104.539 | 107.491 | 107.491 | 10/10精确 |
| 小写a–z，518×56 | 103.539 | 116.817 | 116.817 | 0/10精确；l被识别为1 |
| 数字0–9，214×56 | 57.035 | 64.992 | 64.992 | 10/10精确 |
| AaBb09Zz，176×56 | 53.144 | 56.693 | 56.693 | 10/10精确 |

灰度未找到/取消、关闭适配器也通过。Instrumentation总pass=false、结果码0，不能仅凭ADB进程退出码0误判成功。小写实际输出abcdefghijk1mnopqrstuvwxyz；保留原始失败，不换模型、用字符替换或降低断言来隐藏。新的合成预处理单独检查28组RGBA/BGRA、ROI/stride/大小输入，全部张量逐位一致，differentTensorValues=0、maxAbsoluteError=0、总pass=true；这不证明模型不会混淆字形。

## 明确保留的验收缺口

- 小写l/数字1准确率仍失败。模型或识别策略调整不在当前已确认范围内，不能宣称字母数字识别100%或自动替换结果。
- computer-use的Windows本机pipe不可用（os error 2）。按技能轻量重试、会话reset并初始化后仍失败，停止界面输入。首页/我的/编辑器/设计器/菜单/窗口/大字体/软键盘等本轮未完成可视验收；不能拿冷启动或主机回归替代。
- 未连接ARM或API24/25设备；Root截图/持续触点、悬浮权限撤销、系统回收、生命周期全流程、provider进程加载/取消矩阵、峰值内存和长时间性能均未整体验收。
- 没有运行私人脚本、自动授权权限、卸载App、重启MuMu或删除用户数据；也未清理三份APK备份。第8阶段是部分完成而非全通过。

## 本次继续第7/8阶段 · 真实控件与隔离补验

用户再次要求完成第6阶段之后的任务，本次继续执行未完成的可用设备验证，不重复以历史通过代替新改动验收。

### 发现并修复的真实设备问题

新增仅在独立测试APK中的ScriptUiAcceptanceActivity及夹具，在真实Android主线程创建共享ScriptUiView。首轮6项中2项失败：虽然状态层选中序号为0，Android Spinner在实际layout后仍会强制选中第一项，并通过延迟selection回调改写值。这证明先前仅设置原生-1的修复不充分，不是单元测试断言过期。

共享渲染器现使用仅展示的不可选「未选择」空行承载下拉空状态，弹出选项隐藏此行；正式选项、项目、值、脚本索引及数量不包含该行。程序更新不制造用户事件，用户清空后仍可真实选择第一项。列表继续使用无勾选状态；旧v1表单及下拉行复用也覆盖。没有新增脚本API、模型或依赖。

### 最终设备结果（MuMu 0 / API32 / x86_64）

- 共享View **8/8通过**：全部控件构造/测量/绘制（360×640、640×360两视口）；清空；移除及显式恢复；旧表单/弹出行复用；跨页更新与配置恢复；真实输入/首项选择回调；禁用祖先阻断；运行/对话框按钮通知。视口尺寸测试不是系统横竖屏或完整视觉验收，图片控件不等于真实资源解码已验收。
- 私有文件隔离 **3/3通过**：真实Android符号链接读写拒绝、目录链接读/写/列举拒绝、硬链接读写拒绝，合成目标内容保持不变。只在测试包自建缓存目录中创建/清理自己的文件，不删除Studio项目；原Windows用例仍跳过，不篡改其结果，新增Android用例补充了生产Os.lstat/O_NOFOLLOW边界证据。
- base独立测试包 **2/2通过**：灰度5108与ONNX6101缺组件都明确返回VISION_COMPONENT_NOT_INSTALLED。随后恢复full测试包，不改变Studio配置；这不是签名provider导入/加载进程寿命验收。
- full预处理 **28/28通过**，张量差异数和最大误差仍为0。
- 完整vision套件随后实际重跑，灰度/未找到/取消/关闭、大写/数字/混合样例继续通过；小写仍0/10，实际abcdefghijk1mnopqrstuvwxyz，总pass=false、完成码0，新脚本正确以失败退出。未合并两轮性能分位数，也未把重复失败算作修复。

### 自动门禁和交付

- 新增run-studio-device-acceptance.ps1，要求显式设备及固定套件，只运行已安装的独立合成测试包；不安装、不授权、不选默认设备、不读项目、不截图/注入业务输入。每次120秒宿主上限，同时验证完成码-1和布尔pass=true；报告失败会导致脚本失败，不再只依赖adb进程退出码。
- device-acceptance-result-test.ps1实际通过，覆盖额外结果字段、false、错误完成码、缺失/重复完成、字符串真假、坏JSON、超预算等拒绝。脚本参数遮蔽和测试夹具API编译问题在执行中发现后已修正，未以跳过方式放行。
- 修复后完整Android测试/Lint/Debug构建在隔离full目录通过：**6分16秒，1099任务（522执行、274缓存、303最新）**。从本轮XML重新汇总仍为1108项、1106通过、0失败、0错误、2跳过；Rust和契约无执行语义修改，上一节本轮Rust完整门禁证据仍适用。
- 最终Studio候选173342515字节，布局额外341096字节（小于16MiB）；双ABI、Native符号/剥离、模型摘要、无内嵌Runner检查通过。已保留数据更新MuMu 0，MainActivity冷启动Status ok、815ms。
- 文档同步下拉空状态及独立测试命令；修正旧验收手册仍误称手机默认内嵌Runner的说明。旧APK备份及用户数据未删除。

### 仍不能整体放行的项目

computer-use本机pipe再次经过重试和reset仍为os error 2，按技能停止桌面UI操作；上述独立Android夹具不是桌面控制替代通道，不冒充Studio悬浮窗、人机视觉、软键盘或权限撤销验证。ADB两个在线条目仍是同一MuMu，没有新增ARM/API24设备，已向用户询问当前真机连接。原模型小写l/1失败保留，未授权更换模型或算法。完整Root/悬浮会话、provider进程矩阵、ARM/API24、峰值内存和长期性能仍未完成，不能把第8阶段记为100%。
