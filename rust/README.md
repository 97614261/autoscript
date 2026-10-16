# Rust 工作区

核心引擎在 crates/，Studio工具链与主机工具在 tools/；Cargo配置、锁文件、固定工具链和忽略的 target/ 缓存均位于本目录。
Android工程在上级仓库的android/，仓库根Gradle入口转发至该工程；对应模块自动构建与打包Rust产物，不需要先手动复制SO。Python转换器仍在仓库根tools/legacy-converter/。

在本目录运行：

```powershell
cargo fmt --all --check
cargo test --workspace --offline
cargo clippy --workspace --all-targets --offline
cargo run --offline -p schema-check -- ..
cargo run --offline -p api-codegen -- check ..
```

schema、examples、docs-site和Android目录仍在上一级仓库根；生成器及发布工具的资料路径不要误指本目录。
目录约束见[ADR0056](../docs/adr/0056-contained-rust-workspace.md)，构建入口见[项目说明](../README.md)。
