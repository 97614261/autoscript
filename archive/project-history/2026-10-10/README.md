# 历史性能证据

已清理被后续实现取代的阶段计划和重复交付记录，仅保留下列 11 份含测量口径、异常调查或性能对照的历史证据。它们不代表当前状态或完成率。

历史文档中的engine/crates、根Cargo/target和Rust工具旧位置已按[ADR0056](../../../docs/adr/0056-contained-rust-workspace.md)迁至rust/。复现Cargo命令先进入rust/，仓库资源路径相应使用`../`；历史测量数据不作改写。

Android位置随后按[ADR0057](../../../docs/adr/0057-contained-android-workspace.md)统一：App在android/apps，共享模块在android/modules，Gradle工程根在android/；根打包与Gradle转发入口仍可使用。

- [compact-run-log-delivery.md](docs/compact-run-log-delivery.md)
- [exact-match-batches-and-log-screenshot-overview.md](docs/exact-match-batches-and-log-screenshot-overview.md)
- [fast-root-capture-optimization.md](docs/fast-root-capture-optimization.md)
- [log-read-error-and-performance-followup.md](docs/log-read-error-and-performance-followup.md)
- [root-session-idle-fix.md](docs/root-session-idle-fix.md)
- [run-health-and-capture-transfer-delivery.md](docs/run-health-and-capture-transfer-delivery.md)
- [template-search-followup-and-fullscreen-logs.md](docs/template-search-followup-and-fullscreen-logs.md)
- [template-search-optimization-2026-10-09.md](docs/template-search-optimization-2026-10-09.md)
- [template-search-timeout-optimization.md](docs/template-search-timeout-optimization.md)
- [vision-performance-screenshot-2026-10-06.md](docs/vision-performance-screenshot-2026-10-06.md)
- [vision-six-stage-work.md](docs/vision-six-stage-work.md)

当前说明见 [文档索引](../../../docs/README.md)，删除范围和恢复方法见 [清理记录](../../../docs/verification/repository-cleanup-2026-10-10.md)。用户本地截图仍在忽略的 archive/local-evidence/。
