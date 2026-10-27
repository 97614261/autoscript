# Vision performance phase 5 · 2026-10-17

## 代码收口

`pixel-vision` 精确模板比较现在只计算一次候选的首行像素偏移，然后按图像
行跨度递增。既有几何校验、字节序、透明策略和预算/取消边界不变。

整数 32 位比较候选已做交错基准，但没有稳定收益，因此仍只保留为测试路径，
不替换生产字节比较。

## 验证

```text
cargo fmt --all -- --check
cargo test -p pixel-vision
cargo test -p pixel-vision --release -- benchmark_fixed_search_matrix --ignored --nocapture
```

固定矩阵本次结果（Windows x86_64，合成 720×1280 RGBA 帧，200×122 模板）：

| 场景 | P50 | P95 | P99 |
|---|---:|---:|---:|
| early | 0.031 ms | 0.041 ms | 0.066 ms |
| late | 0.537 ms | 0.631 ms | 0.832 ms |
| missing | 0.511 ms | 0.598 ms | 0.657 ms |
| multi-four | 2.057 ms | 2.375 ms | 3.015 ms |
| large-missing | 4.342 ms | 4.702 ms | 5.824 ms |

结果与历史基线处于同一量级，受主机噪声影响，不能宣称整体加速；本阶段的
价值是减少热循环中的重复地址计算并保留等价回归门禁。阶段收口回归已完成：
`cargo test --workspace`、`cargo check -p engine-jni --target x86_64-linux-android`
和 `git diff --check` 均通过。未安装 APK、连接 MuMu 或运行私人脚本。
