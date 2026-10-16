# ADR 0057：Android工程集中到android/

日期：2026-10-10。用户在Rust集中后要求Android也统一归置，并确认按技术栈划分。

## 决定

- Android App集中于android/apps，共享模块集中于android/modules；Gradle配置、标准wrapper、SDK本地配置和工程缓存集中于android/。模块逻辑身份仍为:apps:*及:android:*，由settings显式映射物理目录，不改变应用身份、依赖和用户数据。
- 根build.cmd和build-mobile.cmd继续作为日常入口；根gradlew/gradlew.bat仅保留无业务的转发入口，真实Gradle工程在android/。仓库根资料由明确的repositoryRoot定位，Rust仍在rust/，生成器不把Android工程根误当schema根。
- 手机配置继续使用仓库build/profiles/arm64隔离输出和任务缓存，根build-mobile.cmd调用不变；手工Gradle必须传仓库缓存绝对路径，误指Android工程内相对缓存时明确拒绝。开发构建产物随模块位于android/下。打包、恢复、CI、合成测试和文档同步路径。
- 移动前后校验源码摘要，不覆盖目标、不删除缓存或用户资料；历史路径文档只加更新说明，性能证据不改写。迁移必须通过本地构建和测试，不安装或执行私人脚本。

## 验证

- 根转发入口projects配置通过，13个真实模块保持原逻辑身份；共享模块及App不进入Rust、归档或Runner的额外依赖。
- Android五模块660项：659通过、1项跳过、0失败；Studio Lint 0错误、29警告、17提示。Debug构建成功（2分10秒）；开发APK位于android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk。
- Rust夹具路径更新后flow-compiler 84项测试通过，fmt及契约生成检查通过；Clippy退出0，仍保留既有警告。
- APK为231,116,241字节；双ABI、JNI动态导出、去除调试符号、原ONNX模型及同ABI内嵌Runner通过核验。package-probe通过ELF、16KiB对齐及签名块结构检查。
- APK输出恢复、构建安装脚本的隔离模拟测试及修改脚本语法通过。模拟测试输出的“安装成功”不代表实际安装；没有连接或操作设备。
- 手机配置只做dry-run：仓库缓存绝对路径通过，误指Android工程内相对缓存时拒绝；未重建手机候选包，未把dry-run当作手机构建验收。
- 源码路径映射的当前目标同步至android/apps，布局门禁防止根Android配置、旧App目录及旧模块目录回流；文档链接及git diff --check通过。原工程缓存整体移动，没有删除缓存、私人脚本或资料。

目录整理不代表新增功能、性能提升或设备验收。真实Gradle wrapper与配置都在android/；根目录仅保留兼容转发和用户日常打包入口。
