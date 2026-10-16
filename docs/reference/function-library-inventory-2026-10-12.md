# 函数库目录整理（2026-10-12）

当前（2026-10-15）为 **177种可视化积木、160个只读API契约**，逐项证据及后续状态见[积木矩阵](studio-block-coverage.md)和[Studio总账](studio-first-version-master-plan.md)。下方分类统计保留2026-10-12快照，不把历史数量当当前总数。

本页历史目录包含 **174 个可视化积木**，另保留 **159 个底层 API 的只读契约**。
数量不同是正常的：变量、判断、插件等是编译器结构；截图缓存等多个
API 可以由一个积木配置流程管理。API 名称不是可逆插件源码语法。

## 入口和清理边界

- 编辑器函数库、悬浮编辑窗：统一可视化目录，点名称看详情，点加入走原参数面板。
- 我的 → 函数文档：积木功能 / API 契约（只读）；后者无加入按钮，不执行示例。
- 删除：旧 Lua 函数目录、五个判断/循环语法模板、三个日志示例封装、
  默认示例参数生成器及其废弃插入分支；不是删除运行 API。
- 保留：四个旧版兼容积木、旧项目数据、Legacy 运行契约、可逆源码编辑、
  录制和图像代码的片段安全检查。没有删除用户项目或参考源码文件。
- 文本、集合、日期、音频、浮窗从混合分类中拆开；文件、文本、图像
  使用小分类标题。中英文、API 名称、参数、用途均可搜索，精确名称优先。

## 本次分类复核

- 调试：运行/弹出/动态提示、日志、输出开关、文字说明和三种对话框；右侧调试有对话框入口。
- 界面：读取页面参数、设置控件值、界面操作。底层UI.waitEvent文档也保留在界面。
- 图像：图片匹配、颜色识别，以及帧裁剪、图像变换、PNG保存。
- 屏幕：采集和缓存、连续截图、系列帧读取/释放；不混入图像处理。
- 时间：Unix墙钟、单调时钟和日期计算；系统仅保留屏幕尺寸、剪贴板、App操作。
- 插件调用/参数/返回放插件；判断、循环及跳转各自保留；任务包含等待、停止、
  重启、子任务和定时器。录制回放放按键，音频播放/音量放音频。
- 随机与几何/RGB通道运算仍为计算；文本、集合/JSON、变量各自独立。
- 四个旧工程颜色兼容项隔离到旧版兼容，不改其执行语义。

## 分类统计

| 分类 | 积木数 |
| --- | ---: |
| 插件 | 8 |
| 变量 | 3 |
| 文本 | 25 |
| 集合 | 11 |
| 计算 | 13 |
| 按键 | 9 |
| 屏幕 | 5 |
| 图像 | 15 |
| 文字 | 4 |
| 判断 | 1 |
| 循环 | 5 |
| 跳转 | 4 |
| 界面 | 3 |
| 浮窗 | 4 |
| 文件 | 28 |
| 系统 | 5 |
| 时间 | 5 |
| 音频 | 4 |
| 任务 | 8 |
| 调试 | 10 |
| 旧版兼容 | 4 |

## 当前积木清单

### 插件

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 获取调用参数 | `flow.argument.get` | 无额外能力 |
| 设置调用参数 | `flow.argument.set` | 无额外能力 |
| 调用插件 | `flow.call` | 无额外能力 |
| 调用插件 · 成功 / 失败分支 | `flow.call.branch` | 无额外能力 |
| 返回上层 | `flow.return` | 无额外能力 |
| 获取返回参数 | `flow.return.get` | 无额外能力 |
| 设置返回参数 | `flow.return.set` | 无额外能力 |
| 返回成功 / 失败 | `flow.status.return` | 无额外能力 |

### 变量

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 变量计算 | `variable.calculate` | 无额外能力 |
| 读取变量 | `variable.copy` | 无额外能力 |
| 设置变量 | `variable.set` | 无额外能力 |

### 文本

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 取标记之后 | `text.after` | core.task |
| 取标记之前 | `text.before` | core.task |
| 取两个标记之间 | `text.between` | core.task |
| 文本包含判断 | `text.contains` | core.task |
| 文本出现次数 | `text.count` | core.task |
| 文本结尾判断 | `text.endswith` | core.task |
| 查找文本位置 | `text.find` | core.task |
| 插入文本 | `text.insert` | core.task |
| 拼接文本数组 | `text.join` | core.task |
| 取文本左侧 | `text.left` | core.task |
| 文本字符数 | `text.length` | core.task |
| 转小写 | `text.lower` | core.task |
| 正则提取全部 | `text.regexall` | core.task |
| 正则提取首项 | `text.regexextract` | core.task |
| 正则匹配判断 | `text.regexmatch` | core.task |
| 移除文本片段 | `text.remove` | core.task |
| 重复文本 | `text.repeat` | core.task |
| 替换全部文本 | `text.replace` | core.task |
| 替换首个文本 | `text.replacefirst` | core.task |
| 取文本右侧 | `text.right` | core.task |
| 拆分文本 | `text.split` | core.task |
| 文本开头判断 | `text.startswith` | core.task |
| 截取文本 | `text.sub` | core.task |
| 去除首尾空白 | `text.trim` | core.task |
| 转大写 | `text.upper` | core.task |

