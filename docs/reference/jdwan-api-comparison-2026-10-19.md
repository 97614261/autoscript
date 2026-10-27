# 简单玩公开API与智构逐项对照

读取日期：2026-10-19。对照[简单玩开发手册](https://jdwan.pages.dev/docs)的全部公开函数卡片，与审查基线的智构163个API、180种积木、编译器和运行适配实现核对。常规图色、固定字库、单指输入、文件与UI已有可用基础；后续补强和剩余差项分别列出，不是所有差项都应加入首版。

下方325行及汇总保留审查时163个API/180种积木的基线，审查后的变化见下节，不继续把历史缺项视为当前缺项。未执行对方引擎，不据此判断其准确率、速度或稳定性。

## 审查后第一批补强

本批增加九个API和九种积木，当前目录为172个API、189种积木。使用见[脚本基础工具](../guides/script-utilities.md#数学文本数据补强)，边界见[ADR0131](../adr/0131-bounded-math-text-data-utilities.md)。

- `Math.unary`开放15种单值计算，补ceil/floor、平方根、对数/指数和三角/反三角；`Math.binary`开放min/max/pow/atan2/log，仍非任意变参；`Math.roundTo`补小数位。
- `Text.formatNumber`补小数和整数补零；`Text.regexReplace`为字面替换，不支持捕获展开；`Text.filter`明确ASCII数字/字母、Unicode汉字/空白；`Text.reverse`按Unicode标量，不按字素。
- `Data.encode`补动态文字转义和已有JSON规范化，不序列化Lua表；`Data.sort`补同类型标量数组排序，不支持混合项或对象字段排序。

新能力不代表与简单玩同名函数完全兼容。URL/HEX/文本Base64、第N处标记、平台查询、图像便捷操作及其他批次仍未由本批补齐；基线汇总不用于计算本批后的完成百分比。

## 样本与计数

- 页面标注生成日期2026-10-16，摘要写323指令/324分类条目；实际读取325张函数卡片，去重英文别名为323。图色区实际35项但标题仍写34。
- `POST`由“POST”和“POST请求”重复展示；`dumpNodeTree`由“节点树导出”和“控件树导出”重复展示。下方保留325张卡片逐行核对，汇总按323个唯一别名计算，不重复记覆盖。
- “摇杆移动/coordMove”只有占位签名，无完整说明，标为待核，不猜功能。另列的54个语法关键字、运算符和运行时约定不计入API函数数。
- 远端样本是响应HTML解码后重新编码UTF-8的SHA-256：`138a1f7fe0d021bc69654ec3260109c0d9f87daae18da40599f92c930d373b7e`。
- 本方依据：[API契约快照](../../schema/api-schema/snapshot/contract.json)、[积木登记矩阵](studio-block-coverage.md)、[生成函数文档入口](../../android/apps/studio-android/src/main/java/com/autoscript/studio/editor/FunctionApiReference.kt)。不拿旧版对照表中的历史缺项冒充当前结论。

## 汇总口径

| 状态 | 唯一别名数 | 含义 |
|---|---:|---|
| 已有 | 58 | 已有同类脚本API或积木入口；仍遵守本方类型、配额和错误协议，不代表旧调用可直接复制。 |
| 组合 | 34 | 可用现有操作或已接通向导明确组合；不是已经提供同名一步函数。 |
| 部分 | 70 | 有相近能力，但关键算法、参数、作用域、输出或生命周期有差异，不能算等价。 |
| 缺口 | 70 | 没有满足该调用关键语义的脚本入口，包括底层标准库有但可视化未开放的情形；不等于都应新建同名API。 |
| 另项 | 90 | 超出现有首版、涉及独立后端或新增高影响权限；部分已明确延期/禁止，其他需要另行决定，不自动进入开发计划。 |
| 待核 | 1 | 对方页面信息不足，无法作可靠判断。 |

函数颗粒度不同：对方多个中文包装可对应本方一个`UI.command`或一个组合；本方也有对方目录未单列的操作。因此不使用163÷323或“已有+部分”给出全项目完成百分比。文件部分覆盖多数来自有意保留的私有data、UTF-8、不覆盖及资源预算，不建议为了数字一致开放任意路径。

## 分类概览

下面按卡片计数，共325行；同别名重复项仅在上表去重。

| 分类 | 卡片 | 已有 | 组合 | 部分 | 缺口 | 另项 | 待核 |
|---|---:|---:|---:|---:|---:|---:|---:|
| 调用其它脚本类 | 2 | 1 | 0 | 1 | 0 | 0 | 0 |
| UI窗口类 | 19 | 8 | 2 | 7 | 2 | 0 | 0 |
| 图色类 | 35 | 13 | 13 | 6 | 3 | 0 | 0 |
| 手指模拟类 | 34 | 7 | 7 | 8 | 2 | 9 | 1 |
| 内存读写类 | 12 | 0 | 0 | 0 | 0 | 12 | 0 |
| 抓包类 | 13 | 0 | 0 | 0 | 0 | 13 | 0 |
| 卡密授权类 | 6 | 0 | 0 | 0 | 0 | 6 | 0 |
| 追踪类 | 6 | 0 | 0 | 0 | 0 | 6 | 0 |
| 安全防护类 | 3 | 0 | 0 | 0 | 0 | 3 | 0 |
| 游戏镜像 | 16 | 0 | 0 | 0 | 0 | 16 | 0 |
| OCR识字类 | 13 | 0 | 0 | 9 | 2 | 2 | 0 |
| 文件类 | 22 | 1 | 0 | 17 | 4 | 0 | 0 |
| 文本类 | 40 | 14 | 2 | 11 | 13 | 0 | 0 |
| 流程控制类 | 6 | 1 | 4 | 0 | 1 | 0 | 0 |
| 数学运算类 | 20 | 0 | 3 | 4 | 13 | 0 | 0 |
| 扩展类 | 67 | 12 | 3 | 6 | 31 | 15 | 0 |
| 无障碍类 | 9 | 0 | 0 | 0 | 0 | 9 | 0 |
| 封装计算类 | 2 | 1 | 0 | 1 | 0 | 0 | 0 |

## 重要差异

### 图像和固定字库

普通像素找图、多模板/多位置、颜色查询、滤色、裁剪/缩放和等待组合已有；滤色找图仍需要明确处理画面和模板，不是同名一步参数。实用缺口是脚本读取图片/帧尺寸、保持常亮及其恢复，以及将识别成功后动作做成更短的显式向导。

`findImageFull`在对方文档中描述为多尺度特征匹配。本方`Screen.findScaled`是有界模板缩放后像素比较；即使都叫“跨分辨率”，也不是同一算法。本方没有该文档所述颜色掩码相关系数/平方差的专用二选一接口；滤色后`findGray`只是相近处理链，不能声称已等价。

固定字库制作、修点、滤色清理、逐字结果和文字定位已存在。本方ASGLYPH与对方txt字库不兼容，不能直接导入；没有默认扫描全部字库的运行规则。ONNX仍只处理单行A–Z/a–z/0–9，不提供通用中文/版面检测。这是既定范围，不因本次对照替换模型或扩大OCR。脚本枚举/切换引擎及读取最近OCR阶段耗时还没有统一入口，UI组件管理、诊断日志不算该API已实现。

### 输入和任务

点击、长按、滑动、拖动、按下/弹起及录制回放已有单指链路；对方的0–9指序、多指同时动作及跨调用挂指规则未实现。输入文本是有焦点前提的Unicode粘贴，不是生产输入法锁定，也不保证所有目标控件接受。

`Task.spawn/cancel`是同VM所有权受控子任务。对方手册声明`runAsync`是变量隔离的独立Lua VM线程。二者不能按名称认定等价。自动捕获异常重试也缺少可视化入口；插件显式成功/失败分支不等于catch异常，更不能重试或吞掉主动停止。

### 数学 文本和结构化数据

底层Lua加载了math/string/table，但Studio的可逆描述源码和计算白名单不开放任意Lua调用。ceil/floor、平方根/幂、对数/指数、三角及反三角等13项不能因为标准库存在就算已可视化接通。取绝对值、角弧转换等可以短组合；min/max变参、小数位四舍五入/格式化仍不完整。

文本长度、截取、大小写、分割/合并、常规替换、计数、部分正则和JSON查询已有。待补方向包括动态JSON编码/转义、格式化、正则替换、字符类别过滤、反转、URL/HEX/文本Base64、数组排序及第N处标记。XML/INI、摘要和AES则应按真实用途决定；MD5/SHA1是摘要而非加密，AES不能照着含糊接口省略模式/认证边界。

### 脚本UI与设备工具

本方已有设计器、页面切换、控件值/属性/列表操作、确认框、布局对话框及命名浮窗。缺的是脚本枚举/保存布局等接口；交互图片浮窗的拖动/点击回调也没有。现有轻量浮窗明确触摸穿透，不能直接修改它冒充交互窗；页面参数快照也不是运行时保存布局。

电池、设备型号/系统版本、网络状态、内存、密度、亮度读取、剩余空间、息屏时间和统一权限查询等，App页面/诊断即使能够显示，也没有相应脚本契约。App启动和纯文本剪贴板已有；前台包查询、关闭App尚无接口。HTTP GET/POST、打开URL亦未接入脚本，不与已延期的账号网盘、在线授权混算。

### 不应自动加入的能力

内存读写、进程注入、socket Hook、VPN/tcpdump、封包重发、反调试、虚拟屏镜像和A*地图是独立能力，不是补几个按钮。账号授权、云功能、下载器、AI、免Root/无障碍按既定决定保留边界；任意Shell明确不开放。IME屏蔽、静默安装卸载、全局清理后台、设备管理器、通知读取、定位等也不能由“Root已就绪”推导为可自动实现或执行。

## 建议开发顺序

这是审查建议，尚未执行，也不把70个缺口逐项变成70个新API。

1. **高频纯计算与文本**：为常用数学补安全表达式/积木；增加可变小数位与补零格式、动态JSON编码、正则替换、字符类别过滤、数组排序。保留有限数值、Unicode、类型和预算校验，不放开任意Lua。
2. **图像流程便捷性**：读取真实帧/资源尺寸；加入显式滤色匹配及等待后动作配置；增加按项目会话恢复的常亮开关。特征匹配/颜色掩码算法先单独评估准确率和预算，不以现有多尺度冒充。
3. **只读平台与实际输入补强**：统一脚本设备/权限状态查询；区分Root就绪与实际输入能力。前台包/关闭App另定结构化命令；多指、生产IME等独立决定，不偷偷切换用户系统配置。
4. **数据和文件便捷工具**：路径工具、修改时间、文本编码；是否做XML/INI按实际脚本需要决定。继续保留私有data和不覆盖，不复制任意路径删除/二进制写入。
5. **通用网络及交互浮窗**：若确认需要，再独立设计HTTP/URL的能力、超时、取消、响应预算及密钥日志规则；图片交互窗复用明确事件模型。账号/市场/网盘、通用下载和AI仍不夹带实现。

引擎全局开关、脚本保存布局、专用变量输出标签等缺口不建议为同名兼容直接照搬：优先沿用逐调用算法、正式设计稿不可被运行期覆盖、已有变量观察窗。下一轮应按使用价值选项，而非追求323个名字全部相同。

## 源码核对入口

| 领域 | 当前实现依据 |
|---|---|
| 可视化数学/转换白名单 | [变量表达式解析器](../../rust/tools/flow-compiler/src/variable_expression.rs)、[基础工具编译](../../rust/tools/flow-compiler/src/utility_operations.rs)、[Lua库边界](../../rust/crates/lua-runtime/src/lib.rs) |
| 图像与坐标 | [视觉编译](../../rust/tools/flow-compiler/src/vision_extensions.rs)、[帧处理执行](../../rust/crates/runtime-executor/src/vision_runtime.rs)、[像素多尺度](../../rust/crates/pixel-vision/src/scaled_search.rs)、[来源几何](../../rust/crates/automation-core/src/frame_geometry.rs) |
| 等待及流程组合 | [识别等待](../../android/apps/studio-android/src/main/java/com/autoscript/studio/image/RecognitionWorkflow.kt)、[稳定识别](../../android/apps/studio-android/src/main/java/com/autoscript/studio/image/StableRecognitionWorkflow.kt)、[运行渲染生成](../../rust/tools/flow-compiler/src/render.rs) |
| 输入快捷动作 | [按键配置](../../android/apps/studio-android/src/main/java/com/autoscript/studio/input/VisualInputActionDialog.kt)、[动作组合](../../android/apps/studio-android/src/main/java/com/autoscript/studio/image/ImageToolCodeGen.kt) |
| UI属性/列表/窗口 | [控件状态](../../android/modules/script-ui/src/main/java/com/autoscript/script/ui/ScriptUiCommandState.kt)、[列表命令](../../android/modules/script-ui/src/main/java/com/autoscript/script/ui/ScriptUiListCommands.kt)、[窗口适配](../../android/modules/runtime-service/src/main/java/com/autoscript/runtime/service/RuntimeScriptUiController.kt) |
| 数据与平台 | [结构化数据](../../rust/crates/runtime-executor/src/data_runtime.rs)、[平台工具白名单](../../android/modules/runtime-service/src/main/java/com/autoscript/runtime/service/RuntimePlatformTools.kt) |
| 既有图像批次证据 | [图像八阶段交付](../verification/image-tools-eight-stages-2026-10-19.md)；不作为对方API等价或本轮实测证据 |

## 全量逐项表

每一行给出明确的本方API/积木或缺失边界。API名是本方稳定契约；积木kind不是可直接执行的Lua函数。相似名字不能保证参数、结果、坐标系和取消语义相同。

### 调用其它脚本类

[对方该分类](https://jdwan.pages.dev/docs#cat-86ad0e)，2张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| 插入脚本 (`runScript`) | 已有 | 积木 `flow.call` | 以同项目已声明插件调用替代按脚本名执行，不开放任意Lua文件。 |
| 移动悬浮球 (`moveBubble`) | 部分 | `UI.command`、积木 `ui.command` | 仅脚本配置界面的bubblePosition可读写；不是编辑/运行控制球，透明度和尺寸参数未对齐。 |

### UI窗口类

[对方该分类](https://jdwan.pages.dev/docs#cat-b923b2)，19张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| 显示窗体 (`showForm`) | 组合 | `UI.command`、积木 `ui.command` | 选择已保存页面后show；页面ID和布局名、生命周期需适配。 |
| 关闭窗体 (`closeForm`) | 部分 | `UI.command`、`UI.closeDialog`、积木 `ui.command`、积木 `ui.dialogclose` | 主界面可hide并保留状态，对话框可close；不是任意窗体卸载。 |
| 取控件值 (`getControlValue`) | 部分 | `UI.getValue`、积木 `ui.get` | 功能已有，但返回字符；布尔控件true/false，不是对方的整数1/0。 |
| 取控件属性 (`getControlProperty`) | 已有 | `UI.command`、积木 `ui.command` | get:白名单属性读真实状态；控件和属性名按本项目契约。 |
| 设控件值 (`setControlValue`) | 已有 | `UI.setValue`、积木 `ui.set` | 更新已设计控件，不触发用户事件，值类型需符合定义。 |
| 设控件属性 (`setControlProperty`) | 已有 | `UI.command`、积木 `ui.command` | 支持文字/可见/启用/位置/尺寸/样式等白名单，非任意Android属性。 |
| 绑定事件 (`bindControlEvent`) | 部分 | `UI.waitEvent` | 设计器绑定插件并消费事件；没有运行时任意Lua回调重绑定接口。 |
| 刷新控件 (`refreshControl`) | 组合 | `UI.setValue`、`UI.command`、积木 `ui.set`、积木 `ui.command` | 控件写入会自动刷新，无需独立refresh；没有强制重建任意控件函数。 |
| 加载布局 (`loadLayout`) | 部分 | `UI.command`、`UI.showDialogAndWait`、积木 `ui.command`、积木 `ui.dialog` | 切换或显示已保存页面；不能从任意文件动态加载布局。 |
| 列出布局 (`listLayouts`) | 缺口 | 无等价脚本入口 | 脚本没有枚举页面或持久化当前布局API；设计器保存、参数快照不算等价。 |
| 保存布局 (`saveLayout`) | 缺口 | 无等价脚本入口 | 脚本没有枚举页面或持久化当前布局API；设计器保存、参数快照不算等价。 |
| 加入表项 (`addListItem`) | 已有 | `UI.command`、积木 `ui.command` | itemAdd/itemInsert/itemRemove已实现；本方1起索引、最多64项，需适配0起索引。 |
| 删除表项 (`removeListItem`) | 已有 | `UI.command`、积木 `ui.command` | itemAdd/itemInsert/itemRemove已实现；本方1起索引、最多64项，需适配0起索引。 |
| 创建悬浮窗 (`createFloatWindow`) | 部分 | `Floating.show`、`Floating.close`、积木 `floating.show`、积木 `floating.close` | 命名轻量浮窗支持文字/颜色/尺寸；触摸穿透，窗口透明度与定时关闭需显式组合。 |
| 更新悬浮窗文字 (`updateFloatWindowText`) | 已有 | `Floating.show`、积木 `floating.show` | 同名show更新内容；保留其他样式需传入原配置，不是仅文字两参。 |
| 关闭悬浮窗 (`closeFloatWindow`) | 已有 | `Floating.close`、积木 `floating.close` | 关闭指定名称的自有轻量浮窗；ID映射为名称。 |
| 显示图片 (`showImage`) | 部分 | `Floating.show`、积木 `floating.show` | 可展示声明图片及文字，但没有图片窗拖动/点击/拖动后点击回调，也不执行脚本名。 |
| 取图片位置 (`getImagePos`) | 部分 | `Floating.getBounds`、积木 `floating.bounds` | 可读轻量浮窗实际矩形；该窗不支持用户拖动，不能视为对方拖动窗完整等价。 |
| 关闭图片 (`closeImage`) | 已有 | `Floating.close`、积木 `floating.close` | 关闭指定名称的自有轻量浮窗；ID映射为名称。 |

### 图色类

[对方该分类](https://jdwan.pages.dev/docs#cat-2eb70f)，35张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| 取色 (`getColor`) | 已有 | `Screen.getColor`、`Screen.loadImage`、积木 `vision.getcolor`、积木 `screen.loadimage` | 可从截图或已声明图片帧取色；返回0xRRGGBB整数，不是#文本。 |
| 找色 (`findColor`) | 已有 | `Screen.findColor`、积木 `vision.findcolor` | 区域颜色搜索已实现；逐通道容差与对方百分比阈值不能直接互换。 |
| 找色扩展 (`findColorEx`) | 已有 | `Screen.findAllColor`、积木 `vision.findallcolor` | 有界返回最多256项，JSON结构及数量/截断处理需适配。 |
| 比色 (`compareColor`) | 已有 | `Screen.compareColor`、积木 `vision.comparecolor` | 返回布尔；阈值采用逐通道容差。 |
| 取色点个数 (`getColorNum`) | 已有 | `Screen.countColor`、积木 `vision.countcolor` | 区域计数有显式limit，不能把截断值当完整计数。 |
| 区域截图 (`captureRegion`) | 组合 | `Screen.capture`、`Screen.cache`、`Screen.crop`、`Screen.savePng`、`Screen.release`、积木 `screen.capture`、积木 `screen.crop`、积木 `screen.savepng`、积木 `screen.release` | 原像素截取再保存；运行期仅私有data，不写任意设备路径。 |
| 全屏截图 (`captureScreen`) | 部分 | `Screen.capture`、`Screen.savePng`、积木 `screen.capture`、积木 `screen.savepng` | 截图/PNG已有，保存限私有data且拒绝覆盖；不返回任意路径或false失败协议。 |
| 模糊找色 (`fuzzyFindColor`) | 已有 | `Screen.findColor`、积木 `vision.findcolor` | 区域颜色搜索已实现；逐通道容差与对方百分比阈值不能直接互换。 |
| 模糊找图 (`fuzzyFindImage`) | 已有 | `Screen.findImage`、`Screen.findImages`、积木 `vision.findimage` | 像素模板/多模板已有；不写对方隐式找到表，结果变量必须声明。 |
| 找图 (`findImage`) | 已有 | `Screen.findImage`、`Screen.findImages`、积木 `vision.findimage` | 像素模板/多模板已有；不写对方隐式找到表，结果变量必须声明。 |
| 全分辨率找图 (`findImageFull`) | 部分 | `Screen.findScaled`、积木 `vision.findscaled` | 仅250–4000‰最多16档像素缩放；不等价智能特征匹配/旋转/跨分辨率模型，也不自动点击。 |
| 找图全部 (`findImageAll`) | 已有 | `Screen.findAllImages`、积木 `vision.findallimages` | 多模板多位置已有，最多64个保留结果，含重叠且显式truncated。 |
| 滤色找图 (`findImageFiltered`) | 组合 | `Screen.transform`、`Screen.findImage`、积木 `screen.transform`、积木 `vision.findimage` | 须明确用相同滤色规则处理画面及模板，再匹配；不是现成一步快捷配置。 |
| 滤色找图点击 (`tapImageFiltered`) | 组合 | `Screen.transform`、`Screen.findImage`、`Input.tapScreen`、积木 `screen.transform`、积木 `vision.findimage`、积木 `vision.resultaction` | 滤色/匹配后显式选择原屏坐标动作，处理过几何的帧须先还原；无一步捷径。 |
| 取图尺寸 (`getImageSize`) | 缺口 | 无等价脚本入口 | 编辑器能显示资源尺寸，但脚本无任意图片或已有帧宽高读取接口。 |
| 保持屏幕 (`keepScreen`) | 缺口 | 无等价脚本入口 | 没有脚本级保持常亮/恢复配对接口；前台服务不等于屏幕常亮。 |
| 释放屏幕 (`releaseScreen`) | 缺口 | 无等价脚本入口 | 没有脚本级保持常亮/恢复配对接口；前台服务不等于屏幕常亮。 |
| 释放图缓存 (`releaseImageCache`) | 部分 | `Screen.release`、积木 `screen.release` | 可释放自己持有的帧；不开放全局清空所有模板/SDK缓存的函数。 |
| 等到颜色 (`waitForColor`) | 组合 | `System.elapsedRealtimeMillis`、`Task.sleep`、积木 `control.cycle`、积木 `control.if` | 等待向导展开显式截图/识别/计时/释放；保留总期限，不提供同名API。 |
| 等到颜色消失 (`waitForColorGone`) | 组合 | `System.elapsedRealtimeMillis`、`Task.sleep`、积木 `control.cycle`、积木 `control.if` | 等待向导展开显式截图/识别/计时/释放；保留总期限，不提供同名API。 |
| 找图等 (`waitForImage`) | 组合 | `System.elapsedRealtimeMillis`、`Task.sleep`、积木 `control.cycle`、积木 `control.if` | 等待向导展开显式截图/识别/计时/释放；保留总期限，不提供同名API。 |
| 找图等点击 (`waitForImageTap`) | 组合 | `System.elapsedRealtimeMillis`、`Task.sleep`、`Input.tapScreen`、积木 `control.cycle`、积木 `control.if`、积木 `vision.resultaction` | 等待完成分支再显式接点击；没有一键等待并点击向导。 |
| 图片缩放 (`resizeImage`) | 组合 | `Screen.loadImage`、`Screen.transform`、`Screen.savePng`、积木 `screen.loadimage`、积木 `screen.transform`、积木 `screen.savepng` | 最近邻原像素变换；加载限声明资源，保存新私有PNG，不原地覆写任意文件。 |
| 图片裁剪 (`cropImage`) | 组合 | `Screen.loadImage`、`Screen.crop`、`Screen.savePng`、积木 `screen.loadimage`、积木 `screen.crop`、积木 `screen.savepng` | 半开ROI裁剪并另存；来源/保存路径受项目边界约束。 |
| 图片转Base64 (`imageToBase64`) | 部分 | `Files.toBase64`、积木 `files.tobase64` | 仅私有data文件≤49152字节；不是任意声明图片/大PNG的统一编码入口。 |
| 取图片像素 (`getImagePixel`) | 已有 | `Screen.getColor`、`Screen.loadImage`、积木 `vision.getcolor`、积木 `screen.loadimage` | 可从截图或已声明图片帧取色；返回0xRRGGBB整数，不是#文本。 |
| 取颜色分量 (`getColorRgb`) | 组合 | `Math.red`、`Math.green`、`Math.blue`、积木 `math.red`、积木 `math.green`、积木 `math.blue` | 三个通道函数可分别读取；返回整数分量，不生成对方中文键表。 |
| 取反色 (`invertColor`) | 组合 | 积木 `variable.calculate` | 对0xRRGGBB可用16777215-rgb计算，返回整数；没有独立反色按钮/API。 |
| 随机颜色 (`randomColor`) | 组合 | `Math.randInt`、积木 `math.randint` | 随机取0至16777215作为RGB整数；没有直接#RRGGBB文本格式化。 |
| 多图查找 (`findMultiImage`) | 已有 | `Screen.findImages`、积木 `vision.findimage` | 按模板列表优先级返回首个命中；模板标识和结果格式需适配。 |
| 多图查找全部 (`findAllMultiImage`) | 已有 | `Screen.findAllImages`、积木 `vision.findallimages` | 多模板多位置已有，最多64个保留结果，含重叠且显式truncated。 |
| 图片滤色 (`filterImage`) | 已有 | `Screen.transform`、积木 `screen.transform` | 包含/排除、多组绝对/相对规则及黑白/透明输出已有；生成独立帧，文件保存另接。 |
| 找图点击 (`tapImage`) | 组合 | `Screen.findImage`、`Input.tapScreen`、积木 `vision.findimage`、积木 `vision.resultaction` | 找图成功动作或结果动作支持点击；只使用明确匹配结果，不依赖全局找到表。 |
| 彩色找图 (`findColorImage`) | 部分 | `Screen.transform`、`Screen.findGray`、积木 `screen.transform`、积木 `vision.findgray` | 可明确滤色后二值/灰度平方差匹配；没有对方专用颜色掩码相关系数算法及0/1切换。 |
| 彩色找图点击 (`tapColorImage`) | 部分 | `Screen.transform`、`Screen.findGray`、积木 `screen.transform`、积木 `vision.findgray` | 可明确滤色后二值/灰度平方差匹配；没有对方专用颜色掩码相关系数算法及0/1切换。 |

### 手指模拟类

[对方该分类](https://jdwan.pages.dev/docs#cat-7445d0)，34张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| 点击 (`tap`) | 部分 | `Input.tap`、`Input.swipe`、`Input.pointerDown`、`Input.pointerMove`、`Input.pointerUp`、积木 `input.tap`、积木 `input.swipe`、积木 `input.pointerdown`、积木 `input.pointermove`、积木 `input.pointerup` | 点击/长按/滑动/拖动/连点可用；仅任务所属单指，不支持对方0–9指序、多指并行或跨调用任意挂指。 |
| 双击 (`doubleTap`) | 组合 | `Input.tap`、`Task.sleep`、积木 `input.tap`、积木 `task.sleep` | 重复点击和短间隔可组合；没有专用双击按钮/手指ID参数。 |
| 长按 (`longPress`) | 部分 | `Input.tap`、`Input.swipe`、`Input.pointerDown`、`Input.pointerMove`、`Input.pointerUp`、积木 `input.tap`、积木 `input.swipe`、积木 `input.pointerdown`、积木 `input.pointermove`、积木 `input.pointerup` | 点击/长按/滑动/拖动/连点可用；仅任务所属单指，不支持对方0–9指序、多指并行或跨调用任意挂指。 |
| 滑动 (`swipe`) | 部分 | `Input.tap`、`Input.swipe`、`Input.pointerDown`、`Input.pointerMove`、`Input.pointerUp`、积木 `input.tap`、积木 `input.swipe`、积木 `input.pointerdown`、积木 `input.pointermove`、积木 `input.pointerup` | 点击/长按/滑动/拖动/连点可用；仅任务所属单指，不支持对方0–9指序、多指并行或跨调用任意挂指。 |
| 拖动 (`drag`) | 部分 | `Input.tap`、`Input.swipe`、`Input.pointerDown`、`Input.pointerMove`、`Input.pointerUp`、积木 `input.tap`、积木 `input.swipe`、积木 `input.pointerdown`、积木 `input.pointermove`、积木 `input.pointerup` | 点击/长按/滑动/拖动/连点可用；仅任务所属单指，不支持对方0–9指序、多指并行或跨调用任意挂指。 |
| 弹起手指 (`releaseFinger`) | 部分 | `Input.pointerUp`、积木 `input.pointerup` | 只释放当前任务持有的单指，不能按ID或-1释放所有其他触点。 |
| 安装apk (`installApk`) | 另项 | 无等价脚本入口 | 未开放脚本静默安装/卸载或全局清理后台；高影响设备写操作需另定能力和确认策略。 |
| 卸载apk (`uninstallApk`) | 另项 | 无等价脚本入口 | 未开放脚本静默安装/卸载或全局清理后台；高影响设备写操作需另定能力和确认策略。 |
| 清理缓存 (`clearCache`) | 另项 | 无等价脚本入口 | 未开放脚本静默安装/卸载或全局清理后台；高影响设备写操作需另定能力和确认策略。 |
| 清理内存 (`clearMemory`) | 另项 | 无等价脚本入口 | 未开放脚本静默安装/卸载或全局清理后台；高影响设备写操作需另定能力和确认策略。 |
| 按键 (`keyPress`) | 已有 | `Input.keyEvent`、积木 `input.keyevent` | 白名单Android键码已有；具体支持取决于Root后端，不以音频设置代替按键。 |
| 返回键 (`pressBack`) | 已有 | `Input.keyEvent`、积木 `input.keyevent` | 白名单Android键码已有；具体支持取决于Root后端，不以音频设置代替按键。 |
| 主页 (`pressHome`) | 已有 | `Input.keyEvent`、积木 `input.keyevent` | 白名单Android键码已有；具体支持取决于Root后端，不以音频设置代替按键。 |
| 最近任务 (`pressRecents`) | 已有 | `Input.keyEvent`、积木 `input.keyevent` | 白名单Android键码已有；具体支持取决于Root后端，不以音频设置代替按键。 |
| 音量加 (`volumeUp`) | 已有 | `Input.keyEvent`、积木 `input.keyevent` | 白名单Android键码已有；具体支持取决于Root后端，不以音频设置代替按键。 |
| 音量减 (`volumeDown`) | 已有 | `Input.keyEvent`、积木 `input.keyevent` | 白名单Android键码已有；具体支持取决于Root后端，不以音频设置代替按键。 |
| 输入文本 (`inputText`) | 部分 | `Input.pasteText`、积木 `input.pastetext` | Unicode剪贴板+系统粘贴，需焦点支持，会替换剪贴板；不等价自带IME逐字注入。 |
| 锁定输入法 (`lockIme`) | 另项 | 无等价脚本入口 | 没有生产输入法锁定/屏蔽其他IME；QA键盘仅测试授权，不能算正式功能。 |
| 解除输入法锁定 (`unlockIme`) | 另项 | 无等价脚本入口 | 没有生产输入法锁定/屏蔽其他IME；QA键盘仅测试授权，不能算正式功能。 |
| 启动应用 (`launchApp`) | 已有 | `Apps.open`、积木 `app.open` | 启动可见启动器入口；请求成功不保证已经前台。 |
| 关闭应用 (`killApp`) | 缺口 | 无等价脚本入口 | 无脚本关闭指定App/读取当前前台包接口；Apps.info只是可见App版本查询。 |
| 取当前应用 (`currentApp`) | 缺口 | 无等价脚本入口 | 无脚本关闭指定App/读取当前前台包接口；Apps.info只是可见App版本查询。 |
| 随机点击 (`randomTap`) | 组合 | `Math.randInt`、`Input.tapScreen`、积木 `math.randint`、积木 `input.tap` | 在明确矩形中分别产生X/Y再点击；注意屏幕/设计坐标，不用随机偏移冒充均匀取点。 |
| 随机延时 (`randomSleep`) | 组合 | `Math.randInt`、`Task.sleep`、积木 `math.randint`、积木 `task.sleep` | 显式抽取毫秒再等待；轮询/超时等待仍保持精确。 |
| 连点 (`multiTap`) | 部分 | `Input.tap`、`Input.swipe`、`Input.pointerDown`、`Input.pointerMove`、`Input.pointerUp`、积木 `input.tap`、积木 `input.swipe`、积木 `input.pointerdown`、积木 `input.pointermove`、积木 `input.pointerup` | 点击/长按/滑动/拖动/连点可用；仅任务所属单指，不支持对方0–9指序、多指并行或跨调用任意挂指。 |
| 智能寻路 (`smartMove`) | 另项 | 无等价脚本入口 | 没有A*地图资源/避障/寻路与路径执行产品；一般拖动不能算路径规划。 |
| 寻路 (`findPath`) | 另项 | 无等价脚本入口 | 没有A*地图资源/避障/寻路与路径执行产品；一般拖动不能算路径规划。 |
| 行走 (`walkPath`) | 另项 | 无等价脚本入口 | 没有A*地图资源/避障/寻路与路径执行产品；一般拖动不能算路径规划。 |
| 坐标拖动 (`coordDrag`) | 部分 | `Math.distance`、`Math.angle`、`Math.joystickX`、`Math.joystickY`、`Input.pointerMove`、积木 `math.distance`、积木 `math.angle`、积木 `math.joystickx`、积木 `math.joysticky`、积木 `input.pointermove` | 基础几何和单指动作可组合；没有自动时长/半径夹限、多指与续推一体向导。 |
| 摇杆移动 (`coordMove`) | 待核 | 无等价脚本入口 | 页面只有名称(...)占位签名，无参数/返回约定，不能据此判断等价。 |
| 计算轮盘坐标 (`calcStickPoint`) | 组合 | `Math.joystickX`、`Math.joystickY`、积木 `math.joystickx`、积木 `math.joysticky` | 分别计算X/Y，不自动执行触摸。 |
| 点击坐标 (`tapXY`) | 组合 | `Input.tapScreen`、积木 `input.tap` | 使用明确声明的识别X/Y变量点击；本方不保存跨操作隐式最近命中。 |
| 地图坐标换算 (`mapCoordToScreen`) | 组合 | 积木 `variable.calculate`、积木 `input.tap` | 可按给定世界/小地图矩形比例计算，后者再显式点击；没有专用向导，也不是A*寻路。 |
| 地图寻路 (`mapPathfind`) | 组合 | 积木 `variable.calculate`、积木 `input.tap` | 可按给定世界/小地图矩形比例计算，后者再显式点击；没有专用向导，也不是A*寻路。 |

### 内存读写类

[对方该分类](https://jdwan.pages.dev/docs#cat-dc0a10)，12张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| 取进程ID (`getPid`) | 另项 | 无等价脚本入口 | 无跨进程内存接口；需要新增独立Root命令、权限和隔离设计。 |
| 取模块基址 (`getModuleBase`) | 另项 | 无等价脚本入口 | 无跨进程内存接口；需要新增独立Root命令、权限和隔离设计。 |
| 取模块列表 (`listModules`) | 另项 | 无等价脚本入口 | 无跨进程内存接口；需要新增独立Root命令、权限和隔离设计。 |
| 读内存 (`readMem`) | 另项 | 无等价脚本入口 | 无跨进程内存接口；需要新增独立Root命令、权限和隔离设计。 |
| 读内存字节 (`readMemBytes`) | 另项 | 无等价脚本入口 | 无跨进程内存接口；需要新增独立Root命令、权限和隔离设计。 |
| 读内存文本 (`readMemText`) | 另项 | 无等价脚本入口 | 无跨进程内存接口；需要新增独立Root命令、权限和隔离设计。 |
| 写内存 (`writeMem`) | 另项 | 无等价脚本入口 | 无跨进程内存接口；需要新增独立Root命令、权限和隔离设计。 |
| 写内存字节 (`writeMemBytes`) | 另项 | 无等价脚本入口 | 无跨进程内存接口；需要新增独立Root命令、权限和隔离设计。 |
| 写内存文本 (`writeMemText`) | 另项 | 无等价脚本入口 | 无跨进程内存接口；需要新增独立Root命令、权限和隔离设计。 |
| 内存搜索 (`searchMem`) | 另项 | 无等价脚本入口 | 无跨进程内存接口；需要新增独立Root命令、权限和隔离设计。 |
| 内存筛选 (`filterMem`) | 另项 | 无等价脚本入口 | 无跨进程内存接口；需要新增独立Root命令、权限和隔离设计。 |
| 内存搜索变动 (`searchMemChanged`) | 另项 | 无等价脚本入口 | 无跨进程内存接口；需要新增独立Root命令、权限和隔离设计。 |

### 抓包类

[对方该分类](https://jdwan.pages.dev/docs#cat-f407e2)，13张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| 开始抓包 (`startCapture`) | 另项 | 无等价脚本入口 | 无VPN/tcpdump/封包重发组件；与截图、日志不是同一能力，需独立立项。 |
| 停止抓包 (`stopCapture`) | 另项 | 无等价脚本入口 | 无VPN/tcpdump/封包重发组件；与截图、日志不是同一能力，需独立立项。 |
| 抓包状态 (`captureStatus`) | 另项 | 无等价脚本入口 | 无VPN/tcpdump/封包重发组件；与截图、日志不是同一能力，需独立立项。 |
| 清空抓包 (`clearCapture`) | 另项 | 无等价脚本入口 | 无VPN/tcpdump/封包重发组件；与截图、日志不是同一能力，需独立立项。 |
| 设置抓包目标 (`setCaptureTarget`) | 另项 | 无等价脚本入口 | 无VPN/tcpdump/封包重发组件；与截图、日志不是同一能力，需独立立项。 |
| 取抓包列表 (`getCaptureList`) | 另项 | 无等价脚本入口 | 无VPN/tcpdump/封包重发组件；与截图、日志不是同一能力，需独立立项。 |
| 取抓包详情 (`getCaptureDetail`) | 另项 | 无等价脚本入口 | 无VPN/tcpdump/封包重发组件；与截图、日志不是同一能力，需独立立项。 |
| 取封包列表 (`getPacketList`) | 另项 | 无等价脚本入口 | 无VPN/tcpdump/封包重发组件；与截图、日志不是同一能力，需独立立项。 |
| 取封包数据 (`getPacketData`) | 另项 | 无等价脚本入口 | 无VPN/tcpdump/封包重发组件；与截图、日志不是同一能力，需独立立项。 |
| 筛选抓包 (`filterCapture`) | 另项 | 无等价脚本入口 | 无VPN/tcpdump/封包重发组件；与截图、日志不是同一能力，需独立立项。 |
| 等待抓包 (`waitCapture`) | 另项 | 无等价脚本入口 | 无VPN/tcpdump/封包重发组件；与截图、日志不是同一能力，需独立立项。 |
| 重发封包 (`replayPacket`) | 另项 | 无等价脚本入口 | 无VPN/tcpdump/封包重发组件；与截图、日志不是同一能力，需独立立项。 |
| 导出抓包 (`exportCapture`) | 另项 | 无等价脚本入口 | 无VPN/tcpdump/封包重发组件；与截图、日志不是同一能力，需独立立项。 |

### 卡密授权类

[对方该分类](https://jdwan.pages.dev/docs#cat-df3856)，6张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| 卡密校验 (`cardCheck`) | 另项 | 无等价脚本入口 | 账号、在线授权和支付明确延期，不作为当前Studio必补项。 |
| 卡密激活 (`cardActivate`) | 另项 | 无等价脚本入口 | 账号、在线授权和支付明确延期，不作为当前Studio必补项。 |
| 卡密到期时间 (`cardExpire`) | 另项 | 无等价脚本入口 | 账号、在线授权和支付明确延期，不作为当前Studio必补项。 |
| 卡密剩余秒 (`cardSeconds`) | 另项 | 无等价脚本入口 | 账号、在线授权和支付明确延期，不作为当前Studio必补项。 |
| 卡密增加时间 (`cardAddTime`) | 另项 | 无等价脚本入口 | 账号、在线授权和支付明确延期，不作为当前Studio必补项。 |
| 卡密已存 (`cardSaved`) | 另项 | 无等价脚本入口 | 账号、在线授权和支付明确延期，不作为当前Studio必补项。 |

### 追踪类

[对方该分类](https://jdwan.pages.dev/docs#cat-d1c22e)，6张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| 开始追踪 (`startSendRecvHook`) | 另项 | 无等价脚本入口 | 无ptrace注入或socket Hook；不能用执行追踪日志冒充。 |
| 停止追踪 (`stopSendRecvHook`) | 另项 | 无等价脚本入口 | 无ptrace注入或socket Hook；不能用执行追踪日志冒充。 |
| 追踪状态 (`sendRecvHookStatus`) | 另项 | 无等价脚本入口 | 无ptrace注入或socket Hook；不能用执行追踪日志冒充。 |
| 取追踪列表 (`getSendRecvList`) | 另项 | 无等价脚本入口 | 无ptrace注入或socket Hook；不能用执行追踪日志冒充。 |
| 取追踪详情 (`getSendRecvDetail`) | 另项 | 无等价脚本入口 | 无ptrace注入或socket Hook；不能用执行追踪日志冒充。 |
| 清空追踪 (`clearSendRecvList`) | 另项 | 无等价脚本入口 | 无ptrace注入或socket Hook；不能用执行追踪日志冒充。 |

### 安全防护类

[对方该分类](https://jdwan.pages.dev/docs#cat-980d33)，3张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| 隐藏进程 (`hideProcess`) | 另项 | 无等价脚本入口 | 无进程伪装或反调试终止接口；不为凑数量引入。 |
| 开启防护 (`enableShield`) | 另项 | 无等价脚本入口 | 无进程伪装或反调试终止接口；不为凑数量引入。 |
| 防护状态 (`shieldStatus`) | 另项 | 无等价脚本入口 | 无进程伪装或反调试终止接口；不为凑数量引入。 |

### 游戏镜像

[对方该分类](https://jdwan.pages.dev/docs#cat-f84d9b)，16张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| 启动镜像 (`startMirror`) | 另项 | 无等价脚本入口 | 无虚拟显示及镜像会话后端；主屏截图/触摸不能冒充虚拟屏操作。 |
| 停止镜像 (`stopMirror`) | 另项 | 无等价脚本入口 | 无虚拟显示及镜像会话后端；主屏截图/触摸不能冒充虚拟屏操作。 |
| 镜像状态 (`mirrorStatus`) | 另项 | 无等价脚本入口 | 无虚拟显示及镜像会话后端；主屏截图/触摸不能冒充虚拟屏操作。 |
| 镜像自检 (`mirrorSelfTest`) | 另项 | 无等价脚本入口 | 无虚拟显示及镜像会话后端；主屏截图/触摸不能冒充虚拟屏操作。 |
| 镜像尺寸 (`mirrorSize`) | 另项 | 无等价脚本入口 | 无虚拟显示及镜像会话后端；主屏截图/触摸不能冒充虚拟屏操作。 |
| 镜像点击 (`mirrorTap`) | 另项 | 无等价脚本入口 | 无虚拟显示及镜像会话后端；主屏截图/触摸不能冒充虚拟屏操作。 |
| 镜像按住 (`mirrorLongPress`) | 另项 | 无等价脚本入口 | 无虚拟显示及镜像会话后端；主屏截图/触摸不能冒充虚拟屏操作。 |
| 镜像滑动 (`mirrorSwipe`) | 另项 | 无等价脚本入口 | 无虚拟显示及镜像会话后端；主屏截图/触摸不能冒充虚拟屏操作。 |
| 镜像拖动 (`mirrorDrag`) | 另项 | 无等价脚本入口 | 无虚拟显示及镜像会话后端；主屏截图/触摸不能冒充虚拟屏操作。 |
| 镜像按键 (`mirrorKey`) | 另项 | 无等价脚本入口 | 无虚拟显示及镜像会话后端；主屏截图/触摸不能冒充虚拟屏操作。 |
| 镜像返回 (`mirrorBack`) | 另项 | 无等价脚本入口 | 无虚拟显示及镜像会话后端；主屏截图/触摸不能冒充虚拟屏操作。 |
| 镜像截图 (`mirrorCapture`) | 另项 | 无等价脚本入口 | 无虚拟显示及镜像会话后端；主屏截图/触摸不能冒充虚拟屏操作。 |
| 镜像找图 (`mirrorFindImage`) | 另项 | 无等价脚本入口 | 无虚拟显示及镜像会话后端；主屏截图/触摸不能冒充虚拟屏操作。 |
| 镜像找色 (`mirrorFindColor`) | 另项 | 无等价脚本入口 | 无虚拟显示及镜像会话后端；主屏截图/触摸不能冒充虚拟屏操作。 |
| 镜像取色 (`mirrorGetColor`) | 另项 | 无等价脚本入口 | 无虚拟显示及镜像会话后端；主屏截图/触摸不能冒充虚拟屏操作。 |
| 镜像等到图片 (`mirrorWaitImage`) | 另项 | 无等价脚本入口 | 无虚拟显示及镜像会话后端；主屏截图/触摸不能冒充虚拟屏操作。 |

### OCR识字类

[对方该分类](https://jdwan.pages.dev/docs#cat-d45dda)，13张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| ai识图 (`aiRecognizeImage`) | 另项 | 无等价脚本入口 | AI网络调用、密钥与提供方配置尚未实现；不借本次对照接入云端或更换模型。 |
| ai推理 (`aiReason`) | 另项 | 无等价脚本入口 | AI网络调用、密钥与提供方配置尚未实现；不借本次对照接入云端或更换模型。 |
| 区域识字 (`ocr`) | 部分 | `Ocr.alphanumeric`、`Ocr.glyph`、积木 `ocr.alphanumeric`、积木 `ocr.glyph` | 仅单行字母数字模型及固定字库；不是通用中文/任意版面OCR，这符合当前产品决定。 |
| 区域颜色识字 (`ocrColorRegion`) | 部分 | `Screen.transform`、`Ocr.glyph`、`Ocr.alphanumeric`、积木 `screen.transform`、积木 `ocr.glyph`、积木 `ocr.alphanumeric` | 可滤色后送现有识别，但范围仍限固定字库/单行字母数字，不是通用颜色OCR。 |
| 字库识别 (`fontOcr`) | 部分 | `Ocr.loadDictionary`、`Ocr.glyph`、积木 `ocr.glyph` | 固定字库制作/识别已有；ASGLYPH格式，不支持对方默认聚合全部txt字库，未知字与错误协议也不同。 |
| 识别图片文字 (`ocrText`) | 部分 | `Screen.loadImage`、`Ocr.glyph`、`Ocr.alphanumeric`、积木 `screen.loadimage`、积木 `ocr.glyph`、积木 `ocr.alphanumeric` | 可识别声明图片帧，仍需明确范围/字库；没有通用图片文本检测。 |
| 找字 (`findText`) | 部分 | `Ocr.glyphDetails`、`Ocr.findText`、`Screen.recognitionValue`、积木 `ocr.glyphdetails`、积木 `ocr.findtext`、积木 `vision.readresult` | 同帧字库逐字结果可找连续文字并读取多位置；不支持通用OCR找字、跨行/未知字符猜测。 |
| 区域找字 (`findTextInRegion`) | 部分 | `Ocr.glyphDetails`、`Ocr.findText`、`Screen.recognitionValue`、积木 `ocr.glyphdetails`、积木 `ocr.findtext`、积木 `vision.readresult` | 同帧字库逐字结果可找连续文字并读取多位置；不支持通用OCR找字、跨行/未知字符猜测。 |
| 找字扩展 (`findTextEx`) | 部分 | `Ocr.glyphDetails`、`Ocr.findText`、`Screen.recognitionValue`、积木 `ocr.glyphdetails`、积木 `ocr.findtext`、积木 `vision.readresult` | 同帧字库逐字结果可找连续文字并读取多位置；不支持通用OCR找字、跨行/未知字符猜测。 |
| 设置识别引擎 (`setOcrEngine`) | 缺口 | 无等价脚本入口 | 我的页面能管理固定组件，但脚本没有引擎枚举/切换API；导入组件不等于切引擎。 |
| 取引擎列表 (`getOcrEngines`) | 缺口 | 无等价脚本入口 | 我的页面能管理固定组件，但脚本没有引擎枚举/切换API；导入组件不等于切引擎。 |
| 取识字耗时 (`ocrTiming`) | 部分 | `System.elapsedRealtimeMillis`、积木 `system.elapsed` | 可测调用总耗时，诊断日志也有阶段观测；没有脚本返回最近OCR四阶段耗时的接口。 |
| 识别坐标 (`recognizeCoord`) | 部分 | `Ocr.glyph`、`Text.split`、`Data.get`、积木 `ocr.glyph`、积木 `text.split`、积木 `data.get`、积木 `variable.calculate` | 已知数字格式可识别后解析；没有直接X/Y解析向导，不写隐式找到表，不猜格式。 |

### 文件类

[对方该分类](https://jdwan.pages.dev/docs#cat-563256)，22张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| 读文件 (`readFile`) | 部分 | `Files.read`、积木 `files.read` | 有界UTF-8、仅私有data；越界/失败报错，不是任意路径或失败返回空。 |
| 写文件 (`writeFile`) | 部分 | `Files.write`、`Files.writeLine`、`Files.append`、积木 `files.write`、积木 `files.writeline`、积木 `files.append` | 覆盖/首行/追加分别有操作；只有私有data、有界UTF-8，不提供旧模式统一入口。 |
| 追加文件 (`appendFile`) | 部分 | `Files.append`、积木 `files.append` | 原子追加已有，不存在可创建；限64KiB及项目data。 |
| 删文件 (`deleteFile`) | 部分 | `Files.remove`、积木 `files.remove` | 只删除普通私有数据文件并进入有界回收区，不删除源码/任意设备文件。 |
| 建目录 (`makeDir`) | 部分 | `Files.mkdir`、积木 `files.mkdir` | 私有data内建目录；不授予外部任意目录写权限。 |
| 删目录 (`deleteDir`) | 部分 | `Files.rmdir`、积木 `files.rmdir` | 只删空目录，不递归删除；不能按同名函数认定语义相同。 |
| 列目录 (`listDir`) | 部分 | `Files.list`、积木 `files.list` | 私有data内列举，结果格式不同，不读取设备任意目录。 |
| 文件存在 (`fileExists`) | 部分 | `Files.exists`、`Files.isDir`、积木 `files.exists`、积木 `files.isdir` | 文件和目录分开；不安全路径拒绝，不返回对方统一真假协议。 |
| 重命名 (`renameFile`) | 部分 | `Files.move`、积木 `files.move` | 同项目普通文件原子移动/改名且不覆盖；不是任意路径或目录改名。 |
| 复制文件 (`copyFile`) | 部分 | `Files.copy`、积木 `files.copy` | 有界UTF-8文件复制，不覆盖；不是通用二进制/目录复制。 |
| 移动文件 (`moveFile`) | 部分 | `Files.move`、积木 `files.move` | 同项目普通文件原子移动/改名且不覆盖；不是任意路径或目录改名。 |
| 文件大小 (`fileSize`) | 部分 | `Files.getSize`、积木 `files.getsize` | 读取私有数据文件真实字节数，路径边界不同。 |
| 取文本行数 (`textLineCount`) | 已有 | `Files.lineCount`、积木 `files.linecount` | 严格UTF-8、LF/CRLF、末尾换行不另算空行；空文件0。 |
| 取行文本 (`getLineText`) | 部分 | `Files.readLine`、`Files.deleteLine`、积木 `files.readline`、积木 `files.deleteline` | 1起行号已有，但本方越界明确报错，不是空文本/false；删除原子保存。 |
| 删除行文本 (`deleteLineText`) | 部分 | `Files.readLine`、`Files.deleteLine`、积木 `files.readline`、积木 `files.deleteline` | 1起行号已有，但本方越界明确报错，不是空文本/false；删除原子保存。 |
| ZIP打包 (`zipPack`) | 部分 | `Files.zip`、`Files.unzip`、积木 `files.zip`、积木 `files.unzip` | 私有data、条目/大小/展开比预算，解压目标必须不存在；不覆盖/合并任意目录。 |
| ZIP解压 (`zipUnpack`) | 部分 | `Files.zip`、`Files.unzip`、积木 `files.zip`、积木 `files.unzip` | 私有data、条目/大小/展开比预算，解压目标必须不存在；不覆盖/合并任意目录。 |
| 取文件时间 (`getFileTime`) | 缺口 | 无等价脚本入口 | 没有脚本文件修改时间及通用路径工具；UI目录信息或手工文本拆分不是专用函数。 |
| 路径拼接 (`joinPath`) | 缺口 | 无等价脚本入口 | 没有脚本文件修改时间及通用路径工具；UI目录信息或手工文本拆分不是专用函数。 |
| 取文件名 (`getFileName`) | 缺口 | 无等价脚本入口 | 没有脚本文件修改时间及通用路径工具；UI目录信息或手工文本拆分不是专用函数。 |
| 取扩展名 (`getFileExt`) | 部分 | `Files.getExtension`、积木 `files.getextension` | 要求已存在私有普通文件，返回无点扩展名；不等于任意路径文本解析。 |
| 取父目录 (`getParentPath`) | 缺口 | 无等价脚本入口 | 没有脚本文件修改时间及通用路径工具；UI目录信息或手工文本拆分不是专用函数。 |

### 文本类

[对方该分类](https://jdwan.pages.dev/docs#cat-446b2c)，40张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| JSON编码 (`jsonEncode`) | 缺口 | 无等价脚本入口 | 没有通用值/动态字符的JSON编码器；Data.set接收已编码valueJson，不能冒充字符串转义编码。 |
| JSON解码 (`jsonDecode`) | 部分 | `Data.valid`、`Data.getJson`、`Data.get`、`Data.text`、积木 `data.valid`、积木 `data.getjson`、积木 `data.get`、积木 `data.text` | JSON解析/读取已有，项目使用JSON字符容器，不返回可随意修改的Lua table。 |
| XML解析 (`xmlParse`) | 缺口 | 无等价脚本入口 | 没有XML/INI解析及序列化契约；Files.write只负责保存，不能冒充编码器。 |
| 读配置 (`iniRead`) | 缺口 | 无等价脚本入口 | 没有XML/INI解析及序列化契约；Files.write只负责保存，不能冒充编码器。 |
| 写配置 (`iniWrite`) | 缺口 | 无等价脚本入口 | 没有XML/INI解析及序列化契约；Files.write只负责保存，不能冒充编码器。 |
| 分割文本 (`splitText`) | 已有 | `Text.split`、积木 `text.split` | Unicode分割，输出JSON字符数组，最多1024项；需适配原生table。 |
| 合并文本 (`joinText`) | 已有 | `Text.join`、积木 `text.join` | 合并JSON字符数组，严格字符元素，最多64KiB。 |
| 去空白 (`trimText`) | 已有 | `Text.trim`、积木 `text.trim` | 去首尾Unicode空白，保留中间内容。 |
| 文字过滤 (`filterText`) | 缺口 | 无等价脚本入口 | 没有按汉字/数字/字母/符号类别清洗的专用函数或向导。 |
| 替换文本 (`replaceText`) | 已有 | `Text.replace`、积木 `text.replace` | 全替换已有，空查找串拒绝，结果需显式接收。 |
| 正则匹配 (`regexMatch`) | 部分 | `Text.regexMatch`、`Text.regexExtract`、积木 `text.regexmatch`、积木 `text.regexextract` | 有限自动机正则，无环视/反向引用；返回1/0，语法和查找/匹配约定需适配。 |
| 转小写 (`lowerText`) | 已有 | `Text.lower`、`Text.upper`、积木 `text.lower`、积木 `text.upper` | Unicode大小写转换已有，输出受64KiB约束。 |
| 转大写 (`upperText`) | 已有 | `Text.lower`、`Text.upper`、积木 `text.lower`、积木 `text.upper` | Unicode大小写转换已有，输出受64KiB约束。 |
| 格式化 (`formatText`) | 缺口 | 无等价脚本入口 | Lua底层有string.format，但可视化受限表达式没有格式化入口；缺小数位及补零格式控制。 |
| 文本长度 (`textLength`) | 已有 | `Text.length`、积木 `text.length` | Unicode字符数，不是UTF-8字节数。 |
| URL编码 (`urlEncode`) | 缺口 | 无等价脚本入口 | 没有对应脚本契约/可视化操作；不以底层标准库或可手写算法宣称已接通。 |
| URL解码 (`urlDecode`) | 缺口 | 无等价脚本入口 | 没有对应脚本契约/可视化操作；不以底层标准库或可手写算法宣称已接通。 |
| 十六进制编码 (`hexEncode`) | 缺口 | 无等价脚本入口 | 没有对应脚本契约/可视化操作；不以底层标准库或可手写算法宣称已接通。 |
| 文本包含 (`containsText`) | 已有 | `Text.contains`、积木 `text.contains` | 包含判断已有，返回整数1/0；空needle返回1。 |
| 取文本片段 (`midText`) | 已有 | `Text.sub`、积木 `text.sub` | 1起Unicode字符截取，超出末尾截短；非法起点明确拒绝。 |
| 正则替换 (`regexReplace`) | 缺口 | 无等价脚本入口 | 没有对应脚本契约/可视化操作；不以底层标准库或可手写算法宣称已接通。 |
| 文本统计 (`textCount`) | 已有 | `Text.count`、积木 `text.count` | 非重叠出现次数，空目标拒绝。 |
| 反转文本 (`reverseText`) | 缺口 | 无等价脚本入口 | 没有对应脚本契约/可视化操作；不以底层标准库或可手写算法宣称已接通。 |
| 首字母大写 (`capitalizeText`) | 组合 | `Text.left`、`Text.upper`、`Text.sub`、积木 `text.left`、积木 `text.upper`、积木 `text.sub`、积木 `variable.calculate` | 首字符转大写再拼接剩余文本；没有独立快捷函数。 |
| 填充文本 (`padText`) | 组合 | `Text.length`、`Text.repeatText`、积木 `text.length`、积木 `text.repeat`、积木 `variable.calculate` | 可按长度差重复填充再拼接；没有统一左右填充/截断参数的入口。 |
| 取小数点后 (`formatNumber`) | 缺口 | 无等价脚本入口 | Lua底层有string.format，但可视化受限表达式没有格式化入口；缺小数位及补零格式控制。 |
| 取字符前字符 (`subBefore`) | 部分 | `Text.before`、`Text.after`、`Text.between`、积木 `text.before`、积木 `text.after`、积木 `text.between` | 只直接支持首个匹配；对方第N处参数没有专用实现。 |
| 取字符后字符 (`subAfter`) | 部分 | `Text.before`、`Text.after`、`Text.between`、积木 `text.before`、积木 `text.after`、积木 `text.between` | 只直接支持首个匹配；对方第N处参数没有专用实现。 |
| 取字符间字符 (`subBetween`) | 部分 | `Text.before`、`Text.after`、`Text.between`、积木 `text.before`、积木 `text.after`、积木 `text.between` | 只直接支持首个匹配；对方第N处参数没有专用实现。 |
| 数组个数 (`arrCount`) | 已有 | `Data.length`、积木 `data.length` | JSON数组/对象项数已有；不读取任意Lua table的混合键语义。 |
| 数组加数据 (`arrAdd`) | 部分 | `Data.append`、积木 `data.append` | 一次追加JSON标量并返回新JSON，非原地变参追加/返回长度；需接length。 |
| 数组删数据 (`arrRemove`) | 部分 | `Data.remove`、积木 `data.remove` | 删除1起索引或键并返回新JSON，非原地修改后返回个数。 |
| 数组提取数据 (`arrGet`) | 部分 | `Data.get`、`Data.getJson`、`Data.text`、积木 `data.get`、积木 `data.getjson`、积木 `data.text` | 1起索引、标量与子结构分开读取、字符需解码；返回协议不同。 |
| 数组排序 (`arrSort`) | 缺口 | 无等价脚本入口 | 没有对应脚本契约/可视化操作；不以底层标准库或可手写算法宣称已接通。 |
| 字符转整数 (`toNum`) | 部分 | 积木 `variable.calculate` | int()/float()可转换已声明变量；非法文本报错，不静默返回0；int向零截断。 |
| 整数转字符 (`toStr`) | 已有 | 积木 `variable.calculate` | text()/float()显式转换已有，不提供这些同名Lua别名。 |
| 字符转浮点数 (`toFloat`) | 部分 | 积木 `variable.calculate` | int()/float()可转换已声明变量；非法文本报错，不静默返回0；int向零截断。 |
| 浮点数转字符 (`floatToStr`) | 已有 | 积木 `variable.calculate` | text()/float()显式转换已有，不提供这些同名Lua别名。 |
| 浮点数转整数 (`floatToInt`) | 部分 | `Math.round`、积木 `math.round`、积木 `variable.calculate` | 半值远离零的round可用；int()是向零截断，不能用int冒充四舍五入。 |
| 整数转浮点数 (`intToFloat`) | 已有 | 积木 `variable.calculate` | text()/float()显式转换已有，不提供这些同名Lua别名。 |

### 流程控制类

[对方该分类](https://jdwan.pages.dev/docs#cat-d11d80)，6张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| 计时开始 (`startTimer`) | 组合 | `System.elapsedRealtimeMillis`、积木 `system.elapsed`、积木 `variable.calculate` | 显式保存开始时刻并相减，用单调时钟，不复制对方墙钟全局秒表。 |
| 取耗时 (`getElapsed`) | 组合 | `System.elapsedRealtimeMillis`、积木 `system.elapsed`、积木 `variable.calculate` | 显式保存开始时刻并相减，用单调时钟，不复制对方墙钟全局秒表。 |
| 延时到 (`sleepUntil`) | 组合 | `System.getTimeMillis`、`Task.sleep`、积木 `system.time`、积木 `variable.calculate`、积木 `control.if`、积木 `task.sleep` | 计算目标墙钟剩余毫秒，正数才等待；没有独立函数，期间改系统时钟的行为需约定。 |
| 随机坐标 (`randomPoint`) | 组合 | `Math.randInt`、积木 `math.randint` | 两个明确范围独立取整数，不生成隐式坐标表；上下界按本方包含端点。 |
| 重试执行 (`safeExec`) | 缺口 | 积木 `flow.call.branch` | 成功/失败分支只处理显式插件返回状态，不捕获异常；无可视化异常重试，取消不应被吞掉。 |
| 停止运行 (`stopScript`) | 已有 | `Task.stop`、积木 `task.stop` | 优先停止整次运行及子任务，随后执行清理。 |

### 数学运算类

[对方该分类](https://jdwan.pages.dev/docs#cat-097cce)，20张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| 取余数 (`mathMod`) | 部分 | 积木 `variable.calculate` | %支持数值余数，另有整数irem；对方未明确负数规则，不能直接判为完全兼容。 |
| 取绝对值 (`mathAbs`) | 组合 | 积木 `control.if`、积木 `variable.calculate` | 条件小于0后取负可表达；没有独立abs函数，最小64位负数溢出仍拒绝。 |
| 向上取整 (`mathCeil`) | 缺口 | 无等价脚本入口 | 底层Lua math库存在，但Studio表达式白名单及函数库未开放这些运算；不能算可视化已覆盖。 |
| 向下取整 (`mathFloor`) | 缺口 | 无等价脚本入口 | 底层Lua math库存在，但Studio表达式白名单及函数库未开放这些运算；不能算可视化已覆盖。 |
| 四舍五入 (`mathRound`) | 部分 | `Math.round`、积木 `math.round` | 仅整数位四舍五入，半值远离零；无可变小数位参数。 |
| 取最大数 (`mathMax`) | 部分 | 积木 `control.if`、积木 `variable.calculate` | 两值可通过判断选择；没有变参min/max计算入口，批量值还需手动循环。 |
| 取最小数 (`mathMin`) | 部分 | 积木 `control.if`、积木 `variable.calculate` | 两值可通过判断选择；没有变参min/max计算入口，批量值还需手动循环。 |
| 取平方根 (`mathSqrt`) | 缺口 | 无等价脚本入口 | 底层Lua math库存在，但Studio表达式白名单及函数库未开放这些运算；不能算可视化已覆盖。 |
| 计算幂 (`mathPow`) | 缺口 | 无等价脚本入口 | 底层Lua math库存在，但Studio表达式白名单及函数库未开放这些运算；不能算可视化已覆盖。 |
| 自然对数 (`mathLn`) | 缺口 | 无等价脚本入口 | 底层Lua math库存在，但Studio表达式白名单及函数库未开放这些运算；不能算可视化已覆盖。 |
| 常用对数 (`mathLog10`) | 缺口 | 无等价脚本入口 | 底层Lua math库存在，但Studio表达式白名单及函数库未开放这些运算；不能算可视化已覆盖。 |
| 计算指数 (`mathExp`) | 缺口 | 无等价脚本入口 | 底层Lua math库存在，但Studio表达式白名单及函数库未开放这些运算；不能算可视化已覆盖。 |
| 正弦 (`mathSin`) | 缺口 | 无等价脚本入口 | 底层Lua math库存在，但Studio表达式白名单及函数库未开放这些运算；不能算可视化已覆盖。 |
| 余弦 (`mathCos`) | 缺口 | 无等价脚本入口 | 底层Lua math库存在，但Studio表达式白名单及函数库未开放这些运算；不能算可视化已覆盖。 |
| 正切 (`mathTan`) | 缺口 | 无等价脚本入口 | 底层Lua math库存在，但Studio表达式白名单及函数库未开放这些运算；不能算可视化已覆盖。 |
| 反正弦 (`mathAsin`) | 缺口 | 无等价脚本入口 | 底层Lua math库存在，但Studio表达式白名单及函数库未开放这些运算；不能算可视化已覆盖。 |
| 反余弦 (`mathAcos`) | 缺口 | 无等价脚本入口 | 底层Lua math库存在，但Studio表达式白名单及函数库未开放这些运算；不能算可视化已覆盖。 |
| 反正切 (`mathAtan`) | 缺口 | 无等价脚本入口 | 底层Lua math库存在，但Studio表达式白名单及函数库未开放这些运算；不能算可视化已覆盖。 |
| 角度转弧度 (`mathRad`) | 组合 | 积木 `variable.calculate` | 乘/除明确的π常数可表达，不执行触摸；没有独立函数。 |
| 弧度转角度 (`mathDeg`) | 组合 | 积木 `variable.calculate` | 乘/除明确的π常数可表达，不执行触摸；没有独立函数。 |

### 扩展类

[对方该分类](https://jdwan.pages.dev/docs#cat-4c4cc0)，67张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| 调试输出 (`print`) | 已有 | `Log.write`、积木 `task.log` | 脚本日志已有，和诊断/识图统计分开；并非同名print别名兼容。 |
| 调试变量 (`debugVar`) | 缺口 | 无等价脚本入口 | 内建运行变量观察窗已存在；但脚本没有把任意内容推送到专用变量标签的接口，不把UI能力算API。 |
| 等待 (`sleep`) | 已有 | `Task.sleep`、积木 `task.sleep` | 可取消挂起，不阻塞Lua调度线程。 |
| 提示 (`toast`) | 已有 | `Prompt.toast`、积木 `task.prompt` | 使用项目统一样式的短时浮窗，不保证等同Android系统Toast样式。 |
| 写日志 (`log`) | 已有 | `Log.write`、积木 `task.log` | 脚本日志已有，和诊断/识图统计分开；并非同名print别名兼容。 |
| 输入对话框 (`dialogInput`) | 组合 | `UI.showDialogAndWait`、`UI.getValue`、积木 `ui.dialog`、积木 `ui.get` | 设计输入页面后显示并读取，可实现输入对话；没有标题/提示两参的快捷输入框。 |
| 确认对话框 (`dialogConfirm`) | 已有 | `UI.confirm`、积木 `ui.confirm` | 有明确confirm/cancel/dismiss/timeout，需映射对方真/假，不能把关闭当确认。 |
| POST (`POST`) | 缺口 | 无等价脚本入口 | 无脚本HTTP客户端或打开URL契约；当前组件校验/文档访问不算脚本联网能力。 |
| POST请求 (`POST`) | 缺口 | 无等价脚本入口 | 无脚本HTTP客户端或打开URL契约；当前组件校验/文档访问不算脚本联网能力。 |
| 网络获取 (`httpGet`) | 缺口 | 无等价脚本入口 | 无脚本HTTP客户端或打开URL契约；当前组件校验/文档访问不算脚本联网能力。 |
| 网络提交 (`httpPost`) | 缺口 | 无等价脚本入口 | 无脚本HTTP客户端或打开URL契约；当前组件校验/文档访问不算脚本联网能力。 |
| 网络请求 (`httpRequest`) | 缺口 | 无等价脚本入口 | 无脚本HTTP客户端或打开URL契约；当前组件校验/文档访问不算脚本联网能力。 |
| 读网络文件 (`readNetFile`) | 另项 | 无等价脚本入口 | 账号隔离网盘属于已延期云功能，不作为当前本地Studio必补项。 |
| 写网络文件 (`writeNetFile`) | 另项 | 无等价脚本入口 | 账号隔离网盘属于已延期云功能，不作为当前本地Studio必补项。 |
| 打开网页 (`openUrl`) | 缺口 | 无等价脚本入口 | 无脚本HTTP客户端或打开URL契约；当前组件校验/文档访问不算脚本联网能力。 |
| 读网页内容 (`fetchUrl`) | 缺口 | 无等价脚本入口 | 无脚本HTTP客户端或打开URL契约；当前组件校验/文档访问不算脚本联网能力。 |
| 下载文件 (`downloadFile`) | 另项 | 无等价脚本入口 | 下载器暂缓；脚本通用下载若要做，也需单独的网络/落盘/取消/配额设计。 |
| 取时间 (`getTime`) | 组合 | `System.getTimeMillis`、`Date.format`、积木 `system.time`、积木 `date.format` | 墙钟毫秒加固定时区格式化；无自动当前本地格式函数。 |
| 时间戳 (`timestamp`) | 已有 | `System.getTimeMillis`、积木 `system.time` | Unix墙钟毫秒，不用于超时或性能统计。 |
| 取屏幕尺寸 (`getScreenSize`) | 已有 | `System.getScreenSize`、积木 `system.screensize` | 读取真实显示像素，不把设计尺寸冒充设备尺寸。 |
| 震动 (`vibrate`) | 缺口 | 无等价脚本入口 | 无脚本震动、系统通知或TTS接口；播放音频资源不等于语音合成。 |
| 执行shell (`shellExec`) | 另项 | 无等价脚本入口 | 架构明确禁止Lua取得任意Shell/Root命令；不做按名字照搬。 |
| 取设备码 (`getDeviceId`) | 另项 | 无等价脚本入口 | 无唯一标识或定位脚本接口；需单独用途、权限和隐私决定，不自动采集。 |
| 播放声音 (`playSound`) | 部分 | `Audio.play`、积木 `audio.play` | 声明音频或旧私有data音频，20MiB/60秒/单播放器；不支持任意路径或URL。 |
| 状态栏通知 (`notify`) | 缺口 | 无等价脚本入口 | 无脚本震动、系统通知或TTS接口；播放音频资源不等于语音合成。 |
| 剪贴板读 (`readClipboard`) | 已有 | `Clipboard.getText`、`Clipboard.setText`、积木 `clipboard.gettext`、积木 `clipboard.settext` | 纯文本≤4096字节、后台限制明确失败；不读取URI/图片。 |
| 剪贴板写 (`writeClipboard`) | 已有 | `Clipboard.getText`、`Clipboard.setText`、积木 `clipboard.gettext`、积木 `clipboard.settext` | 纯文本≤4096字节、后台限制明确失败；不读取URI/图片。 |
| 随机整数 (`randomInt`) | 已有 | `Math.randInt`、积木 `math.randint` | 包含端点、有限整数范围，非密码学随机。 |
| 随机小数 (`randomFloat`) | 缺口 | 无等价脚本入口 | 无独立随机浮点契约/积木；用随机整数除比例只是离散固定精度替代。 |
| 随机文本 (`randomString`) | 已有 | `Math.randStr`、积木 `math.randstr` | ASCII预设字符集、1–1024字符；字符模式需显式选择。 |
| MD5加密 (`md5`) | 缺口 | 无等价脚本入口 | 无字符摘要/密码学/文本Base64接口；Files.toBase64/fromBase64是文件编码，不能冒充文本编码。 |
| SHA1加密 (`sha1`) | 缺口 | 无等价脚本入口 | 无字符摘要/密码学/文本Base64接口；Files.toBase64/fromBase64是文件编码，不能冒充文本编码。 |
| Base64编码 (`base64Encode`) | 缺口 | 无等价脚本入口 | 无字符摘要/密码学/文本Base64接口；Files.toBase64/fromBase64是文件编码，不能冒充文本编码。 |
| Base64解码 (`base64Decode`) | 缺口 | 无等价脚本入口 | 无字符摘要/密码学/文本Base64接口；Files.toBase64/fromBase64是文件编码，不能冒充文本编码。 |
| 时间戳转时间 (`formatTimestamp`) | 部分 | `Date.format`、积木 `date.format` | 仅date/time/datetime及显式固定时区；不接受任意格式模板。 |
| 取表长度 (`tableCount`) | 已有 | `Data.length`、积木 `data.length` | JSON数组/对象项数已有；不读取任意Lua table的混合键语义。 |
| 取电池电量 (`getBattery`) | 缺口 | 无等价脚本入口 | 没有对应脚本只读设备信息契约；我的/权限页面和诊断日志能显示信息不等于脚本可读取。 |
| 取网络类型 (`getNetworkType`) | 缺口 | 无等价脚本入口 | 没有对应脚本只读设备信息契约；我的/权限页面和诊断日志能显示信息不等于脚本可读取。 |
| 取设备型号 (`getDeviceModel`) | 缺口 | 无等价脚本入口 | 没有对应脚本只读设备信息契约；我的/权限页面和诊断日志能显示信息不等于脚本可读取。 |
| 取系统版本 (`getAndroidVersion`) | 缺口 | 无等价脚本入口 | 没有对应脚本只读设备信息契约；我的/权限页面和诊断日志能显示信息不等于脚本可读取。 |
| 取屏幕亮度 (`getScreenBrightness`) | 缺口 | 无等价脚本入口 | 没有对应脚本只读设备信息契约；我的/权限页面和诊断日志能显示信息不等于脚本可读取。 |
| 设置屏幕亮度 (`setScreenBrightness`) | 另项 | 无等价脚本入口 | 系统设置写入、设备管理器及通知访问需独立权限/影响边界；不以Root已就绪代替授权。 |
| 存储空间 (`getStorageSpace`) | 缺口 | 无等价脚本入口 | 没有对应脚本只读设备信息契约；我的/权限页面和诊断日志能显示信息不等于脚本可读取。 |
| 取屏幕密度 (`getScreenDensity`) | 缺口 | 无等价脚本入口 | 没有对应脚本只读设备信息契约；我的/权限页面和诊断日志能显示信息不等于脚本可读取。 |
| 震动模式 (`vibratePattern`) | 缺口 | 无等价脚本入口 | 无脚本震动、系统通知或TTS接口；播放音频资源不等于语音合成。 |
| 语音播报 (`speak`) | 缺口 | 无等价脚本入口 | 无脚本震动、系统通知或TTS接口；播放音频资源不等于语音合成。 |
| 停止播报 (`stopSpeak`) | 缺口 | 无等价脚本入口 | 无脚本震动、系统通知或TTS接口；播放音频资源不等于语音合成。 |
| AES加密 (`aesEncrypt`) | 缺口 | 无等价脚本入口 | 无字符摘要/密码学/文本Base64接口；Files.toBase64/fromBase64是文件编码，不能冒充文本编码。 |
| AES解密 (`aesDecrypt`) | 缺口 | 无等价脚本入口 | 无字符摘要/密码学/文本Base64接口；Files.toBase64/fromBase64是文件编码，不能冒充文本编码。 |
| 下拉通知栏 (`pullNotification`) | 另项 | 无等价脚本入口 | 系统设置写入、设备管理器及通知访问需独立权限/影响边界；不以Root已就绪代替授权。 |
| 关闭通知栏 (`closeNotification`) | 另项 | 无等价脚本入口 | 系统设置写入、设备管理器及通知访问需独立权限/影响边界；不以Root已就绪代替授权。 |
| 分屏切换 (`toggleSplitScreen`) | 另项 | 无等价脚本入口 | 系统设置写入、设备管理器及通知访问需独立权限/影响边界；不以Root已就绪代替授权。 |
| 剪贴板图片保存 (`readClipboardImage`) | 另项 | 无等价脚本入口 | 当前剪贴板仅纯文本；图片/URI读取需另定来源校验、授权和资源预算。 |
| 锁屏 (`lockScreen`) | 另项 | 无等价脚本入口 | 系统设置写入、设备管理器及通知访问需独立权限/影响边界；不以Root已就绪代替授权。 |
| 激活设备管理器 (`openDeviceAdmin`) | 另项 | 无等价脚本入口 | 系统设置写入、设备管理器及通知访问需独立权限/影响边界；不以Root已就绪代替授权。 |
| 读取通知 (`getNotification`) | 另项 | 无等价脚本入口 | 系统设置写入、设备管理器及通知访问需独立权限/影响边界；不以Root已就绪代替授权。 |
| 清空通知 (`clearNotifications`) | 另项 | 无等价脚本入口 | 系统设置写入、设备管理器及通知访问需独立权限/影响边界；不以Root已就绪代替授权。 |
| 取定位 (`getLocation`) | 另项 | 无等价脚本入口 | 无唯一标识或定位脚本接口；需单独用途、权限和隐私决定，不自动采集。 |
| 运行权限检测 (`checkPermissions`) | 缺口 | 无等价脚本入口 | App权限/环境复查已存在；脚本没有统一查询API。不得把页面显示或Root连接算作脚本权限接口。 |
| 启动线程 (`runAsync`) | 部分 | `Task.spawn`、`Task.cancel`、积木 `task.spawn`、积木 `task.cancel` | 已有同VM协程子任务及按所有权取消；不是独立VM线程/任意代码字符串，局部隔离语义不同。 |
| 停止线程 (`stopThread`) | 部分 | `Task.spawn`、`Task.cancel`、积木 `task.spawn`、积木 `task.cancel` | 已有同VM协程子任务及按所有权取消；不是独立VM线程/任意代码字符串，局部隔离语义不同。 |
| 取内存信息 (`getMemoryInfo`) | 缺口 | 无等价脚本入口 | 没有对应脚本只读设备信息契约；我的/权限页面和诊断日志能显示信息不等于脚本可读取。 |
| 取息屏时间 (`getScreenTimeout`) | 缺口 | 无等价脚本入口 | 没有对应脚本只读设备信息契约；我的/权限页面和诊断日志能显示信息不等于脚本可读取。 |
| 取星期 (`getWeekday`) | 缺口 | 无等价脚本入口 | Date.format没有星期格式选项，缺专用读取/日期解析入口。 |
| 取两个日期差 (`dateDiff`) | 部分 | `Date.daysBetween`、`Date.addDays`、积木 `date.daysbetween`、积木 `date.adddays` | 本方输入毫秒时间戳并按固定86400000毫秒；对方输入日期文本、按日历日，含时区/DST差异。 |
| 日期增加天数 (`dateAddDays`) | 部分 | `Date.daysBetween`、`Date.addDays`、积木 `date.daysbetween`、积木 `date.adddays` | 本方输入毫秒时间戳并按固定86400000毫秒；对方输入日期文本、按日历日，含时区/DST差异。 |
| 取屏幕方向 (`getScreenOrientation`) | 组合 | `System.getScreenSize`、积木 `system.screensize`、积木 `control.if` | 比较当前真实宽高可得横/竖屏；不提供0/90/180/270旋转角或独立方向API。 |

### 无障碍类

[对方该分类](https://jdwan.pages.dev/docs#cat-c60339)，9张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| 控件存在 (`nodeExists`) | 另项 | 无等价脚本入口 | 首版Root-only；自有脚本UI控件操作不等于读取其他App节点。 |
| 点击控件 (`clickNode`) | 另项 | 无等价脚本入口 | 首版Root-only；自有脚本UI控件操作不等于读取其他App节点。 |
| 取控件文本 (`getNodeText`) | 另项 | 无等价脚本入口 | 首版Root-only；自有脚本UI控件操作不等于读取其他App节点。 |
| 取控件坐标 (`getNodeBounds`) | 另项 | 无等价脚本入口 | 首版Root-only；自有脚本UI控件操作不等于读取其他App节点。 |
| 控件输入 (`setNodeText`) | 另项 | 无等价脚本入口 | 首版Root-only；自有脚本UI控件操作不等于读取其他App节点。 |
| 等待控件 (`waitForNode`) | 另项 | 无等价脚本入口 | 首版Root-only；自有脚本UI控件操作不等于读取其他App节点。 |
| 等待控件消失 (`waitForNodeGone`) | 另项 | 无等价脚本入口 | 首版Root-only；自有脚本UI控件操作不等于读取其他App节点。 |
| 节点树导出 (`dumpNodeTree`) | 另项 | 无等价脚本入口 | 首版Root-only；自有脚本UI控件操作不等于读取其他App节点。 |
| 控件树导出 (`dumpNodeTree`) | 另项 | 无等价脚本入口 | 首版Root-only；自有脚本UI控件操作不等于读取其他App节点。 |

### 封装计算类

[对方该分类](https://jdwan.pages.dev/docs#cat-2bdb1c)，2张卡片。

| 对方指令 | 状态 | 智构API或积木 | 关键边界 |
|---|---|---|---|
| 计算角度 (`calcAngle`) | 部分 | `Math.angle`、积木 `math.angle`、积木 `variable.calculate` | 本方[-180,180]且相同点报错；需明确归一化到0–360以及屏幕Y轴方向。 |
| 计算直线距离 (`calcDist`) | 已有 | `Math.distance`、积木 `math.distance` | 两点欧氏距离已有。 |
