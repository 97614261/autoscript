# 识图与常用函数性能专项

2026-10-16，用户授权开始测试优化。与已结束的非真机功能验收分开记账，不新增产品能力。

## 固定范围与顺序

1. 在生产修改前建立主机与MuMu原版基线。固定公开合成输入，记录构建配置、ABI、屏幕/ROI/模板、样本数、首次调用与热样本最近秩P50/P95/P99；不同阶段分位数不相加。
2. 图像覆盖截图往返、小/大ROI、靠前/靠后/未命中及多模板。常用函数覆盖计算、Unicode文本切片、正则、JSON/列表和可视化循环；系统输入及文件IO分开测，不把主动等待算CPU开销。
3. 依据基线只优化明确重复遍历/分配或其他可验证瓶颈；不替换模型、改变算法/结果/坐标/能力，不关闭鉴权、取消、预算或日志来换速度。未测得收益的候选不写成提速。
4. 同条件复测，再跑正确性、取消/预算、静态/单测/包检查及相关MuMu回归。只安装门禁通过的候选，保留数据；不运行私人脚本，不清理未知QA或用户日志。

主机性能测试和Android构建/设备基准串行，避免已知并发负载影响。主机结果不冒充ARM真机结果；设备首次调用不自动等于进程冷启动。函数批量测量要标为每批/每次摊销，不冒充独立调用的尾延迟。

## 已完成的第一轮代码优化

- 文本字符定位不再先遍历全串计数再定位；切片按Unicode边界一次复制，仍按字符1起索引，保留合法末尾插入、截短和64KiB预算。
- JSON验证直接借用数组/对象遍历，去掉每层临时子节点Vec；键、深度、总项数和错误顺序保持不变。没有增加跨调用JSON缓存。
- 数学参数最多4个，使用栈上数组；i32范围整数经精确From转换，不再先分配十进制字符串。大整数仍走原转换和有限数预算，无精度放宽。
- 新增显式忽略的固定输入CPU基准和独立androidTest性能模式；生产不包含测试画面、样本采集器或QA工程。

## 主机CPU实测

Windows x86_64，Cargo release；每例100个批量摊销样本，先10批热身，轮换测试顺序；单位为微秒/次，最近秩P50/P95/P99。这是实际Rust操作，不含Lua调度/JNI/Android/Root，不能据此宣称整个脚本同比提速。计时循环前校验每个输入的真实结果。

| 固定输入 | 批内次数 | 原版P50/P95/P99 | 优化P50/P95/P99 |
| --- | ---: | --- | --- |
| 64KiB ASCII，起点2/长度4096 | 100 | 4.666 / 5.574 / 6.938 | 2.354 / 2.768 / 3.289 |
| Unicode 28672字节，起点2/长度2048 | 100 | 14.583 / 17.230 / 20.037 | 13.281 / 15.200 / 22.044 |
| 64KiB ASCII，起点60000/长度1024 | 100 | 20.904 / 23.648 / 27.184 | 18.918 / 21.845 / 28.089 |
| 4KiB文本正则 `[0-9]+` | 20 | 28.405 / 37.630 / 46.945 | 28.295 / 34.600 / 38.745 |
| 512项JSON数组读取第512项 | 20 | 9.470 / 12.870 / 15.825 | 9.105 / 12.470 / 22.280 |
| 整数限制范围42/0/100 | 1000 | 0.1256 / 0.1658 / 0.2790 | 0.0118 / 0.0144 / 0.0278 |

ASCII头部切片P50约减少50%，本例整数数学计算部分约减少91%。Unicode/尾部/JSON的尾分位存在波动，不宣称所有分位改善；JSON只确认消除临时分配，正则没有生产改动。中间重复测量保存在本地build/performance-2026-10-16-cpu-after*.log；最终使用cpu-after-optimized.log。

像素算法未修改。既有5条件CPU矩阵复测：720×1280、200×122、RGBA、相似度1000、容差0、方向0，100样本/10热身。ROI(149,407,546,879)的靠前/靠后/未命中/4模板优化后P50分别0.033/0.569/0.531/2.098ms；全屏未命中4.533ms。与原版0.033/0.572/0.546/2.144/4.583ms相近，不把噪声写成图像算法提速。

## MuMu原版基线

