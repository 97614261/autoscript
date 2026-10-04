# ADR 0023：脚本界面运行时属性与白名单读取

状态：实现中，2026-10-02。

## 背景

ADR 0019 的共享传统 View 渲染器已经支持控件值、可见性、启用状态、选项与分页。运行时位置、尺寸、字号和颜色仍只读设计配置。旧一键玩 `ui/funJieMian.java` 中的 Get/SetWindowRect、FontSize、FontImage 和 BackImage 是能力参考，不复制其任意窗口、文件路径或 JS 调用边界。

## 决策

- 保持 runnerUi v2、页面/控件 ID、旧积木 kind 和 opcode 1203。扩展 `UI.command(id, operation, value)` 的强白名单：`x`、`y`、`width`、`height`、`fontPx`、`textColor`、`background`、`alignment`。像素仍是所属页面/容器的设计像素，颜色仍为 `#AARRGGBB`。
- 读取使用 `UI.command(id, "get:属性", "")`，返回字符串。读取白名单为以上属性及 `value`、`text`、`visible`、`enabled`、`items`、`progress`。原有写操作继续兼容，写操作不返回属性值。
- 新属性写入与属性读取使用现有 HostRequest/HostCompletion，Lua 协程挂起，由共享渲染器所在主线程完成。读取之前有序应用旧 UI 命令，不在 Rust 建立独立属性镜像，也不提前确认尚未应用的属性。新增固定 JNI UI 适配回调只接收 ID、白名单操作和字符串数据，不能暴露 View、Context、Binder、任意方法名或 JS。
- 运行状态独立于当前挂载页面：隐藏、非当前页及最小化时更新被保留；重新渲染使用有效运行属性。重置默认值清除运行覆盖，停止/替换会话丢弃旧状态。
- 白名单值使用与 runnerUi v2 相同的范围：X 0–4096、Y 0–8192、宽 20–4096、高 20–8192、字号 8–160；对齐仅 left/center/right。非法操作和值在变更状态前拒绝，不使用反射或任意 Android 方法调用。
- 新宿主请求有取消、2 秒适配等待、3 秒调度超时与背压。取消、超时和失效会话的延迟回调不得再修改控件；错误只返回稳定错误码，不泄露脚本和路径。
- 本批不扩展事件枚举。补齐现有 click/longClick/change/selection 在单选子项、列表条目等控件的真实触发；程序更新不合成用户事件。多选、任意 JS/HTML、字体文件加载、背景图片及系统浮球位置不作为本批完成范围。

## 验证

独立状态测试覆盖默认读取、属性边界、非法更新原子性、非当前页/隐藏覆盖保留和重置；Rust 覆盖白名单、真实 HostCompletion 返回、挂起/取消/超时与 UI 权限；Android 针对性单测、Lint 和编译。实际触摸、软键盘及 Android 7 设备验证单独记录。
