# Studio首版总计划

2026-10-15。用户已依次授权第1–3、第4–6及剩余验证阶段。本文是当前合并总账，历史批次记录不再充当当前缺口。范围为Studio；独立Runner、下载器、通用中文OCR、免Root、账号市场云功能和插件流程模板库延期。用户最新允许OCR策略/模型评估，但生产替换仍须再次确认。

| 阶段 | 开发交付 | 当前状态 | 放行依据 |
|---|---|---|---|
| 1 总账及契约 | 177种积木逐项矩阵、真实入口/修改/保存/编译/执行边界、历史待办销项 | 代码完成 | 矩阵/登记/schema/生成契约及集中主机回归通过；逐项设备验收未完成 |
| 2 图像流程 | 单模板测试与回填统一范围/方向；来源、资源、截图保存、取消和凭据失效 | 代码完成 | Kotlin及两ABI原生链路编译、集中回归、严格Clippy通过；设备渲染未验收 |
| 3 等待/文字/结果 | 紧凑变量映射、安全的字库目标包含、结果生产来源引导及动作确认 | 代码完成 | 普通积木组合、声明和边界回归实际通过；真实系统交互未验收 |
| 4 运行及组件 | 组件状态/准备一致、取消/资源预算、重复开销审计、四配置边界 | 代码完成 | 四配置运行服务回归通过；默认full不变，不声称设备提速 |
| 5 编辑运行 | 编辑/源码/变量/流程/录制/会话/单步/高亮全链路复核与必要修复 | 代码完成 | 组合校验和事务保存、取消回执、返回身份修正及主机回归通过；设备交互待验收 |
| 6 页面及数据 | 设计器三端一致、提示日志、项目恢复、布局及代码清理 | 代码完成 | 无选中状态、容器裁剪、恢复保留及相关主机回归通过，不删用户数据 |
| 7 离线验证 | 实际执行Kotlin/Rust回归、Lint、契约与APK包检查 | 门禁通过，2项跳过 | 四配置回归、Lint、契约、干净full APK布局/原生依赖通过；跳过项明确保留 |
| 8 设备验收 | MuMu/ARM/API边界、生命周期、准确率、性能及内存 | 指定MuMu流程通过，非全设备放行 | 最新Studio20项连续两轮及严格共享控件9项实际通过，含重连/默认控件修复；前序Provider16项及22000次调用证据保留。v4/v5各150次候选比较完成但候选更差，OCR歧义及真ARM/API24/全部UI生命周期边界仍未解决或未验收 |

## 当前事实与边界

2026-10-16用户确认本次非真机收尾为固定六项：持续运行、异常取消、图像/积木组合、数据安全、复杂界面、统一候选交付。本批可执行清单现已完成：最终同包15分钟6578次循环、30项异常/数据、17项页面、36项Studio及10项共享控件通过，保留数据安装及交付门禁完成；自然录制取消因MuMu事件源不支持而未实测。不再从该清单另拆新开发阶段；实际通过、失败修复与设备边界见[非真机六项记录](../verification/studio-nonphysical-six-2026-10-16.md)。真机/API24及已明确延期功能继续单列。

- 可视化目录/函数库统一生成，专用配置和通用属性编辑共用保存与完整编译。逐项证据见[积木矩阵](studio-block-coverage.md)；矩阵不根据字符串命中推断设备成功。
- ADR0109/0110/0111三轮识图已实现：六类识别的四种等待、专用读取、逐字框、结果动作及有界解析缓存。旧文档中的缺颜色/文字等待或仍需Data读取是历史状态，不能重复计缺口。
- 原像素、灰度、颜色、字库和ONNX模型分开；分数不等价。灰度单模板、不缩放旋转；ONNX单行字母数字，不承诺通用OCR。历史小写l/1失败证据保留。
- 当前默认full，组件导入/选择/准备代码存在不等于已完成瘦身版验收。Runner模板与运行组件严格分开。
- 等待工作变量必须声明，不因便利性隐藏新建。总期限是识别前后检查，不保证SDK硬实时终止。
- 结果读取不截图；处理坐标不直接作为触摸坐标。截图和图片保存保持原像素及预算。

