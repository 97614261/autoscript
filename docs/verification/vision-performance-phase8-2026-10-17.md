# Vision performance phase 8 · 2026-10-17

## 目标

本阶段收口“增量候选点游标”是否值得进入 `pixel-vision` 生产路径。此前的
局部候选基准与完整搜索结果受 Windows 调度噪声影响，不能用两次独立运行直接
判断收益。本阶段改为同一进程交错 A/B，并让参考实现完整复制生产
`SearchPoints` 的状态机、中心向外状态、分派、边界检查和 `point()` 几何。

## 基准门禁

测试位于：

```text
rust/crates/pixel-vision/src/performance_matrix_tests.rs
```

新增内容：

- `division_reference_matches_production_search_points`：五种方向、非对称 ROI、
  非 1 步长逐点核对参考实现与生产实现，防止参考夹具漂移。
- `benchmark_incremental_cursor_against_division_reference`：只测候选遍历，
  同进程交错 100 次有效样本，比较 P50/P95/P99。
- `benchmark_incremental_cursor_full_search_ab`：使用相同合成帧、模板、ROI、
  预筛、预算和比较函数，交错比较完整搜索路径。

本次 release 运行结果（Windows x86_64，合成 RGBA 帧；仅为 CPU 门禁，不是
Android 或 Root 截图延迟）：

| 场景 | 除法参考 P50/P95/P99 | 增量候选 P50/P95/P99 |
| --- | --- | --- |
| early | 0.015/0.017/0.026 ms | 0.015/0.017/0.029 ms |
| late | 1.058/1.300/1.432 ms | 1.048/1.254/1.328 ms |
| missing | 1.025/1.243/1.373 ms | 1.022/1.225/1.250 ms |
| large-missing | 8.974/10.277/10.814 ms | 8.957/10.533/10.828 ms |

候选在部分中位数上略快，但 P95/P99 没有跨场景稳定改善；早命中和大 ROI
尾部存在回退，重复运行也会随主机调度产生不同方向。因此不以局部 P50
收益作为生产合入条件。

## 结论

- 阶段 8 **完成，候选拒绝合入**。
- `SearchPoints` 生产实现保持 `emitted / columns` 与 `emitted % columns`，不改变
  搜索顺序、结果、预算、取消、Lua 语义或公开 API。
- 增量实现只保留在忽略的性能门禁中，便于未来在真实 Android 同设备数据充分时
  重新评估；不得把它当成已启用优化。
- 阶段 5 的模板行偏移快路径及阶段 1–4 的已验证改动保持不变。

## 验证命令

```text
cargo fmt --all -- --check
cargo test -p pixel-vision
cargo test -p pixel-vision --release -- benchmark_incremental_cursor_against_division_reference --ignored --nocapture
cargo test -p pixel-vision --release -- benchmark_incremental_cursor_full_search_ab --ignored --nocapture
```

本阶段未安装 APK、未连接 MuMu、未重启设备、未运行私人脚本；设备端仍需在
同一 ABI、分辨率、ROI、模板、方向和后端下进行真实连续采样后，才能评估完整
截图/传输/匹配链路。
