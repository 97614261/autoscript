# 脚本能力扩展使用说明

这批契约通过函数库配置和受限源码共用；参数与输出使用已声明变量。项目能力不是Android系统授权，旧项目不会自动勾选新增能力。完整参数以生成的API文档为准，设备验收状态见[执行清单](../reference/studio-capability-expansion-plan.md)。

## 文本、路径和文件

`Text.urlEncode/urlDecode`明确选择URL组成部分或表单模式，表单中加号代表空格；HEX/Base64按UTF-8严格编解码，错误不会返回乱码。`Text.findNth/betweenNth`查询第N次位置或标记间文本。

`Path.name/parent/normalize/join`只计算项目私有相对路径，不访问文件，也不能把`../`规范化成越界路径。`Files.modifiedTime`读取已存在普通文件的Unix毫秒，不是单调计时，不更改文件时间。

## HTTP、设备和网址

`Network.request`需要`network.http`，支持GET/POST/PUT/DELETE/HEAD/OPTIONS，仅HTTP/HTTPS，无自动重定向和重试。请求头JSON对象、正文UTF-8分别有界；GET/HEAD不能带正文，受限制头不能自定义。响应是`status/headers/bodyBase64/elapsedMs` JSON，用`Text.fromBase64`读取UTF-8正文。响应超限报错，不截断冒充完整下载；二进制不是UTF-8时不能当文本解码。网址、凭据、请求头和正文不写普通日志。

`Apps.openUrl`显式交给系统浏览器，不证明页面已经打开，也不自动下载。`System.info`按device/display/battery/network/storage/permissions读取小型JSON，不提供唯一设备ID、网络地址或提升权限。Root支持标记不等于截图/目标App实测成功。`System.keepScreenAwake`只在所属运行持有常亮，停止释放，不改变系统休眠时间。

## 图片浮窗及脚本事件

1. 先声明图片资源、结果整数变量和事件文本变量，再配置`Floating.image`：名称、图片、像素位置、大小、是否允许拖动。
2. `Floating.waitImageEvent`等待同名窗口。`timeoutMs=0`立即读取，最大60秒；返回`kind=click/drag/timeout/closed`以及窗口屏幕X/Y、局部坐标、序号和丢弃计数。
3. 根据事件JSON中的kind判断，再显式调用插件。点击不等于自动执行任意字符串回调；`Floating.closeImage`关闭该交互窗口，唤醒等待者返回closed。

拖动只在图片窗口内捕获触摸，窗口外仍操作其他App。每窗最多64条完整手势，溢出丢弃最旧并计数，不把每个移动点变成回调。停止、失败、重启清理窗口、等待与迟到事件。旧`Floating.show/hide/close/getBounds`继续触摸穿透，两类能力和名称空间分开管理。

## 图像、特征和OCR

`Screen.frameInfo`查询真实任务帧的width/height/rowStride/rotation/format/hasScreenOrigin；图片、截图和处理图均可，不另截图或释放。变换后的局部坐标仍不能直接当原屏点击坐标。

`Screen.release`释放自己持有的截图、项目图片或处理帧。新工程声明`core.task`即可释放，不需要额外截图能力；仅声明`screen.capture`的旧工程继续兼容。句柄必须仍属于当前任务，不允许释放别人的帧或已经释放的句柄。

特征匹配`Screen.findFeatures`是明确的ORB/Hamming/RANSAC算法，适合有纹理的图片，结果为0或1个矩形和内点得分；不是像素相似度，不承诺纯色按钮、透明边缘或任意尺寸都可识别。函数库可配置来源帧、模板、范围和输出，缺SDK明确拒绝。

默认`Ocr.alphanumeric`仍为既有V4。`Ocr.alphanumericV5`是固定英文移动候选，独立选择，只输出A–Z/a–z/0–9；内置ONNX/full或明确选定的V2离线ONNX组件均支持。V1外置组件拒绝扩展，不自动回退V4。ORB亦支持内置OpenCV或V2离线OpenCV组件。导入不等于选择，默认full不改用外置库存；加载后更换组件需重启进程，不自动重启。

本轮公开语料V5更快，但整行42/60低于V4的48/60，不替换默认；固定数字字库12/12，综合字符仍受字体/字号/颜色/行高影响。范围与性能条件见[六项质量交付](../verification/studio-quality-1-6-2026-10-20.md)，不声称解决所有l/1等歧义。

## 多指输入

`Input.touchDown/touchMove/touchUp`使用0–4指编号，整组触点必须由同一任务持有，使用同一设计坐标映射和共享手势时间。先按下所需手指，再组合移动和等待，最后逐指弹起；同时存在两个触点时，移动一个触点仍携带其他触点的当前位置。不要把指编号当作事件数组下标。

需要`input.multi`及Root真实多指协商，权限页会单独显示。没有支持时拒绝，不把多次单指动作伪装成多指。停止、取消和触点租约到期会清理，部分注入失败也必须清理，设备/目标App兼容效果单独验收。

## 智构输入法

在我的/权限设置中，手动进入系统启用与选择界面。`Input.imeStatus`报告是否启用、选择及连接，`Input.imeText`只提交脚本明确提供的文字，每次最多4096 UTF-8字节。它不读取周边文字、密码、剪贴板或保存输入历史；焦点切换和停止后拒绝未提交的迟到请求。

脚本不能静默开启或切换输入法。测试临时选择后必须恢复原输入法；正式使用由用户自行决定是否保留。输入连接返回接受不等于目标App的业务校验通过。

## 页面与控件枚举

`UI.command("window","get:pages","")`返回页面ID、标题和设计尺寸。`get:controls`的value为空读取当前页面，或明确指定页面ID；只返回控件ID、类型和输入类型，不切页，不泄露控件值、密码或事件内容，不修改设计稿。
