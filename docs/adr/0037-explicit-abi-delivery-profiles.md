# ADR 0037：显式 ABI 交付配置

状态：接受；2026-10-03，用户批准交付收尾 1–4。

默认 `development` 继续 arm64-v8a + x86_64，`build.cmd` 构建并更新 MuMu。新增 `-Pautoscript.abiProfile=arm64` 手机候选配置，过滤所有原生目标以及 Studio/Runner 打包 ABI；其所有 Gradle 输出隔离到 `build/profiles/arm64`，防止切换配置复用错误的 Runner 模板或 APK。未知值在配置阶段失败。

模板路径、测试与资产使用实际模块 buildDirectory，不能继续硬编码默认输出。App ID、协议、数据目录和签名策略不变，不增加 productFlavor/新模块。手机候选先用 Debug 签名作验证，正式发布密钥/商店要求由用户另行确认；不为了减包删除 OCR、OpenCV、ONNX 或手机打包能力。

手机端 Runner 重建器接受完整 arm64 或完整双 ABI，拒绝半套依赖/不支持 ABI；独立 `package-probe --arm64-only` 显式校验手机配置，默认 probe 仍强制双 ABI，不默默降低既有 CI 门禁。日常构建脚本显式传 development，防止用户 Gradle 全局属性改变后误装旧输出。

收益：候选 APK/内嵌 Runner 去掉仅用于模拟器的 x86_64；两种构建互不污染，默认安装保持兼容。代价：两配置各有输出及缓存，磁盘占用增加。Rust 默认优化级别暂不改动，R8/依赖替换需另批完整兼容验证。

切换配置实测发现，仅改变 buildDirectory 不够：同一 Gradle 项目任务历史会清理上一配置的过期输出。因此手机命令同时使用 `--project-cache-dir build/profiles/arm64/.gradle`，根构建对 arm64 缺少此隔离参数时直接拒绝；防止来回构建丢失另一配置产物。共享的只读依赖/构建内容缓存不受影响。