## 状态口径

逐项记录“源码路径/编译/回归执行/设备验收”，不以API数计算百分比。不扩大当前阶段范围；审计发现的问题并入原阶段。设备失败不能在没有重现证据时写成修复成功。

前序证据：[第九阶段](../verification/studio-stage9-closeout-2026-10-14.md)、[识图第一轮](../verification/studio-recognition-completion-2026-10-14.md)、[后续](../verification/studio-recognition-followup-2026-10-14.md)、[安全收口](../verification/studio-recognition-safety-closeout-2026-10-14.md)。本批设计见[ADR0112](../adr/0112-studio-coverage-and-recognition-workflows.md)。

## 第1阶段 · 真实生产路径核对

矩阵按177种已发布积木逐行记录kind、标题、版本、schema、编译器登记和能力。生成检查拒绝重复kind、重复schema身份、错误schema常量、错误编译器版本及未发布登记；历史control.if v1/v2同时保留。下面是所有行共用的生产链路，不声称仅靠文本搜索便证明每个参数执行正确。

| 环节 | 实际代码 | 审查结论及边界 |
|---|---|---|
| 函数加入/文档 | [FunctionCatalog](../../android/apps/studio-android/src/main/java/com/autoscript/studio/editor/FunctionCatalog.kt)、[FunctionApiReference](../../android/apps/studio-android/src/main/java/com/autoscript/studio/editor/FunctionApiReference.kt) | visualGroups枚举BlockCatalog.all，不维护旧Lua插入表；底层API文档不能插入。目录逐项回归已存在，本批编译但未执行 |
| 参数与修改 | [VisualNodeArgumentsDialog](../../android/apps/studio-android/src/main/java/com/autoscript/studio/editor/VisualBlockArguments.kt) | 新增/修改共用分发；识图/结果、基础工具、界面、运行工具、输入等走专用面板，其余由契约属性编辑；均需正式保存校验 |
| 树与正式保存 | [VisualEditorState](../../android/apps/studio-android/src/main/java/com/autoscript/studio/editor/VisualEditorState.kt)、[VisualProjectScreen](../../android/apps/studio-android/src/main/java/com/autoscript/studio/editor/VisualProjectScreen.kt)、[ProjectStore](../../android/modules/project-store/src/main/java/com/autoscript/project/store/ProjectStore.kt) | 参数先成草稿；saveEditorIfNeeded提交validateVisualDraft成功后带expected基线保存。恢复、会话及运行身份的完整复核继续属于第5/6阶段 |
| 完整编译 | [编译器入口](../../rust/tools/flow-compiler/src/lib.rs)、[分析](../../rust/tools/flow-compiler/src/analyze.rs)、[生成](../../rust/tools/flow-compiler/src/render.rs) | 分析变量、资源、能力和结构后生成冻结Lua与source-map。矩阵仅自动证明登记和schema一致 |
| 执行 | [Rust执行器](../../rust/crates/runtime-executor/src/lib.rs)、[运行服务](../../android/modules/runtime-service/src/main/java/com/autoscript/runtime/service/AutomationRuntimeService.kt) | 控制/变量是结构化生成代码；纯计算在Rust，Android能力经HostRequest及服务适配，不从同名API推断等价或设备可用 |
| 可逆源码 | [PluginSourceToolchain](../../android/apps/studio-android/src/main/java/com/autoscript/studio/editor/PluginSourceToolchain.kt)、[全部契约往返用例](../../android/apps/studio-android/src/test/java/com/autoscript/studio/editor/VisualLuaRoundTripTest.kt) | 受限描述语言不执行用户源码；现有用例遍历全部契约并保留嵌套数据及身份，仅测试源码已编译，实际回归待第7阶段 |

