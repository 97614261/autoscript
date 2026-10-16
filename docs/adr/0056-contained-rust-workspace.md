# ADR 0056：Rust 工作区集中到 rust/

日期：2026-10-10。用户明确要求 Rust 相关源码、配置与产物不再散落仓库根目录。

随后Android工程按[ADR0057](0057-contained-android-workspace.md)迁至android/；根Gradle入口改为转发。本文的Rust结构及仓库资料根约束不变。

## 决定

- Cargo.toml、Cargo.lock、rust-toolchain.toml、rustfmt.toml 移至 rust/。核心crate从engine/crates迁至rust/crates；七个Rust工具迁至rust/tools；Cargo工作区成员保持显式白名单。
- Cargo缓存与可执行产物从target/迁至rust/target/，保持忽略，不删除旧缓存。Android Gradle的Cargo任务在rust/工作目录执行，以使用固定工具链；生成器仍接收仓库根作为schema/文档/项目资源根。
- Rust工具测试的仓库相对路径、JNI构建、代码生成、Runner打包、CI、主机测试和转换检查器定位同步调整。JNI库名、crate身份、协议、ABI、冻结Lua与用户数据不变；Studio/Runner依赖边界不变。
- Python旧工程转换器仍在tools/legacy-converter，不嵌入Rust工作区或App；分发检查器读取rust/target。Gradle入口和Android模块保留根位置，现有build.cmd和build-mobile.cmd不改调用方式。
- 记录移动前后源码哈希和路径映射；目录迁移不得覆盖已有目标或重置工作区改动。本批仅本地门禁，不安装或运行私人脚本。

## 使用

Rust开发先进入rust/，运行cargo fmt/test/clippy。契约工具的资料根使用..，例如cargo run -p schema-check -- ..。Android仍从仓库根执行Gradle，负责自动构建和装入对应ABI的Rust产物。

## 验证（2026-10-10）

- 128个源码/配置文件移动后、必要路径修改前逐项核对SHA-256一致；路径映射和哈希保存在忽略的build/rust-layout-migration.json。旧缓存原目录移动至rust/target，没有删除缓存或用户数据。
- Cargo metadata确认22个显式工作区成员及rust/target输出位置。fmt通过；工作区408项测试通过、12项显式基准跳过；Clippy退出0但仍有既有警告，不声称严格零警告CI通过。
- schema-check通过211份JSON文档、79个schema、59个API和70个积木定义；api-codegen check通过，生成契约不变。
- 独立转换器50项合成测试全部通过，包括从新位置调用真实Rust检查器，没有因旧路径静默跳过该测试。
- Android五模块660项：659通过、1项跳过、0失败；Lint 0错误、29警告、17提示；Debug构建成功（2分49秒）。两种ABI均从新工作区重建JNI、编译器和RootDaemon。
- 新开发APK为231,118,681字节。双ABI、动态导出、移除调试符号、原ONNX模型及内嵌Runner摘要通过核验；package-probe通过ELF、16KiB对齐及签名块结构检查，不冒充正式发布签名。
- 布局门禁已接入现有CI步骤：检查Rust配置集中位置、显式成员清单、旧成员目录和Gradle旧路径，防止路径回退。文档链接和git diff --check通过。

本批不安装、不继续模拟器/真机测试，不生成手机隔离候选包；不把目录整理当作功能或性能提升。Gradle根入口保留，不能将目录改名当成Android/Rust依赖解耦的新实现。
