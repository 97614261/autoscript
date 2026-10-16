# ADR 0046：运行提示生命周期与屏幕像素定位

## 决定（2026-10-05）

按用户反馈，运行中的持续提示不再在脚本结束后保留。主动停止立即清除持续提示、短时弹窗、计时器和提示操作窗；正常结束或失败清除持续提示，正常短时弹窗仍允许显示至其到期时间，避免单条提示脚本刚显示就被销毁。

两种提示共享项目 `debugSettings.popupStyle` 的屏幕像素位置、尺寸、字体、背景和文字对齐，但不混合生命周期：`Prompt.show` 持续更新、允许拖动和后台隐藏；`Prompt.toast` 到时消失。`Prompt.show(message, styleJson?)` 向既有 opcode 1101 增加可选受限样式，旧单参数调用保持有效；冻结启动计划为两种调用注入项目样式，显式样式优先。Rust 校验后通过既有有界事件队列传给 Runner，不引入项目存储依赖。

X/Y 从真实屏幕左上角计，0 是原点，-1 分别表示该轴居中；越界位置限制到屏幕内。窗口不再被创建后的硬编码居中覆盖，并显式排除系统栏 inset 对坐标的二次偏移。

提示显示队列使用独立运行代次；停止、关闭提示、重开运行后，迟到显示或旧到期回调不能重建已关闭提示或移除新提示。清理不依赖隐藏编辑器继续绘制。ADR 0006/0008 中结束后保留持续提示的决定由本条取代。

## 本批验证

- Rust 格式与 API 生成物一致性检查通过；相关 lua-runtime、runtime-executor、flow-compiler、release-packager 单元测试共 155 项通过。
- `build.cmd -Verify -StartupTimeoutSeconds 60 -InstallTimeoutSeconds 180` 最终成功，Studio 384 项测试通过，runtime-service 58 项测试中 57 通过、1 项现有环境相关跳过；其他构建脚本覆盖的模块测试也通过。Lint 无错误，现有 36 警告和 12 提示未扩大。
- Debug 包已覆盖安装 MuMu 0 号实例，保留应用数据；没有运行用户私人脚本或进行设备交互实测。
- Clippy 普通检查完成；严格 `-D warnings` 仍被仓库已有 automation-core/flow-ir 等警告阻挡，未将这次检查描述为零警告通过，也未扩张本批去修改无关代码。
# 2026-10-10: Overlapping prompt channels

When a short tip overlaps the persistent prompt, the short tip takes temporary
visual priority. The persistent view is invisible, not closed or moved; its
message continues to update and reappears at its original pixel position when
the tip expires. Non-overlapping prompts remain independently visible. Explicit
stop still invalidates both channels and cannot reveal a previous run's view.
