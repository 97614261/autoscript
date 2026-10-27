# Vision performance phase 6 · 2026-10-17

## 候选评估

曾尝试为四种角点方向增加增量行列游标，中心向外方向保留原状态机。搜索顺序、
步长、ROI、预算、取消检查和结果均未改变。

新增固定序列测试覆盖非对称 ROI、非 1 步长和四种角点方向；已有所有方向唯一性、
颜色、模板、透明像素、预筛、预算和取消回归继续使用原路径。随后第八阶段的同进程
交错 A/B 基准发现该候选反而变慢，已撤回生产代码。

## 主机复测

命令：

```text
cargo fmt --all -- --check
cargo test -p pixel-vision
cargo test -p pixel-vision --release -- benchmark_fixed_search_matrix --ignored --nocapture
```

撤回前的单次矩阵（Windows x86_64，合成 720×1280 RGBA 帧，200×122 模板）结果：

| 场景 | P50 | P95 | P99 |
|---|---:|---:|---:|
| early | 0.025 ms | 0.037 ms | 0.044 ms |
| late | 0.494 ms | 0.795 ms | 0.955 ms |
| missing | 0.466 ms | 0.734 ms | 1.074 ms |
| multi-four | 1.899 ms | 2.628 ms | 3.387 ms |
| large-missing | 4.039 ms | 5.684 ms | 6.111 ms |

当时的独立运行结果受主机调度噪声影响，不能作为合入依据；第八阶段的公平 A/B
基准覆盖了相同输入和同一进程顺序，最终拒绝该游标候选，不宣称固定端到端提速。

## 当前边界

`adb devices` 当前只返回 `emulator-5554 offline`，因此第六阶段没有进行 MuMu
设备安装、重启、真实截图或私人脚本运行。设备端仍需在同一 ABI、分辨率、ROI、模板、
方向和后端下连续至少 100 次采样，并分别记录截图、传输、帧入池和匹配分段。
