# Root 截图所有权阶段 4 · 2026-10-17

## 完成内容

- Root daemon 的 Android 截图桥新增单槽像素缓冲复用。
- 只有匹配的 `ReleaseCapture` 才会把活动帧移入复用槽；活动帧不会与下一帧
  共享可变存储。
- 完整读回和固定计时尾部通过后才移动所有权；错误/取消仍清除有效发布路径。
- 旧桥协议、BGRA 字节序、64 MiB 上限、鉴权/序号/请求关联和 `screencap`
  降级路径保持不变。

## 验证

通过：

```text
cargo fmt --all -- --check
cargo check -p root-daemon --target x86_64-linux-android
cargo test -p root-daemon-core -p root-client -p root-daemon
```

结果：`root-client` 25 个、`root-daemon` 12 个、`root-daemon-core` 23 个测试
全部通过；没有安装 APK、连接 MuMu 或运行私人脚本。

## 性能边界

这项修改只消除连续截图的整帧堆分配抖动，不改变系统截图、Bitmap 读回和
认证传输耗时。阶段日志仍需分别观察 acquisition、bridge readback、transfer
和 FramePool publish，不能把各阶段百分位相加，也不能用主机测试替代设备实测。
