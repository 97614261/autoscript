# 图像工具八阶段执行记录

日期：2026-10-19。本批八阶段已完成，并保留数据安装到MuMu 0。最终同包新增功能12项、滤色9项、固定字库16项合成验收全部通过，三组均为`pass=true`、`cleanup=true`。范围由[ADR0130](../adr/0130-bounded-image-authoring-and-recognition-tools.md)约束，不将合成验收扩写为真机或所有图片的准确率保证。

## 阶段状态

| 阶段 | 交付 | 当前状态 |
|---|---|---|
| 1 | 原像素模板蒙版及透明保存 | 完成；忽略/恢复笔、矩形、撤销/重做、预览和变换积木；MuMu实测矩形、撤销/重做及透明PNG原尺寸保存，原图不覆盖 |
| 2 | 去噪及腐蚀 膨胀 开闭处理 | 完成；显式前景、阈值、面积及半径；主机验证整数处理语义，MuMu实测清理预览、切换模式后纠正非法面积及应用 |
| 3 | 完整图像识别方案管理及备份 | 完成；冲突检查、原子保存、复制/改名/搜索/确认删除/确认重算；MuMu实测增删改查、载入及备份恢复 |
| 4 | 连续命中与区域变化 稳定等待 | 完成；可见积木组合、新鲜度、显式声明变量；MuMu执行连续3次新帧精确命中及Root区域稳定等待，另验无需模板的直接入口 |
| 5 | 差异叠加与失败分析 | 完成；明确候选、透明忽略、差异叠加、几何检查及本地计时；MuMu实测独立分析，不自动保存或生成测试凭据 |
| 6 | 受来源约束的原屏坐标映射 | 完成；FramePool来源、64步逆采样及专用读取；主机验证几何边界，MuMu执行截图镜像后的映射并验证文件来源不冒充原屏 |
| 7 | 有界多尺度像素找图 | 完成；原匹配器、总预算、JNI预览、配置和结果读取；MuMu预览返回(8,8)、2000‰比例及1000‰得分，正式编译执行读回2000‰ |
| 8 | 文档 回归 MuMu及安装收口 | 完成；当前主机门禁、37项MuMu合成验收、双ABI包检查及保留数据安装通过 |

## 已执行的主机验证

- Rust的automation-core、engine-jni、flow-compiler、lua-runtime、pixel-vision、runtime-executor及script-api七个相关crate测试通过；runtime-executor最终110项通过、1项既有忽略。覆盖真实Lua导出、来源拒绝、实际新鲜度、严格结果类型、窄偏移ROI取消、模板透明和缩放取样。
- pixel-vision / automation-core / runtime-executor / flow-compiler生产库严格Clippy通过；engine-jni连带检查仍受root-client两处既有slow_vector_initialization警告影响，不宣称整个工作区无警告。
- VitePress 15项测试通过，163个API页面及174个HTML页面构建/内部链接检查通过。API与积木分别为163项和180种，已更新生成矩阵。
- `build.cmd -Verify`通过，Studio 825项检查中824通过、1项既有跳过、0失败/错误；共享模块测试通过。Studio Lint为0错误、37警告、14提示。
- schema-check通过536个JSON文档、190个schema、163个API及180种积木；api-codegen检查及180种积木的精确schema/编译器登记审计通过。
- Android测试APK构建通过，`git diff --check`通过；仓库链接检查通过，无本机专属路径引用。

## 最终MuMu验收

设备为MuMu 0、`127.0.0.1:16384`、API32、x86_64、720×1280。只使用新建的`project-qa-`工程及公开合成位图，不执行私人脚本、不清应用数据。

| 测试入口 | 数量 | 最终结果 | 用例来源 |
|---|---:|---|---|
| imageEnhancementOnly | 12 | 全通过，清理成功 | [新增图像工具](../../android/apps/studio-android/src/androidTest/java/com/autoscript/studio/ImageEnhancementAcceptanceFixture.kt) |
| imageFilterOnly | 9 | 全通过，清理成功 | [滤色回归](../../android/apps/studio-android/src/androidTest/java/com/autoscript/studio/ImageFilterAcceptanceFixture.kt) |
| glyphMakerOnly | 16 | 全通过，清理成功 | [固定字库回归](../../android/apps/studio-android/src/androidTest/java/com/autoscript/studio/GlyphMakerAcceptanceFixture.kt) |

新增验收同时检查方案增删改查、透明PNG有效/忽略像素、无模板区域等待的声明变量选择、实际编译运行的蒙版→多尺度→结果读取、Root新帧稳定性及几何映射。滤色和字库在最终同包重新执行，不复用旧包结果。

原始日志位于本地忽略的`build/image-eight-final-enhancement-mumu.log`、`build/image-eight-final-filter-mumu.log`、`build/image-eight-final-glyph-mumu.log`；构建日志为`build/image-eight-final-closeout.log`，测试APK日志为`android/build/image-eight-final-test-apk.log`。

## 交付包

- 包名：`com.zhigou.kaifaban`；默认full视觉配置，包含arm64-v8a及x86_64，原生依赖/ABI/Runner边界检查通过。
- APK：`android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk`。
- 长度：185,937,177字节；ZIP布局开销12,169,808字节，低于16MiB门限。
- SHA-256：`702e6994019f0997a0c437976f4de0068abe7ee42c6c9c89d6806a0f316e7bf4`。
- 原OCR模型SHA-256保持`e8770c967605983d1570cdf5352041dfb68fa0c21664f49f47b155abd3e0e318`，没有替换模型或增加下载器。

用户明确批准的旧备份`previous-1bc0e9560f674d358032943ea391bd37.apk`已删除以完成本批重打包；未删除其他安装包备份、项目、图片或脚本。

## 验收期间的修正

- 真实Lua用例发现三个新函数未导出，已补齐并运行验证；Binder预览入口遗留固定8参数，已按操作类型严格验证，接受多尺度的11参数，调用UID边界保持。
- 新工具在过长横向条末尾不易发现，已单独使用紧凑自适应工具区；蒙版、清理及差异预览固定在顶部，参数独立滚动。
- 区域等待增加不需要模板/普通比较输出的入口；不再要求无用途的前次X/Y变量。清理模式切换后非法面积仍保持可编辑，不静默改值。
- 扩展自动化用例修正同名输入框/列表项和带状态前缀的按钮定位，并等待蒙版有效像素统计后提交。初次失败没有计为通过；最终12项逐项保留断言，未跳过失败案例。

## 实现边界

`snapshot_id`是屏幕坐标代次，不是截图序号。fresh要求代次相同、非零采集时刻递增、尺寸和几何链一致；重复缓存不计数。几何链保留在原FramePool，没有第二份帧注册表；文件加载和文件后续处理保持无屏幕来源。

多尺度是显式的首个合格查询：距1000‰最近优先、同距较小优先，档内沿既定方向；单档模板256K像素、总重采样4M、最多16档和共用1亿比较。未换OpenCV/ONNX模型，没有自动下载或通用OCR。

本次纯帧处理沿既有执行器有界CPU路径，使用合作取消和期限检查，不进行阻塞宿主I/O。没有虚构独立视觉worker。局部诊断耗时不作为Root完整识图耗时；未给未经设备统计的性能百分位。
