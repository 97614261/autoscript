# 持续帧缓存阶段1验证

日期：2026-10-17

## 已实现

- Root `Screen.capture` 增加最多 3 帧、32MiB 总像素预算的有界缓存，命中必须满足屏幕宽高、项目代次和单调时间戳一致。
- 缓存年龄上限为 80ms；超龄、时钟回拨、屏幕尺寸/代次变化会回退到真实 Root 截图。
- 缓存像素通过 `Arc<[u8]>` 与 `FramePool` 共享，发布时仍生成新的任务帧身份，不改变 Lua/API 租约。
- 输入、停止、Root 断开/重连、会话重置和关闭会清理缓存；截图系列不走缓存。
- 运行事件增加 `captureCacheHit`、`captureCacheAgeUs`，缓存命中显示为 `continuous-frame-cache`/`cached`。

## 验证结果

- `cargo test -p automation-core -p engine-jni -p runtime-executor`：通过（25 + 38 + 104 个非忽略测试）。
- `cargo check -p engine-jni --target x86_64-linux-android`：通过。
- 尚未进行 MuMu/真机性能验收；本阶段不宣称端到端已达到 100ms，需后续使用同设备、ROI、模板和至少 100 次样本测量。
