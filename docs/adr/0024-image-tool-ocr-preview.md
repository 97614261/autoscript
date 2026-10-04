# ADR 0024：截图取字与统一识别预览

状态：实施中。日期：2026-10-02。

截图工具的“取字”以用户框选的原始截图像素区域为输入，开放已实现的 Rust 字库 OCR 与 ONNX 单行字母数字 OCR。结果展示真实识别文本、得分与字库覆盖率，可将同一配置回填到既有 ocr.glyph / ocr.alphanumeric 积木或 Lua 调用，不新增脚本 API，不将预览文本伪装成运行时识别。

Studio 只使用 runtime-client / runtime-api。runtime-service 接收一次性的只读图像/字库 FD，校验同 UID、请求身份、会话、operation 白名单、固定选项长度、文件与像素预算，组合 JNI Rust 识别或已有 NativeVisionAdapter。该低频、用户明确触发的离线预览不替代运行时 FramePool，也不成为高频截图传输通道；不登记/覆盖 Lua VM 的模板与字库。

统一预览包含 Rust 像素模板、OpenCV 灰度模板、Rust 字库与 ONNX 字母数字四种 operation。每服务仅一个预览，最长五秒；新请求遇忙明确返回，用户取消/关闭弹窗可中断 OCR 的检查点及 ONNX RunOptions，超时、取消或会话改变后的结果不得回填。Rust 字库保留原有像素/分段/比较上限，并增加取消检查点；取消不承诺硬实时中断一个第三方 native 调用。

预览结果使用有限 Parcelable，不跨 Binder 返回图像字节；字段包含请求与会话身份、状态、文本或匹配位置、得分/覆盖率。JNI 严格检查数组与尺寸，panic 不穿越 FFI。Runner 不依赖 Studio UI；字库仍来自项目已声明资源，能力授权仍以 project.json.capabilities 为唯一真相。回填 ONNX 积木继续沿用 runtimeApi 1.7 与 ocr.onnx 的现有升级流程。

新增 AIDL 预览/取消方法将 RuntimeProtocol 协议统一升级至21，与同批调试变更一起生成/核对；旧 testTemplate 保留兼容。模板测试与截图取字共用请求与结果文案，配置变化清空旧结果；预览取消不停止脚本，不进入普通脚本业务队列。
