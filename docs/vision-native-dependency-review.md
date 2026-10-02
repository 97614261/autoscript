# 视觉扩展依赖核对（2026-09-29）

结论：默认Rust像素识别/字库OCR保留；本批真实集成OpenCV灰度找图和ONNX字母数字识别。运行时固定模型哈希，主机执行真实算法样例；设备API24/16KB页兼容仍未验证。具体设计见ADR0015。

## 已集成的开发版本，不是正式发布兼容结论

本机 Gradle 缓存确实存在 OpenCV 4.10.0 与 ONNX Runtime Android 1.19.2 AAR，不能把缺口说成“没有 SDK”。以下来自 ZIP entry length，单位字节；不是 APK 体积预测，也不是运行内存或性能结论。

| 构件 | arm64 原生库未压缩合计 | x86_64 原生库未压缩合计 | 注意事项 |
|---|---:|---:|---|
| OpenCV 4.10.0 | 21,924,680 | 56,070,264 | 包含 opencv_java4 与 c++_shared；AAR 含 prefab 头文件，两个 ABI 元数据声明 API 21/NDK 25 |
| ONNX Runtime 1.19.2 | 18,441,824 | 21,186,648 | 包含 onnxruntime 与 onnxruntime4j_jni；未含业务模型，API 24 兼容未验证 |

SDK 自身许可证与模型授权是两个问题。OpenCV 4.5+ 使用 Apache-2.0，见[官方许可证](https://opencv.org/license/)；ONNX Runtime 使用 MIT，见[官方许可证](https://github.com/microsoft/onnxruntime/blob/main/LICENSE)。所有实际分发的第三方构件仍需携带对应 NOTICE/许可。

[OpenCV 官方发布页](https://opencv.org/releases/)已有后续版本，缓存 4.10.0 不能未经评估就当作当前推荐版本。正式锁版本前还要核对更新状态、Android 7、16KB 页大小、NDK/STL 冲突、API 24 设备与两个 ABI。prefab 的 API 标记不等于本项目集成和设备验证已通过。

[ONNX 官方兼容说明](https://onnxruntime.ai/docs/reference/compatibility.html)列出已测试Android API28，更低API仍需设备验证。本批模型已保存到runtime-service/assets/vision，7,653,044字节，SHA256 e8770c967605983d1570cdf5352041dfb68fa0c21664f49f47b155abd3e0e318。来源是RapidOCR官方登记的PaddleOCR PP-OCRv4英文移动模型；SDK和权重分别附许可与NOTICE，不开放任意外部模型。

## 已确认范围与尚需资料

1. ONNX仅单行ROI，BGR/NCHW/48高，CTC过滤字母数字；五组真实主机样例通过，不是识别所有字体/截图的准确率结论。
2. OpenCV TM_SQDIFF_NORMED/1-平方差，最高分；固定分块样例通过。默认Rust像素匹配保留原来的得分与首个命中语义。
3. 参考“图像数据”的真实结构：mm0.Q0/R0 和布局能确认单/多模板返回选项；mm0.R/m0/onClick 含未反编译的方法，未取得可靠返回协议。当前“匹配区域图像”明确是本项目独立裁剪帧，不冒充该未知类型。

## 后续准入和验收

- 先记录具体适配 ADR，再实现窄 HostRequest/资源句柄适配；UI 不直接依赖第三方 native 后端，不向 Lua 暴露 Mat、Context、Binder 或指针。
- 模型只从声明资源加载；固定输入输出类型、尺寸、最大内存、截止时间、取消和任务所有权；不得导入任意库/文件/远程模型。
- 固定回归样本对比默认 Rust 算法与新算法，明确得分口径，不混用相似度字段冒充同一语义。
- 真实集成通过 API 24/两个 ABI 构建与宿主测试后再启用页面；设备性能按设备/分辨率/ROI/模板尺寸/P50/P95/P99 单独验收。
