# ADR0036：Android打包副本剥离Rust调试符号

日期2026-10-03，状态实施。当前Studio Debug APK约575MB，最大项为内嵌Runner模板242MB（压缩后97MB），Rust JNI/编译库双ABI共约165MB，RootDaemon双ABI约16MB；OCR模型约7.7MB。不删除识别功能，不改变首发arm64和模拟器x86_64。

Cargo原始debug产物继续完整保存在target，Gradle先复制到模块build/generated/unstripped，再用已有NDK30 llvm-strip --strip-debug生成专用打包副本。去除DWARF，不删除JNI导出或改变代码/资源/协议；原件可用于调试。明确NDK版本保证AGP与构建器一致，三类Rust产物均使用同一边界。不能对用户文件或target原件就地strip。

两端继续使用非legacy JNI打包，不引入native压缩/提取行为变化；RootDaemon仍由现有控制器处理。检查两ABI ELF调试节/动态JNI导出和模板签名测试，保存前后体积、模型哈希，保持16KB页对齐。构建成功不等于设备运行验收。

实施验证：Studio Debug 301,423,771字节（较基线减少47.6%）；Runner模板146,123,240字节。六个打包ELF均去除debug_info，动态符号表与完整原件一致；模型7,653,044字节且原始SHA-256一致，未裁减识别能力。实际APK复验脚本为scripts/verify-native-packaging.ps1，独立SDK v2验签和两ABI 16KB ZIP对齐通过。