### 集合

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 追加集合标量 | `data.append` | core.task |
| 取集合 / 对象标量 | `data.get` | core.task |
| 读取JSON子结构 | `data.getjson` | core.task |
| JSON键是否存在 | `data.has` | core.task |
| 集合 / 对象键列表 | `data.keys` | core.task |
| 集合 / 对象长度 | `data.length` | core.task |
| 移除集合 / 对象项 | `data.remove` | core.task |
| 设置集合 / 对象标量 | `data.set` | core.task |
| 解码 JSON 字符串 | `data.text` | core.task |
| 读取JSON类型 | `data.type` | core.task |
| 检查JSON合法性 | `data.valid` | core.task |

### 计算

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 两点角度 | `math.angle` | core.math |
| 提取蓝色通道 | `math.blue` | core.math |
| 限制范围 | `math.clamp` | core.math |
| 两点距离 | `math.distance` | core.math |
| 翻转角度 | `math.flipangle` | core.math |
| 提取绿色通道 | `math.green` | core.math |
| 范围判断 | `math.inrange` | core.math |
| 轮盘X坐标 | `math.joystickx` | core.math |
| 轮盘Y坐标 | `math.joysticky` | core.math |
| 随机整数 | `math.randint` | core.math |
| 随机字符串 | `math.randstr` | core.math |
| 提取红色通道 | `math.red` | core.math |
| 四舍五入 | `math.round` | core.math |

### 按键

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 按键 | `input.keyevent` | input.basic |
| 粘贴文本（含中文） | `input.pastetext` | input.basic, device.clipboard |
| 回放录制动作 | `input.playrecording` | input.basic, core.task |
| 按下 | `input.pointerdown` | input.basic |
| 移动触点 | `input.pointermove` | input.basic |
| 弹起 | `input.pointerup` | input.basic |
| 录制全局动作 | `input.record` | input.basic |
| 滑动 | `input.swipe` | input.basic |
| 点击 | `input.tap` | input.basic |

### 屏幕

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 截图并缓存 | `screen.capture` | screen.capture |
| 释放截图 | `screen.release` | screen.capture |
| 连续截图 | `screen.series` | screen.capture, screen.capture.series, core.task |
| 读取系列帧 | `screen.seriesframe` | core.task |
| 释放系列帧 | `screen.seriesrelease` | screen.capture, core.task |

### 图像

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 裁剪帧 | `screen.crop` | screen.capture |
| 保存帧为PNG | `screen.savepng` | storage.data |
| 图像变换 | `screen.transform` | screen.capture |
| 单点比色 | `vision.comparecolor` | vision.pixel |
| 颜色连通块 | `vision.components` | vision.pixel |
| 颜色计数 | `vision.countcolor` | vision.pixel |
| 查找全部颜色 | `vision.findallcolor` | vision.pixel |
| 多结果找图 · 成功/失败 | `vision.findallimages` | vision.template, screen.capture |
| 批量多点找色 | `vision.findallmulticolor` | vision.pixel |
| 区域找色 | `vision.findcolor` | vision.pixel |
| 灰度找图 · OpenCV | `vision.findgray` | vision.opencv, vision.template, screen.capture |
| 区域找图 | `vision.findimage` | vision.template, screen.capture |
| 多点找色 | `vision.findmulticolor` | vision.pixel |
| 取点颜色 | `vision.getcolor` | vision.pixel |
| 读取找图结果属性 | `vision.result` | vision.template, core.task |

### 文字

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 字母数字 · ONNX | `ocr.alphanumeric` | ocr.onnx, screen.capture |
| 从字库结果找文字 | `ocr.findtext` | ocr.glyph |
| 字库识字 | `ocr.glyph` | ocr.glyph |
| 字库逐字结果 | `ocr.glyphdetails` | ocr.glyph |

### 判断

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 如果 / 否则如果 / 否则 | `control.if` | 无额外能力 |

### 循环

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 可取消无限 / 限时循环 | `control.cycle` | core.task |
| 循环阈值检查 | `control.loopcheck` | 无额外能力 |
| 获取循环指标 | `control.loopmetric` | 无额外能力 |
| 重复次数 | `control.repeat` | 无额外能力 |
| 条件循环 | `control.while` | 无额外能力 |

### 跳转

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 跳出循环 | `control.break` | 无额外能力 |
| 结束本次循环 | `control.continue` | 无额外能力 |
| 跳转标记 | `control.goto` | 无额外能力 |
| 放置标记 | `control.label` | 无额外能力 |

### 界面

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 界面操作 | `ui.command` | ui.control |
| 读取页面参数 | `ui.get` | ui.control |
| 设置控件值 | `ui.set` | ui.control |

