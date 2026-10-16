# Studio总计划第1–3阶段交付

2026-10-15。用户授权“执行1–3，执行完告诉我”。范围为代码、源码/回归源码编译与静态检查；实际用例执行、Lint、APK和安装统一后置。本次没有设备操作、私人脚本执行、独立Runner开发或下载器。

## 第1阶段：总账与契约

- 合并[首版总计划](../reference/studio-first-version-master-plan.md)，按源码、编译、回归执行、设备验收分别记状态。旧函数库五批、识图三轮的已实现内容从历史缺口中销项，保留原日期和证据，不由API数量推断完成率。
- [逐项矩阵](../reference/studio-block-coverage.md)列出177种积木的kind/标题/版本/schema/编译器/能力；[生成检查](../../scripts/audit-studio-functions.ps1)校验schema常量、重复、历史版本及未发布注册。函数库、配置、修改、保存、源码和执行生产路径及验收边界明确链接。
- 现有FunctionLibraryTest遍历目录、VisualLuaRoundTripTest遍历全部契约；本批编译源码，不将此写为177项已经运行成功。

## 第2阶段：图像流程一致性

- [不可变测试配置](../../android/apps/studio-android/src/main/java/com/autoscript/studio/image/RecognitionPreviewConfiguration.kt)统一原图ROI、阈值及方向。保存图像测试输入X/Y/宽/高，非法/未完成/溢出拒绝测试；范围框及命中点共用Fit坐标。灰度仍单模板最高分，禁用方向和颜色容差，不换算法。
- 源码审查发现服务原先丢弃像素方向，已接通[服务](../../android/modules/runtime-service/src/main/java/com/autoscript/runtime/service/RuntimeRecognitionPreview.kt)→新增内部JNI配置入口→[Rust matcher](../../rust/crates/engine-jni/src/template_preview.rs)的现代0–4顺序；旧JNI签名及脚本API不变。options末位对像素为方向，对字库仍为空格间距；Binder结构及版本不变。
- [处理尾部](../../android/apps/studio-android/src/main/java/com/autoscript/studio/image/ImagePipelineRecognitionTail.kt)拒绝更换已测试范围。保存图片测试和处理工作台在来源/资源改变时取消所属工作、清空证明，完整参数及运行会话仍须当前有效；不复用旧屏。截图工具仅暂隐测试窗，返回保留配置并要求重测。
- 保存窗口展示裁剪尺寸px；建议名仅初始化一次，修改名称/位置/新目录输入后关闭需确认。原像素、预算拒绝、同名编号、不覆盖原图和新建目录即刻生效保持不变；四个保存调用均传实际尺寸。
- 回归源码补齐全范围/边界/无效数值、五方向、配置与回填一致、处理范围拒绝及测试证明变化；内部Rust预览五方向、无效方向及取消，协议选项保留字库间距边界。

## 第3阶段：等待、文字及结果

- [等待窗](../../android/apps/studio-android/src/main/java/com/autoscript/studio/image/RecognitionWorkflowDialog.kt)紧凑显示时限/方式/条件，工作映射折叠；快速分配保留有效选择、排除识别引用和重复，确认列表后应用。变量不足需维护，不隐藏建变量；返回保留未加入草稿。
- [字库包含组合](../../android/apps/studio-android/src/main/java/com/autoscript/studio/image/RecognitionWorkflow.kt)使用一次截图/逐字识别，从详情读取原文字/得分/覆盖率，再复用Ocr.findText。目标外未知字可存在，目标内未知及跨行不命中；目标不含空白且最多256字节。两个额外已声明字符工作变量必须独立，不覆盖原输出。留空非空和完全一致仍采用旧门控；旧已保存组合不迁移。
- 结果读取说明对应生产积木、字符变量与实际形状不同，逐字可读数量。动作来源/形状/偏移/锚点/逐项/动作/数量/时长改变均清除旧原屏确认，仍不把预览或处理图坐标直接用于触摸。
- [Kotlin回归源码](../../android/apps/studio-android/src/test/java/com/autoscript/studio/image/RecognitionFollowupTest.kt)覆盖一次截图及详情、原输出保持、显式变量冲突、分配保持和确认重置；[Rust回归源码](../../rust/crates/runtime-executor/src/glyph_details.rs)补充外部未知字、真正问号与占位符、内部未知/跨行及非法输入。只编译，不宣称已执行。

## 本批检查

- 严格Clippy（runtime-executor/flow-compiler/engine-jni，包含测试源码，offline，-D warnings）：通过；Rust格式化完成。
- api-codegen check：通过；schema-check：527份JSON、187份schema、160个API、177种积木，通过。
- Studio矩阵只读一致性检查：177种，schema及编译器注册通过。
- Kotlin最终增量编译：Studio compileDebugKotlin/compileDebugUnitTestKotlin和runtime-service compileDebugUnitTestKotlin通过（2分2秒，202任务，3执行）；此前含方向接口的完整编译通过（2分31秒，202任务，17执行），JNI arm64-v8a及x86_64均完成原生编译。现有Android废弃API警告仍存在，本批图像配置的恒真条件警告已消除。
- 仓库布局/链接检查：492个本地链接、0个本地产物引用、22个Rust成员，归档不参与构建，通过。git diff --check通过；本批文件另含未跟踪路径检查通过（修正文档末尾空行）。

## 不能冒充完成的内容

- 新增和既有Kotlin/Rust回归没有执行，不把“编译测试源码”写成“单元测试通过”。Lint、APK布局、安装、交互/旋转/键盘、大字体、生命周期、准确率、性能、内存及API24/ARM设备边界均待[集中验收](../reference/studio-acceptance-checklist.md)。
- 测试仍单模板；没有声称多模板/目录/频次/自动动作已在此预览。灰度不缩放/旋转，ONNX仍单行A–Z/a–z/0–9，历史l/1失败保留，不加新模型或通用中文OCR。
- SDK期限在前后检查及可用检查点取消，不保证第三方调用硬实时抢占。默认full不变；组件导入不等于SDK设备验收。
- 第4–6阶段开发及第7/8阶段验收尚未因本批交付而完成。设计见[ADR0112](../adr/0112-studio-coverage-and-recognition-workflows.md)。
