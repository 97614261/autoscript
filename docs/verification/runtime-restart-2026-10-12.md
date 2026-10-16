# 运行重启与图像扩展收尾

本批新增Task.restart（opcode3011）及可视化重新运行积木，契约总数137，积木152。按ADR0073冻结源码、声明资源和已确认界面初值；认证清理完成后重建VM，停止及陈旧会话撤销重启，最多10次，不发布中间假终止。脚本录制与音频仍未完成。

第四批六项图像/OCR扩展已接通契约、编译器与参数页；PNG仍保留原像素，不覆盖目标；文本配额保持512KiB，PNG总预算64MiB。OCR文本矩形包含中间字形。

新增回归覆盖子任务请求重启、后续语句不执行、资源清理、VM全局状态重置、固定参数拒绝及重启门禁。MuMu不启动、不安装；设备行为仍需后续验收，不能以主机测试声明所有Root设备已支持。

本地验证通过：Rust工作区单元测试、严格Clippy、格式化、契约快照比对；Studio559项单元测试、runtime-service单元测试、Studio Lint与Debug打包均成功。冻结模板跨重启可加载的专项测试也通过。APK输出android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk，未安装。