## 历史缺口销项

这是源码状态更新，不删除旧记录，也不借旧测试结果宣称本轮通过。

| 曾列出的缺口 | 当前事实 | 后续仍需做什么 |
|---|---|---|
| 函数库缺专用积木、旧Lua模板混入 | 当前统一177种可视化积木/160个只读API，函数库遍历生成契约 | 第7阶段目录/源码回归、第8阶段交互验收 |
| 基础工具、数据/文件、运行期UI和脚本控制缺失 | 五批代码已补齐，见[逐批记录](../verification/function-library-batch-1-5-2026-10-12.md) | 编辑运行和UI一致性按第5/6阶段复核，不由数量判完成度 |
| 灰度/变换/帧系列/逐字结果/文字定位缺入口 | 已有契约、专用或通用配置和编译分支 | 设备准确率、内存及交互待验收，不扩新算法或模型 |
| 工作台不能加入处理链及识别尾部 | ADR0102/0103已实现输出选择、整批校验、一次撤销和帧释放 | 本批统一测试/加入范围和方向，执行回归及设备验收后置 |
| 结果只能通用Data读取 | 当前vision.readresult专用64KiB有界读取及任务所属解析缓存 | 损坏/来源变化/取消回归仍需执行 |
| 颜色/OCR没有等待 | 六类识别均可展开四种可见等待组合 | 本批改进字库包含和变量映射，新组合仍待运行 |
| 组件导入等于可运行，默认声明等于SDK需求 | ADR0104–0107已有resolver、精确选择和实际输出需求准备 | 第4阶段复核组件及预算，默认full不变；不冒充provider设备验收 |
| 独立Runner、下载器、插件流程模板库 | 用户明确延期 | 不计Studio首版当前开发缺口，不自动实施 |

## 第2/3阶段 · 本批交付清单

- 保存图像测试：原图X/Y/宽/高，非法输入不强制转换；半开ROI、五种像素方向与阈值同时传到预览和回填。灰度仍单模板最高分，不接受方向/容差；多模板、目录、频次和动作只在正式配置设置，本测试不声称覆盖。
- 修复预览服务丢弃像素方向，接至内部JNI及真实Rust matcher；旧JNI签名不改，不新增脚本API/协议。保留预览期限及预算，不保证下一帧命中。
- 范围框/命中点与Fit图片共用缩放和留白原点；工作台禁止换已测试ROI；资源变化取消任务并清空证明，原图解码不借用旧资源尺寸。
- 截图保存共用目录选择/新建/改名/同名编号及原像素预算，展示尺寸；改过名称/位置后关闭需确认，取消不删除已创建目录，不覆盖原图。
- 等待窗紧凑显示计时/条件，折叠工作映射；快速分配保留有效选择、排除原输出及来源，明确确认后应用，不自动建变量；维护返回保留草稿。
- 字库包含非空目标使用一次截图/逐字识别，再从同帧读回原文字/覆盖率/得分并定位。允许目标外未知字，内部未知/跨行不匹配，目标无空白且最多256字节；两个额外字符变量必须声明。非空/完全一致和旧保存组合不迁移。
- 结果读取提示生产积木、集合/文本区别和类型；逐字能直接读数量。动作来源/形状/偏移/锚点/逐项/动作/数量/时长变化均清除旧原屏确认，处理图坐标不能直接点击。
- 回归源码覆盖范围、五方向、内部JNI纯像素入口、字库未知字、单帧输出、声明冲突、自动分配保持及确认重置；仅编译/静态检查，实际执行及设备效果后置。

第1–3阶段原始证据见[本批记录](../verification/studio-master-stages-1-3-2026-10-15.md)，当时只编译未运行；随后集中回归已实际执行，不篡改历史记录。

## 第4–6阶段 · 代码收口

