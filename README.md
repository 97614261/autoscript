# 智构 · AutoScript

Root 自动化脚本平台：Kotlin/Compose Studio、独立 Runner、safe Rust 引擎、PUC Lua 5.4。用户编写可视化项目，每个独立流程文件称“插件”；持久化 flowId 和协议保持兼容。

当前实现包含跨 App 悬浮编辑、变量/判断/循环/跳转、Root输入与录制、像素/灰度找图、字库与单行字母数字 OCR、脚本界面、私有运行日志和独立 Runner 打包。代码门禁与设备验收分开，免 Root、账号/市场/云端和插件流程模板库仍延期。

## 当前入口

- [Android工作区](android/README.md)、[Rust工作区](rust/README.md)、[文档索引](docs/README.md)、[Studio第九阶段开发收口](docs/verification/studio-stage9-closeout-2026-10-14.md)
- [开发约束](AGENTS.md)、[接手说明](AI接手说明.md)
- [智构文档站](docs-site/README.md)：VitePress、Vue 3 和官方主题，完整 API 参考与本地搜索。
- [历史性能证据](archive/project-history/2026-10-10/README.md)、[文件清理记录](docs/verification/repository-cleanup-2026-10-10.md)

当前瘦身批次已完成本地候选构建和校验，不自动安装或替换网站下载包，见[瘦身记录](docs/verification/studio-package-slimming-2026-10-21.md)。独立Runner和默认视觉组件策略不变，下载器与设备验收不在本批范围。历史开发记录见[阶段收口](docs/verification/studio-stage9-closeout-2026-10-14.md)与[集中验收待办](docs/reference/studio-acceptance-checklist.md)；历史测试数量不作为当前候选已通过设备验收的依据。

## 构建与检查

日常开发构建并保留数据安装到 MuMu 0（会操作模拟器）：

```powershell
.\build.cmd -Verify
```

仅本地构建、测试和 Lint，不安装：

```powershell
.\gradlew.bat :apps:studio-android:testDebugUnitTest :android:script-ui:testDebugUnitTest :android:project-store:testDebugUnitTest :android:runtime-client:testDebugUnitTest :android:runtime-service:testDebugUnitTest :apps:studio-android:lintDebug :apps:studio-android:assembleDebug '-Pautoscript.abiProfile=development'
.\scripts\check-repository-layout.ps1
.\scripts\verify-native-packaging.ps1 -Profile development
Push-Location .\rust
cargo fmt --all --check
cargo test --workspace
Pop-Location
```

手机 arm64 隔离候选包：`build-mobile.cmd -Verify`，输出到仓库 build/profiles/arm64，不自动安装。手工 Gradle 手机构建须同时指定 `-Pautoscript.abiProfile=arm64` 和 `--project-cache-dir` 的仓库缓存绝对路径，例如在仓库根传 `(Join-Path (Get-Location) 'build/profiles/arm64/.gradle')`。当前仍是 Debug 候选，不冒充正式签名。

不减功能的瘦身候选使用独立入口，不安装、不替换网站下载包：

```powershell
.\build-distribution.cmd -Profile arm64 -Verify
.\build-distribution.cmd -Profile x86_64
```

保留 full 视觉与 V4/V5，使用 Release 代码/资源裁剪及优化 Rust，但显式使用开发证书签名，**不是正式签名发布包**。产物和项目缓存分别位于 `build/profiles/distribution/arm64`、`build/profiles/distribution/x86_64`；APK 在各自 `apps/studio-android/outputs/apk/release`，体积、完整内容及基线对比见 `size-report.json`。`-Profile development` 可生成双架构优化候选。原有 `build.cmd` 和 `build-mobile.cmd` 不变。详细边界见 [ADR0135](docs/adr/0135-studio-distribution-size-profiles.md)。

本轮实际候选为 ARM64 62.20 MiB、x86_64 98.36 MiB；完整摘要、验证结果及未做设备验收的边界见 [瘦身记录](docs/verification/studio-package-slimming-2026-10-21.md)。`-SkipBuild` 仅复查已生成候选，不代表重新构建或执行单测，不能与 `-Verify` 同用。

环境：JDK 17、Android SDK 36、NDK 30.0.14904198、Rust 1.96.0；minSdk 24，首发 arm64-v8a，x86_64 用于模拟器开发。默认Studio开发构建不再内嵌Runner模板；显式内嵌仅使用 `-Pautoscript.embedRunnerTemplate=true`。默认视觉仍为full，不代表按需SDK设备验收已完成；双ABI与单ABI产物不能直接比较体积。

## 目录

```text
android/     Android工程：apps应用、modules共享模块、Gradle配置及缓存
rust/        Rust工作区：crates引擎、tools工具链、Cargo配置和target缓存
schema/      项目、积木、API 唯一契约
tools/       独立Python旧工程转换器及辅助脚本
scripts/     本地构建、校验和明确授权后使用的设备脚本
docs/        当前说明、操作手册与 ADR
docs-site/   独立VitePress文档站、契约生成的全部API参考及旧离线HTML
examples/    公开示例，不含私人脚本
archive/     历史性能证据、本地截图；不参与构建
build/       本地产物与验收证据（忽略）
资料/        参考源码和竞品文件（忽略，保留）
截图/        用户截图输入（忽略，保留）
```

Studio 的 main/test Kotlin 文件在 com/autoscript/studio 下按 editor、image、input、runtime、project、packaging、designer、navigation、ui 组织；包名仍为 com.autoscript.studio。查找移动文件见 [路径映射](docs/source-layout-map.json)。

## 安全与数据

项目能力声明是唯一真相；Root连接、后端就绪和实际输入特性分开。停止必须确认触点清理，失败不能显示成正常停止。日志区分脚本输出、诊断和指标，保存在 App 私有目录，不自动记录源码、变量值或模板路径。归档不会删除旧 Lua 数据或参考资料；未保存内存草稿不承诺在进程强杀后恢复。