MuMu 0，127.0.0.1:16385，API32，实际x86_64，development/full Debug；生产安装沿用上一轮冻结包SHA-256 `848C472779D7133C5B4F4693C7466FF183CC585583490C4C3ED8B0123AC04B31`，只更新测试APK。自有项目和自绘画面，200×122不透明模板、720×1280屏幕、相似度1000、容差0、方向0，每轮重新截图、匹配后释放帧。每条件111次，首调用独立记录，再排除10次热身，100热样本。Root已认证，首调用不等于进程/Root冷启动。

| 条件/半开ROI | 截图P50/P95/P99 ms | 匹配P50/P95/P99 ms |
| --- | --- | --- |
| 靠前(480,948,700,1090) | 32.718 / 39.885 / 42.183 | 0.048 / 0.065 / 0.085 |
| 靠后(0,0,720,1280) | 26.779 / 42.003 / 50.416 | 4.514 / 5.878 / 6.740 |
| 未命中(0,0,720,948) | 27.461 / 37.521 / 39.112 | 4.097 / 5.323 / 7.808 |

匹配计时使用真实5101观测，截图使用5000宿主往返；不能相加两个阶段的百分位。固定数据下主要耗时在实时取帧，不是小ROI的像素匹配。没有为提速复用旧屏、关闭指标、鉴权或取消。

本地证据：build/performance-2026-10-16-device-before-final.log，4/4通过、完成码-1、cleanup=true；每条件111截图/111匹配，丢弃/截断记录均0，命中状态和坐标已校验。前两次测试统计器漏选5101导致样本检查失败，作为无效尝试保留，不并入基线、不改写为通过。

## 验证与剩余边界

已通过严格workspace Clippy、Rust 520项测试（13个显式忽略的性能测试）、527份JSON/187个Schema/160个API/177个积木契约检查。新增Unicode参考实现逐边界对照及JSON项数/键/溢出、整数快慢路径一致性测试。

## Android候选及同条件复测

候选APK：android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk，SHA-256 `DF45B9FC4E1A2798F377DF2166E3FC6677F6C5E03285CCCA8A3A6DAAB8475F37`，172884661字节；ZIP有效压缩载荷172678110字节，布局/签名/填充206551字节。development双ABI、默认full，原OCR模型摘要不变，独立Runner未嵌入。Verify构建1176任务/52执行，1153项Android单测0失败/0错误、2项既有跳过，Lint Fatal/Error为0，包检查和目录检查通过，保留数据安装MuMu 0。

同一测试APK和条件重新执行：4/4通过，完成码-1，cleanup=true；333次截图和333次匹配，零丢弃、零截断，命中状态/坐标不变。

| 条件（同上ROI） | 截图P50/P95/P99 ms | 匹配P50/P95/P99 ms |
| --- | --- | --- |
| 小ROI靠前 | 23.914 / 33.912 / 35.418 | 0.046 / 0.080 / 0.109 |
| 全屏靠后 | 26.338 / 35.035 / 37.345 | 4.514 / 5.823 / 7.706 |
| 大ROI未命中 | 30.509 / 36.080 / 37.181 | 3.633 / 3.839 / 4.403 |

首截图分别361.915/26.338/30.691ms，首匹配0.043/4.235/3.576ms；首个取帧有明显单次波动，已独立保留，没有混入热样本或冒充冷启动统计。本批没有改变图像算法和截图实现，因此热取帧范围23.9–30.5ms、匹配分位的变化仅作为对照，不归因于本次函数优化。不能承诺所有场景P99变快；小ROI匹配与全屏靠后匹配P99仍有波动。

本地证据：build/performance-2026-10-16-device-after.log；边界回归build/performance-2026-10-16-boundaries.log，30/30通过、完成码-1、cleanup=true，覆盖等待/取消、连续运行、错误隔离和日志清理等原语义。MuMu重启后端口由16385变为16384，已按管理器实际端点复核，不把旧端口离线状态当应用失败。

编辑器核心回归build/performance-2026-10-16-overlay-core.log，9/9通过、完成码-1、cleanup=true：展开/最小化灵动环、最大化还原、更多菜单不跳动、单步、运行高亮、暂停继续、停止、Root点击和关闭清理均通过。一次带完整关闭收口的尝试额外包含键盘遮挡项；因未启用用户明确授权才可使用的临时QA输入法，该单项报告QA_LAYOUT_IME_NOT_VISIBLE，其余29项通过，不能归因于本批代码，也没有切换用户输入法。

