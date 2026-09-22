# ADR-0002：运行时截图悬浮窗与 Studio 交接

日期：2026-09-20

## 背景

Lua 编辑器内的截图入口只能在 Studio 位于前台时调用 Root 截图，因此即时截图会采集到编辑器本身。用户需要像脚本工具一样：在目标 App 上方保留一个可拖动的相机入口，点击后先采集目标 App，再返回编辑器做取色、取坐标和生成 Lua。

## 决定

- 相机入口属于 `runtime-service` 的系统悬浮窗，而不是 Studio 的 Compose 页面；它与运行控制浮标共用悬浮窗权限。
- Runner 只保存最近一次成功启动的、不透明的 `projectId`，不依赖 Studio 的项目实现。
- Runner 采集 Root 帧后将有界 PNG 写入应用私有 cache 的一次性交接目录，并通过 `RuntimeProtocol` 定义的显式 action 启动 Studio。
- 交接 intent 只携带受格式校验的项目 ID、随机 token 和 PNG 尺寸；Studio 根据 token 在同 UID 私有 cache 中读取并删除文件，随后打开对应 Lua 项目的图像工具。
- `runtime-api` 只定义协议常量；`runtime-service` 不引用 Studio 类，Studio 也不直接访问 Runner 的实现。这保持 Runner/Studio 的单向依赖边界。
- 相机仅在 Runner 空闲、停止或失败状态可用。运行中的脚本保持原有“截图通道不可占用”约束，避免与脚本视觉操作竞争。

## 后果

- 启动协议新增项目 ID，因此 Runtime Protocol 版本递增；Studio 与 Runner 必须由同一构建产物安装。
- 截图交接失败只记录到 Runner 日志，不会暴露缓存路径或图片数据给 intent/日志。
- 交接图像只用于当前一次编辑器打开，Studio 解码后立即删除文件。
