# 可选视觉适配器及独立预处理验证

## 本批代码

- JNI入口只依赖SDK中立的Backend、Work、缓冲校验、ROI和CTC。固定生成工厂组合OpenCV/ONNX源集；基础配置不引用两个SDK的类。
- OpenCV、ONNX源代码及模型/许可证分别放入组件源目录。首次基础包检查发现assets.exclude不能实际移除模型，已改为物理源集分离并复验；没有放宽检查。
- ONNX使用独立BGR、线性缩放、归一化和零填充，不再加载或调用OpenCV。模型字节和哈希保持不变；没有自动修正识别文字。
- 取消钩子只执行一次，并与解绑互斥，等待进行中的SDK终止操作后才关闭RunOptions。关闭模型仍遵守原生命周期，不抢先释放正在使用的资源。
- 修复OpenCV库加载：本地4.10AAR的Core字节码给出桌面名称opencv_java4100，Android构件实际携带libopencv_java4.so；现在只加载固定Android名称opencv_java4。

## 编译、测试与包内容

四种development配置均编译、服务单测及APK内容检查成功。默认full通过`build.cmd -Verify`，保留数据安装MuMu0。Studio633项、RuntimeClient28项、RuntimeService113项（1项既有跳过）、ScriptUI47项、ProjectStore76项，全部0失败/错误；三个显式组件配置各自重跑最新113项服务测试。Lint0错误、32警告、16提示。补丁及仓库布局检查通过。

| 双ABI开发配置 | APK字节 | 内容检查 |
|---|---:|---|
| base | 55,799,477 | 无OpenCV/ONNX/模型/内嵌Runner |
| opencv | 126,973,001 | 有OpenCV，无ONNX/模型/内嵌Runner |
| onnx | 93,970,340 | 有ONNX及固定模型，无OpenCV/内嵌Runner |
| full默认最终版 | 172,520,066 | 两种SDK及固定模型，无内嵌Runner |

显式配置输出和Gradle缓存隔离在`build/profiles/components/development/<profile>`。这些是含arm64-v8a+x86_64的Debug开发APK，不是单ABI手机包或独立Runner体积；base包还含约7.2MB ZIP额外空间，不能把它混作已优化的纯载荷。

## 公开合成设备检查

MuMu0、API32，测试APK为独立com.autoscript.vision.acceptance。未截图、未注入输入、未执行私人脚本；报告中的HONOR设备名称来自模拟器配置，不是物理手机证据。

- [28组张量精确对照](onnx-preprocessing-parity-2026-10-13.json)：RGBA/BGRA、额外行步幅、偏移ROI、上/下采样、精确2倍缩小、大ROI及960宽输出，全部逐位相同。首次被Android库名错误阻断，修复后才执行成功。
- [基础版缺组件拒绝](base-vision-rejection-2026-10-13.json)：5108、6101都返回VISION_COMPONENT_NOT_INSTALLED，而非成功空结果或尝试加载缺失的SDK。
- [ONNX-only公开推理](onnx-only-public-ocr-2026-10-13.json)：确认没有OpenCV仍实际加载模型并完成推理。大写、数字及混合字符各10次完全匹配；小写序列中l被读成1，0/10完全匹配，因此总验收明确为false。没有掩盖它或做1→l自动替换。

四组推理计时各10个暖样本，分辨率720×1280但输入是报告所列合成Bitmap和ROI；P50/P95/P99只代表适配器调用，不含截图或Root输入，不与找图性能混合比较。未测完整内存峰值。

## 未完成项

默认安装仍为full，不是已接通“勾选即安装”的基础Studio。组件库存、签名/兼容/本地导入与项目可用性预检继续按计划；下载器仍不实现。

ARM真机、Android24/25、全部字体/小写准确率、OCR场景数据及Root端到端识图还未验收。预处理对照成功不等于100%OCR准确率。基础Rust新增操作、图像工作台和按项目选择Runner家族继续开发，整个长期目标未完成。