前一阶段记录曾将真实设备/Android7、OpenCV/ONNX冷启动、全部函数逐项延迟、文件IO及系统输入吞吐列为待验；这些非物理设备项目已在下方最终收口记录补测。真实可视化循环仅用于本轮驱动截图匹配，不冒充纯循环CPU基准。独立Runner范围及正式OCR模型不变。

## 复跑命令

主机在rust/执行，串行运行，完整结果保留在本地build/：

```powershell
cargo test --offline --release -p runtime-executor benchmark_common_function_matrix -- --ignored --nocapture
cargo test --offline --release -p pixel-vision benchmark_fixed_search_matrix -- --ignored --nocapture
```

MuMu 端口会因实例重启变化，复跑前以 MuMuManager.exe info --vmindex 0 返回的 adb_port 替换示例中的端口。

MuMu：先完成测试APK构建/安装，再运行下面的独立模式；不要在运行中安装APK或同时跑其他基准。必须显式设备及确认Studio中无未保存私人工作。

```powershell
./scripts/run-studio-boundary-acceptance.ps1 -DeviceSerial 127.0.0.1:16384 -Suite performance -ConfirmTargetRestart
```

## Final non-physical-device closeout (2026-10-16)

The remaining non-physical-device checks were run after the APK/build and emulator state were
recovered. All commands used synthetic data or the owned Studio acceptance fixture; no private
script was opened or executed.

- Repository/schema gates: `scripts/check-repository-layout.ps1`, `scripts/audit-studio-functions.ps1`
  and `scripts/syntax-check.ps1` passed. The catalog contains 177 published kinds with exact
  schema/compiler registration pairs.
- Rust gate: `build/performance-2026-10-16-rust-final-pass.log` contains 520 passed, 0 failed and
  13 explicitly ignored tests. Clippy with `-D warnings`, formatting, schema-check (527 JSON
  documents/187 schemas/160 APIs/177 blocks) and generated-contract checks passed. The first run
  hit Windows sandbox `os error 5` while renaming a temporary compiler file; the same unchanged
  tests passed with `TEMP/TMP` bound to `build/rust-tmp`.
- Android host gate: `build/performance-2026-10-16-android-final.log` reports `BUILD SUCCESSFUL`
  for all unit-test tasks, Studio debug APK and Runner template debug APK. Lint completed without
  fatal/error findings. The first wrapper attempt was blocked by the restricted Gradle user-cache
  lock; rerunning with the existing distribution cache completed successfully.
- Host performance: `build/performance-2026-10-16-functions-final.log` and
  `build/performance-2026-10-16-pixel-final.log` passed the fixed function and pixel matrices;
  `build/performance-2026-10-16-input-wire.log` passed the authenticated packet/input wire
  benchmarks (3/3 ignored benchmarks). `build/performance-2026-10-16-project-data.log` passed
  the new bounded private-data IO benchmark (write/read/append/copy/zip, 100 samples each).
- Input semantics: `build/performance-2026-10-16-input-matrix.log` passed 25 root-client and 23
  root-daemon-core tests, including tap, long-press, swipe, key, recording cancellation and
  replay/orientation validation. These are protocol/host tests, not physical touch latency.
- MuMu 0 (`127.0.0.1:16384`, API 32, x86_64): `build/performance-2026-10-16-boundaries-final.log`
  passed the 9-case editor/overlay/root boundary suite and
  `build/performance-2026-10-16-performance-final.log` passed the same synthetic performance
  mode. The previous 30-case boundary, provider-soak and 20,000/2,000-call provider records
  remain valid in their earlier logs.
- MuMu vision/component suites: preprocessing, Script UI and sandbox passed. The ordinary full
  vision run is intentionally `pass=false` only because monospace `l/1` is still ambiguous;
  sans-serif cases pass. OCR diagnostics reports the same known `l/1` boundary. OCR evaluation
  is an evaluation report, not a production gate, and passed its strategy harness. The current
  full APK correctly rejects external-provider testing with `BUNDLED_SDK_WOULD_MASK_PROVIDER`;
  provider acceptance remains covered by the separately built provider APK logs. Missing-component
  rejection is likewise a profile-specific guard and is covered by the existing
  `build/performance-2026-10-16-missing-components.log` record.

The only intentionally unrun scope is physical hardware (ARM64 phone, Android 7/other ROMs and
real input methods). The production OCR model and default full visual profile were not replaced.