### 浮窗

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 读取浮窗位置 | `floating.bounds` | ui.floating |
| 关闭浮窗 | `floating.close` | ui.floating |
| 隐藏浮窗 | `floating.hide` | ui.floating |
| 显示/更新浮窗 | `floating.show` | ui.floating |

### 文件

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 追加数据文件文本 | `files.append` | storage.data |
| 复制数据文件 | `files.copy` | storage.data |
| 删除数据文件一行 | `files.deleteline` | storage.data |
| 数据目录统计 | `files.directorystats` | storage.data |
| 数据文件是否存在 | `files.exists` | storage.data |
| Base64保存文件 | `files.frombase64` | storage.data |
| 数据文件扩展名 | `files.getextension` | storage.data |
| 数据文件大小 | `files.getsize` | storage.data |
| 插入数据文件一行 | `files.insertline` | storage.data |
| 数据目录是否存在 | `files.isdir` | storage.data |
| 持久整数值累加 | `files.kvadd` | storage.data |
| 读取持久对象值 | `files.kvget` | storage.data |
| 列举持久对象键 | `files.kvkeys` | storage.data |
| 移除持久对象键 | `files.kvremove` | storage.data |
| 保存持久对象值 | `files.kvset` | storage.data |
| 读取数据文件行数 | `files.linecount` | storage.data |
| 列举项目数据文件 | `files.list` | storage.data |
| 创建数据目录 | `files.mkdir` | storage.data |
| 移动或改名数据文件 | `files.move` | storage.data |
| 读取项目数据文件 | `files.read` | storage.data |
| 读取数据文件一行 | `files.readline` | storage.data |
| 移除项目数据文件 | `files.remove` | storage.data |
| 删除空数据目录 | `files.rmdir` | storage.data |
| 文件转Base64 | `files.tobase64` | storage.data |
| 解压数据ZIP | `files.unzip` | storage.data |
| 写入项目数据文件 | `files.write` | storage.data |
| 替换数据文件一行 | `files.writeline` | storage.data |
| 压缩数据目录 | `files.zip` | storage.data |

### 系统

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 查询App信息 | `app.info` | device.apps |
| 启动App | `app.open` | device.apps |
| 读取剪贴板 | `clipboard.gettext` | device.clipboard |
| 写入剪贴板 | `clipboard.settext` | device.clipboard |
| 读取屏幕尺寸 | `system.screensize` | device.display |

### 时间

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 日期增加天数 | `date.adddays` | core.task |
| 计算时间差天数 | `date.daysbetween` | core.task |
| 格式化日期时间 | `date.format` | core.task |
| 读取单调时间 | `system.elapsed` | core.task |
| 读取Unix时间 | `system.time` | core.task |

### 音频

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 读取音乐音量 | `audio.getvolume` | audio.control |
| 播放音频 | `audio.play` | audio.control |
| 设置音乐音量 | `audio.setvolume` | audio.control |
| 停止音频 | `audio.stop` | audio.control |

### 任务

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 停止插件任务 | `task.cancel` | core.task |
| 空任务 | `task.noop` | 无额外能力 |
| 重新运行脚本 | `task.restart` | core.task |
| 等待 | `task.sleep` | core.task |
| 启动插件任务 | `task.spawn` | core.task |
| 停止整个脚本 | `task.stop` | core.task |
| 停止周期定时器 | `timer.cancel` | core.task |
| 启动周期定时器 | `timer.every` | core.task |

### 调试

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 注释 | `task.comment` | 无额外能力 |
| 输出日志 | `task.log` | core.task |
| 脚本日志开关 | `task.logenabled` | core.task |
| 动态提示 | `task.message` | core.task |
| 弹出提示 | `task.prompt` | core.task |
| 运行提示开关 | `task.promptenabled` | core.task |
| 运行提示 | `task.runprompt` | core.task |
| 确认对话框 | `ui.confirm` | ui.control |
| 布局对话框 | `ui.dialog` | ui.control |
| 关闭对话框 | `ui.dialogclose` | ui.control |

### 旧版兼容

| 功能 | 稳定标识 | 能力声明 |
| --- | --- | --- |
| 旧版多点比色 | `legacy.duodianbise` | vision.pixel.legacy |
| 旧版多点找色 | `legacy.duodianzhaose` | vision.pixel.legacy |
| 旧版区域颜色计数 | `legacy.getrectcolornum` | vision.pixel.legacy |
| 旧版RGB颜色合成 | `legacy.getrgbcolor` | vision.pixel.legacy |

API 参数、返回类型、版本及能力来自 `GeneratedFunctionDocumentation`，
与实际生成的运行契约比对；无需维护另一套手写调用模板。
契约源目录：`schema/api-schema/functions`，积木源目录：
`schema/block-catalog/blocks`。本清单是本次整理快照，不替代实时生成目录。

决定见 [ADR 0087](../adr/0087-unified-visual-function-library.md)。
