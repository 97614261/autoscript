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

## 2026-10-24：连续提示原地更新

按用户确认，持续运行提示和短时弹出提示分别复用现有窗口，不排队或叠加。
短提示以最新文字替换，并撤销旧到期任务、从最新消息重新计时；即使文字相同也续时。
复用时更新点击所属票据，旧计时器和旧运行不能关闭新消息。无悬浮权限或窗口更新失败时
清理所属短提示，沿既有系统 Toast 降级，不声称已显示自定义窗口。

文字未变不重新赋值，外观未变不重建背景；窗口几何仅在尺寸/坐标变化时更新。
仅改变字体、颜色、圆角或短提示时长不会重置持续提示已拖动的位置。
用户后台隐藏仍保持隐藏，重叠遮蔽、正常结束短提示到期与停止清理保持原语义。

本批 `build.cmd -Verify`（单测、Lint、Debug 构建及 MuMu 0 覆盖安装）通过；
提示定位/生命周期 12 项单测及 API32 MuMu 实际窗口回归 7 项通过，自有 QA 清理成功。
精确候选与测试包摘要、窗口身份/续时/点击/隐藏/重叠/停止结果保存在
`build/verification/prompt-window-reuse-device.log`，构建记录为同目录
`prompt-window-reuse-build.log` 与 `prompt-window-reuse-test-build.log`。
本批不连接真机、不运行私人脚本、不改系统设置或发布下载站。
