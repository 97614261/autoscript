# ADR 0070：运行期背景图、窗口位置与容器组操作

2026-10-11，继续函数库第三批。

复用 UI.command 白名单，不引入 Android 反射或修改已保存布局。backgroundImage/get:backgroundImage 仅用于已设计控件，图片只能来自已声明的项目图片资源，空值清除。解码限制文件16MiB、边长4096、显示采样边长512；每个界面的背景缓存最多2百万像素，超限或损坏在发布属性前失败，不静默忽略。

windowPosition/bubblePosition 用 window 为目标，值为仅含整数 x、y 的 JSON；get:windowPosition/get:bubblePosition 返回实际屏幕像素 x/y/width/height/visible。坐标被限制到当前可用屏幕，不触发显示、最小化或运行。隐藏时保留位置，窗口拖动更新查询值，屏幕变化重新限界；只操作脚本界面的最小化按钮，不操作暂停/停止控制球。

groupVisible/groupEnabled/groupReset/get:groupCount 目标必须为容器，包含容器本身与全部后代，不跨组。可见/启用仅接受布尔；重置先以当前运行期范围和选项校验所有默认值，再一次发布，不清除布局样式，不触发事件。不能隐藏、删除或替代窗口自带的停止入口。

所有新操作经宿主同步确认，有会话代次、取消、队列上限及既有3秒超时。原有界面属性语义、界面保存格式与密码排除规则不变。