本批修复视觉准备/查询状态和取消发布、完成回调后继请求；全部脏插件组合校验并一次事务保存、取消后的保存回执及运行返回身份；共享控件无选中回显、设计器祖先裁剪和恢复记录保留关闭。默认full、资源预算、既有协议及产品边界不变。详细交付及源码定位见[第4–6阶段记录](../verification/studio-master-stages-4-6-2026-10-15.md)和[ADR0113](../adr/0113-studio-runtime-and-draft-closeout.md)。

## 第7/8阶段 · 集中验证

实际执行的门禁、回归发现、跳过项、APK阻塞与设备结果统一维护在[验证记录](../verification/studio-master-validation-2026-10-15.md)。主机用例通过不代表生命周期、SDK准确率、Root截图/输入或性能已经通过；没有设备的ARM/API边界保持未验收。

用户随后要求修复剩余项，新增Android原生Instrumentation、有界QA工程、base真实Provider验收和OCR字体诊断，并修正实际清单/ART快照/最大化工作区问题。最新通过、失败及重启授权边界见[Android收口记录](../verification/studio-android-followup-2026-10-15.md)，不以新增夹具或源码修复直接把阶段8记为完成。

## 用户确认的最后六项

本轮按同一六项完成代码修复与可用MuMu指定流程验证，不继续拆出新开发阶段。发现并修正设计恢复可信根别名、服务重建后旧高亮序号、监听重连无当前位置、编辑运行未准备默认控件状态；Studio20项/共享View9项、受影响926项主机回归（924通过、2既有跳过）、Lint和四配置编译及新APK检查通过，已保留数据更新MuMu。OCR官方v5候选更快但准确率更差，不替换。详细证据与未覆盖边界见[最后六项收口](../verification/studio-final-six-closeout-2026-10-15.md)和[ADR0115](../adr/0115-studio-final-acceptance-and-fixed-ocr-evaluation.md)。

追加MuMu验收已扩展到Studio25项：真实确认/布局的输入读回、取消、停止、超时，以及变量—循环—判断—跳转组合和编辑器/函数库方向恢复，连续两轮通过；共享界面增加InputConnection与IME观测。布局弹窗尺寸现纳入附着视图的键盘边界。系统悬浮输入测试未观察到IME的轮次仅证明读写，不能计作遮挡通过；新增主机门禁及逐轮证据继续见上述收口记录。不将这些用例等同全部积木参数、全部键盘或真机已验收。

用户随后要求的六项交付复验现已完成：用户授权重启MuMu 0和临时QA键盘后，修复真实发现的运行唤醒丢失、图像缩略图提前回收、实体键盘遮挡及视口变化重建输入控件问题。最新生产包在MuMu/API32连续两轮36/36通过，包含四类编辑弹窗、五项重点组合、非零实体IME的实际输入/无遮挡、横竖屏恢复后真实触摸；两轮pass/cleanup均true、完成码-1，原输入法恢复且QA键盘停用。共享View10/10不包含实体IME观测，不拿它代替遮挡验收。full主机983项（981通过、2既有跳过）、Lint及APK检查通过，保留数据安装完成。逐项结果、原始失败、包摘要及未覆盖边界见[六项交付复验](../verification/studio-delivery-workflows-2026-10-15.md)。本有限批次已结束，不等于ARM/API24、全177积木所有参数、所有输入法/权限撤销/系统回收或OCR准确率问题已结束；不新增延期Runner等开发任务。

随后用户提供新SVG并要求实际接入品牌图标/动效。该独立视觉批次已完成，同源PNG、自适应/主题图标、悬浮球和轻量动画及PNG/GIF全集见[品牌接入记录](../verification/zhigou-brand-integration-2026-10-15.md)。新包执行910项相关主机门禁（2既有跳过）及MuMu4项品牌/9项编辑浮窗回归，不把此前36项结果说成新包全量复验。窗口交互、项目格式、Root/视觉算法和生产模型均不改变。
