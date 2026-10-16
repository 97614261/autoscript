# 老版函数库与智构能力核对 · 2026-10-11

2026-10-15总账更新：[当前Studio首版计划及历史销项](studio-first-version-master-plan.md)、[177种积木逐项矩阵](studio-block-coverage.md)。下方2026-10-11“主要缺口”是历史快照，不再作为当前待开发清单；入口/保存/编译/执行边界归并到总账，实际回归与设备验收另列，不由API数量推算百分比。

2026-10-14视觉后续更新：当前schema静态检查为160个API、177种积木；视觉行按本次代码更新，其余历史缺口行需按实际源码重新确认，不能直接相加计算当前完成率。

实施更新（截至2026-10-12）：基础工具、文本/JSON/日期/私有文件、运行期界面、图像扩展、重启、脚本录制回放及音频基础已接通；后续又补齐项目音频、文件元数据/Base64/ZIP、平台剪贴板/App、受控文本粘贴及命名浮窗绘图，当前契约为159个API、174种积木。使用见[基础工具](../guides/script-utilities.md)与[运行期能力](../guides/runtime-ui-frame-control.md)，分类见[函数目录](function-library-inventory-2026-10-12.md)，剩余工作见[执行计划](function-library-development-plan.md)。下方逐项表保留2026-10-11审查时快照，不把历史“缺少”当成新增代码后的当前结论，也不把契约数量视为五批完成率。

## 核对口径

这是代码静态审查，不是设备运行验收，也不是旧项目可直接导入的兼容保证。本批没有修改产品代码、安装或运行用户脚本。

- 一键玩：完整静态目录216个函数，逐条核对下表。菜单名称和Java参数记录不证明Native实现的完整语义。
- 易编精灵：从本地函数注册/运行说明源码mn0、vy、pi、ha0、w90抽出266个不同的点号API名逐条归类。这是已检查的静态条目集合，**不是易编全部API数量**：动态拼接、中文任务函数及反编译缺失方法不能仅用该抽取计数宣称完整覆盖。中文启动/停止线程和定时器另外按现有task/timer结构化任务对照。
- 智构：审查时schema目录65个运行API、73种可视化积木；另外检查变量计算白名单、UI属性状态、编辑器入口及编译运行分支。后续实施更新见文首。
- 不采用历史迁移CSV的IMPLEMENTED/PLANNED标记作为当前事实；该CSV有“字符串输入已实现”等过期状态，当前实际契约没有Input.text。
- 一个新API可替代多个旧函数；有API不代表可视化入口已齐，有手动编辑器功能不代表脚本运行API已齐。不从函数数量计算产品完成度百分比。

状态：“已覆盖”表示对应基础用途有真实链路，不表示旧参数/错误协议逐字节兼容；“部分覆盖”表示缺模式、结果、范围或方便入口；“入口缺口”表示真实运行API存在但没有独立积木；“新版替代”表示采用了不同的明确产品语义；“范围外/延期/需决策”不得混计为首版待办。

## 主要缺口与建议顺序

| 批次 | 能力 | 当前证据与缺口 |
|---|---|---|
| 1 基础工具 | 文本输入、剪贴板、启动/查询应用、时间/随机值、屏幕尺寸与距离积木 | 文本输入缺宿主协议；随机偏移不等于随机变量；System和Math部分API缺可视化入口。设备键按下/弹起与多指另行评估，不扩大现有单指语义。 |
| 2 数据/文件 | 行操作、追加、复制/移动/存在性、私有目录管理、持久KV、JSON子结构/类型/存在性、文本拆分和有界正则 | Files仅私有data有界UTF-8；Data.get只读标量，不返回对象/数组；编辑器建目录/备份不算这些脚本函数。先做项目沙箱，不开放任意存储。 |
| 3 界面 | 列表逐项操作/选择索引、占位文字与数值范围/步长、页面参数配置保存恢复、背景图片、主窗/浮球位置 | 当前UI.command读写基础值/选项集合及设计像素样式；缺上述运行属性，设计器可设不等于脚本可改。确认/布局/关闭对话框已接通。 |
| 4 图像/OCR | 运行期帧保存、裁剪积木、图片变换、字库逐字位置与找指定文字、批量多点/色块 | OpenCV只有灰度路径；像素透明模板不是OpenCV透明模板算法；英文数字单行不是PP中文多行OCR。扩大ONNX和算法范围须用户确认。 |
| 5 运行控制 | 脚本主动停止/重启、运行期日志/提示开关、脚本录制入口、音频、轻量命名浮窗/绘图 | 运行球已有用户暂停继续停止；Root录制已有工具入口，不代表脚本能调用；关闭布局不能冒充停止脚本。 |
| 6 可选扩展 | HTTP/下载、QR、缩放/边缘/形状/ORB、通用OCR | HTTP与旧云对象/FTP/Redis分开设计；目前均不能宣称已实现。此批不是自动获准开发范围。 |

首版优先建议：1 → 2 → 3 → 4的基础项 → 5的停止/重启与运行开关。网络、通用OCR、多指、轻量浮窗/绘图及系统写操作另行决定。按现有用户决定，模板库、账号云服务、无障碍/节点、任意Shell/Java/Native入口不补。

## 特别避免的误判

- 编辑器能截图保存/创建图片文件夹，不代表有脚本SaveScreen/Files.mkdir/图像输出API。
- 可视化源码是受限可逆描述语言，不允许靠粘贴任意Lua标准库调用绕过积木能力和编译器。
- Screen.captureSeries、Screen.crop、Math.distance、System.getScreenSize、System.elapsedRealtimeMillis存在真实API，但无对应独立积木；截图系列还需要适合可视化使用的结果/释放配置。
- Ocr.loadDictionary/releaseDictionary、帧和模板加载的部分底层操作由高级参数与编译器管理，不要求机械复制成每项独立按钮。
- 并发Task采用同VM结构化协程，旧线程可能独立VM，取消/变量共享/失败传播不可宣称相同。
- 不自动恢复旧全局阈值、范围、找图失败计时等隐式状态；新逐积木配置是有意设计，若要运行期策略API需另外限定作用域。

## 一键玩216项逐条清单

### 输入控制（9项）

| 旧函数 | 用途 | 当前状态 | 对照说明 |
|---|---|---|---|
| TouchDown | 触摸屏按下 | 部分覆盖 | 单指按下/弹起/点击/长按/滑动已接通；旧触点ID、多指与参数语义不能视为相同。 |
| TouchUp | 触摸屏弹起 | 部分覆盖 | 单指按下/弹起/点击/长按/滑动已接通；旧触点ID、多指与参数语义不能视为相同。 |
| TouchClick | 触摸屏单击 | 部分覆盖 | 单指按下/弹起/点击/长按/滑动已接通；旧触点ID、多指与参数语义不能视为相同。 |
| KeyClick | 按下设备键 | 已覆盖 | input.keyevent / Input.keyEvent，白名单Android键码。 |
| Input | 输入字符串 | 缺少 | 没有脚本字符串/中文输入契约、可视化积木和Root输入链。 |
| DurationDrag | 持续拖动 | 部分覆盖 | 单指按下/弹起/点击/长按/滑动已接通；旧触点ID、多指与参数语义不能视为相同。 |
| RandomClick | 设置全局按键随机点击 | 新版替代 | 项目/本积木随机策略已实现；不是运行中修改旧全局开关的同名API。 |
| WzaClick | 无障碍触摸点击 | 范围外 | 首版Root-only，不做无障碍及节点自动化。 |
| WzaDrag | 无障碍触摸拖动 | 范围外 | 首版Root-only，不做无障碍及节点自动化。 |

### 屏幕视觉（30项）

2026-10-14补记：以下视觉行按当前原生能力更新，非老版格式/算法等价声明。识图后续的专用结果读取、等待组合和逐字预览见[开发记录](../verification/studio-recognition-followup-2026-10-14.md)；其他分类的10月11日历史状态不应当作当前百分比。

| 旧函数 | 用途 | 当前状态 | 对照说明 |
|---|---|---|---|
| GetColor | 获取指定坐标点像素 | 已覆盖 | 对应取色、像素找图、找色及Legacy专用契约/积木；不要混同不同算法的分数。 |
| GetR | 从像素中提取R值 | 缺少 | RGB通道提取没有专用函数/积木；不能因可手算而算函数库已覆盖。 |
| GetG | 从像素中提取G值 | 缺少 | RGB通道提取没有专用函数/积木；不能因可手算而算函数库已覆盖。 |
| GetB | 从像素中提取B值 | 缺少 | RGB通道提取没有专用函数/积木；不能因可手算而算函数库已覆盖。 |
| GetRGBColor | 把RGB值转成一个像素 | 已覆盖 | 对应取色、像素找图、找色及Legacy专用契约/积木；不要混同不同算法的分数。 |
| SaveScreen | 保存屏幕 | 新版覆盖 | 手动截图保存及screen.capture/crop/savepng可组合，脚本PNG限私有data，不等价任意路径写入。 |
| GetScreenWidth | 获得屏幕宽度 | 入口缺口 | System.getScreenSize存在，当前73种积木没有独立读取屏幕尺寸积木。 |
| GetScreenHeight | 获得屏幕高度 | 入口缺口 | System.getScreenSize存在，当前73种积木没有独立读取屏幕尺寸积木。 |
| ZhaoTu | 找图 | 已覆盖 | 对应取色、像素找图、找色及Legacy专用契约/积木；不要混同不同算法的分数。 |
| ZhaoSe | 找色 | 已覆盖 | 对应取色、像素找图、找色及Legacy专用契约/积木；不要混同不同算法的分数。 |
| TuPianXuanZhuan | 图片旋转 | 新版覆盖 | screen.transform支持固定90/180/270度、镜像、灰度、二值化和最近邻缩放；不声称任意角度旋转。 |
| GetGameWidth | 获得游戏屏宽 | 部分覆盖 | 项目设计分辨率已存在；没有专用运行读取设计宽高积木。 |
| GetGameHeight | 获得游戏屏高 | 部分覆盖 | 项目设计分辨率已存在；没有专用运行读取设计宽高积木。 |
| ZhaoSeEX | 扩展找色 | 部分覆盖 | 区域/多点找色已有；旧扩展参数串每种语义并未全量证明。 |
| CacheScreen | 缓存屏幕 | 新版替代 | 显式Screen.cache/release与任务帧租约；不是旧全局缓存状态。 |
| UnCacheScreen | 解除缓存屏幕 | 新版替代 | 显式Screen.cache/release与任务帧租约；不是旧全局缓存状态。 |
| SetApproxi | 设置全局近似度 | 新版替代 | 找图积木逐次配置阈值/ROI；不提供会影响其他任务的旧全局隐式状态。 |
| SetRandomClick | 设置全局随机点击 | 新版替代 | 项目/本积木随机策略已实现；不是运行中修改旧全局开关的同名API。 |
| GetNotFindTime | 连续找不到图的时间 | 组合替代 | 等待向导展开单调计时/循环/判断，覆盖像素/灰度、颜色和文字；从实际未命中开始计时、命中重置，不创建旧全局隐式计时器。 |
| AddFindScope | 扩展全局寻图范围 | 新版替代 | 找图积木逐次配置阈值/ROI；不提供会影响其他任务的旧全局隐式状态。 |
| DownApproxi | 临时降低全局近似度 | 新版替代 | 找图积木逐次配置阈值/ROI；不提供会影响其他任务的旧全局隐式状态。 |
| SmartFindFigure | 智能寻图 | 待核实 | 旧菜单与参数证据存在，Native内部语义未证明；不能用普通找图冒充。 |
| DuoDianZhaoSe | 多点找色 | 已覆盖 | 对应取色、像素找图、找色及Legacy专用契约/积木；不要混同不同算法的分数。 |
| DuoDianBiSe | 多点比色 | 已覆盖 | 对应取色、像素找图、找色及Legacy专用契约/积木；不要混同不同算法的分数。 |
| GetRectColorNum | 获取区域颜色个数 | 已覆盖 | 对应取色、像素找图、找色及Legacy专用契约/积木；不要混同不同算法的分数。 |
| setDict | 字库初始化 | 部分覆盖 | ASGLYPH字库加载/识别及原生积木已接通；不保证旧字库格式直接兼容。 |
| ocrEx | 字库OCR识别 | 部分覆盖 | ASGLYPH字库加载/识别及原生积木已接通；不保证旧字库格式直接兼容。 |
| ocrjEx | 获取所有文字坐标 | 原生替代 | ASGLYPH逐字JSON和专用vision.readresult支持文字、矩形、分数与识别状态，字库测试显示真实框；不兼容未知旧Native格式。 |
| findStrEx | 字库文字查找 | 原生替代 | ocr.findtext查询同一行连续已识别字形，返回全部匹配矩形；空白/跨行查询拒绝，不等价通用文字检测。 |
| findStrAll | 获取字库文字坐标 | 原生替代 | 已有findtext集合可逐项读取数量、文字、边界及中心；受64KiB/2048项预算，不生成推测坐标。 |

### 文件存储（31项）

| 旧函数 | 用途 | 当前状态 | 对照说明 |
|---|---|---|---|
| OpenFile | 打开文件 | 部分覆盖 | Files.read/write/remove只允许项目私有data有界UTF-8文件；不等价任意路径/旧读写模式。 |
| WriteFile | 写入文件 | 部分覆盖 | Files.read/write/remove只允许项目私有data有界UTF-8文件；不等价任意路径/旧读写模式。 |
| GetFileNum | 获得文件个数 | 部分覆盖 | Files.list可列私有数据文件；没有旧迭代游标/单独计数函数。 |
| GetNextFile | 获得下个文件 | 部分覆盖 | Files.list可列私有数据文件；没有旧迭代游标/单独计数函数。 |
| GetLine | 获得一行文本 | 缺少 | 按行读取/插入/删除/行数尚无专用契约和积木。 |
| InsertLine | 插入一行文本 | 缺少 | 按行读取/插入/删除/行数尚无专用契约和积木。 |
| DelLine | 删除一行文本 | 缺少 | 按行读取/插入/删除/行数尚无专用契约和积木。 |
| GetLineNum | 获得文本总行数 | 缺少 | 按行读取/插入/删除/行数尚无专用契约和积木。 |
| Obj_SetObj | 保存对象值 | 部分覆盖 | Data对象/集合与Files可组合，但缺持久KV、筛选、原子累加及等价索引语义。 |
| Obj_GetObj | 获取对象值 | 部分覆盖 | Data对象/集合与Files可组合，但缺持久KV、筛选、原子累加及等价索引语义。 |
| Obj_DelObj | 删除对象 | 部分覆盖 | Data对象/集合与Files可组合，但缺持久KV、筛选、原子累加及等价索引语义。 |
| Obj_DelAllObj | 删除所有对象 | 部分覆盖 | Data对象/集合与Files可组合，但缺持久KV、筛选、原子累加及等价索引语义。 |
| Obj_IsObj | 判断对象是否存在 | 部分覆盖 | Data对象/集合与Files可组合，但缺持久KV、筛选、原子累加及等价索引语义。 |
| Obj_GetObjCount | 获取对象总个数 | 部分覆盖 | Data对象/集合与Files可组合，但缺持久KV、筛选、原子累加及等价索引语义。 |
| Obj_GetObjIndex | 根据索引获取对象名 | 部分覆盖 | Data对象/集合与Files可组合，但缺持久KV、筛选、原子累加及等价索引语义。 |
| Obj_Screening | 对象筛选 | 部分覆盖 | Data对象/集合与Files可组合，但缺持久KV、筛选、原子累加及等价索引语义。 |
| Obj_VarAddUp | 对象值累加 | 部分覆盖 | Data对象/集合与Files可组合，但缺持久KV、筛选、原子累加及等价索引语义。 |
| DelFile | 删除文件 | 部分覆盖 | Files.read/write/remove只允许项目私有data有界UTF-8文件；不等价任意路径/旧读写模式。 |
| CopyFile | 文件复制 | 缺少 | 脚本API未实现；项目备份、编辑器建目录/改名不是脚本文件能力。 |
| CopyFolder | 文件夹复制 | 缺少 | 脚本API未实现；项目备份、编辑器建目录/改名不是脚本文件能力。 |
| DelFolder | 文件夹删除 | 缺少 | 脚本API未实现；项目备份、编辑器建目录/改名不是脚本文件能力。 |
| CreateFolder | 文件夹创建 | 缺少 | 脚本API未实现；项目备份、编辑器建目录/改名不是脚本文件能力。 |
| RenameFile | 文件重命名 | 缺少 | 脚本API未实现；项目备份、编辑器建目录/改名不是脚本文件能力。 |
| RenameFolder | 文件夹重命名 | 缺少 | 脚本API未实现；项目备份、编辑器建目录/改名不是脚本文件能力。 |
| FileExist | 检测文件是否存在 | 缺少 | 脚本API未实现；项目备份、编辑器建目录/改名不是脚本文件能力。 |
| FolderExist | 检测文件夹是否存在 | 缺少 | 脚本API未实现；项目备份、编辑器建目录/改名不是脚本文件能力。 |
| FileRoot | 修改文件权限 | 范围外 | 不开放任意路径chmod或越过项目沙箱。 |
| FolderRoot | 修改文件夹权限 | 范围外 | 不开放任意路径chmod或越过项目沙箱。 |
| Zip | zip压缩 | 缺少 | 脚本API未实现；项目备份、编辑器建目录/改名不是脚本文件能力。 |
| UnZip | zip解压 | 缺少 | 脚本API未实现；项目备份、编辑器建目录/改名不是脚本文件能力。 |
| BmpToPng | bmp图片转png图片 | 缺少 | 脚本API未实现；项目备份、编辑器建目录/改名不是脚本文件能力。 |

### 网络服务（22项）

| 旧函数 | 用途 | 当前状态 | 对照说明 |
|---|---|---|---|
| SaveObject | 保存网络对象 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| GetObject | 获取网络对象值 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| GetObjectNum | 获取网络对象总个数 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| GetObjectIndex | 根据索引获得网络对象名 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| DelObject | 删除网络对象 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| DelAllObject | 删除所有网络对象 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| FTP_PutFile | 上传文件 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| FTP_GetFile | 下载文件 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| FTP_WriteFtpFile | 写入网络文件 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| FTP_ReadFtpFile | 读取网络文件 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| GetNetTime | 获得网络时间 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| OpenUrl | 打开指定url | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| LoginGuaGua | 网络帐号登录 | 范围外 | 旧账号、会员、代答及专属PC服务，不计首版缺口。 |
| ChangePassword | 修改网络帐号密码 | 范围外 | 旧账号、会员、代答及专属PC服务，不计首版缺口。 |
| FTP_WriteObject | 设置网络文件对象 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| FTP_ReadObject | 获取网络文件对象 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| FTP_DelObject | 删除网络文件对象 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| ObjectValAdd | 网络对象值累加 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| ReadUrlText | 读取url内容 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| UrlDown | url文件下载 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| MyFTP_Down | ftp文件下载 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |
| MyFtp_Upload | ftp文件上传 | 未规划通用能力 | 没有脚本网络契约；HTTP/下载/打开URL可另行设计，旧私有对象服务/FTP不直接复刻。 |

### 系统与扩展（75项）

| 旧函数 | 用途 | 当前状态 | 对照说明 |
|---|---|---|---|
| GetMac | 获取设备的MAC信息 | 范围外 | 不复制设备MAC身份采集；未来设备信息只提供有用途且授权明确的字段。 |
| GetRandom | 生成随机数值 | 缺少 | 缺随机数/字符串、日期时间与日期运算可视化函数；点击随机偏移不等于随机值函数。 |
| RandomLetter | 生成随机字符串 | 缺少 | 缺随机数/字符串、日期时间与日期运算可视化函数；点击随机偏移不等于随机值函数。 |
| GetTickCount | 获得开机时间 | 入口缺口 | System.elapsedRealtimeMillis存在（当前运行使用的单调时间）；无独立可视化积木，也不宣称开机时钟完全等价。 |
| GetCurDate | 获得当前日期 | 缺少 | 缺随机数/字符串、日期时间与日期运算可视化函数；点击随机偏移不等于随机值函数。 |
| CountDate | 计算两个日期差 | 缺少 | 缺随机数/字符串、日期时间与日期运算可视化函数；点击随机偏移不等于随机值函数。 |
| DaiDaChaXun | 代答查询题分 | 范围外 | 旧账号、会员、代答及专属PC服务，不计首版缺口。 |
| DaiDaRun | 运行代答 | 范围外 | 旧账号、会员、代答及专属PC服务，不计首版缺口。 |
| DaiDaErr | 代答返回错误 | 范围外 | 旧账号、会员、代答及专属PC服务，不计首版缺口。 |
| RebootSystem | 重启系统 | 需决策 | 脚本重启设备/安装卸载属于高影响系统写操作，尚无能力声明及确认策略。 |
| StartPack | 启动程序 | 缺少 | 脚本启动/停止App、已安装检查、剪贴板、屏幕状态、音频、内存及版本查询未开放。 |
| ClosePack | 关闭程序 | 缺少 | 脚本启动/停止App、已安装检查、剪贴板、屏幕状态、音频、内存及版本查询未开放。 |
| LuaRun | 运行Lua文件 | 范围外 | 不允许任意Lua文件执行、Shell、Java反射或Native扩展入口。 |
| MessageBox | 用消息框提示用户 | 部分覆盖 | 短时/持续提示及新增确认对话框已接通；旧返回协议、样式参数不宣称完全等价。 |
| InstallApk | 安装apk | 需决策 | 脚本重启设备/安装卸载属于高影响系统写操作，尚无能力声明及确认策略。 |
| UninstallApk | 卸载apk | 需决策 | 脚本重启设备/安装卸载属于高影响系统写操作，尚无能力声明及确认策略。 |
| GetAvailMemory | 可用内存大小 | 缺少 | 脚本启动/停止App、已安装检查、剪贴板、屏幕状态、音频、内存及版本查询未开放。 |
| GetTotalMemory | 内存总大小 | 缺少 | 脚本启动/停止App、已安装检查、剪贴板、屏幕状态、音频、内存及版本查询未开放。 |
| GetCurrentMemory | 当前应用占用内存 | 缺少 | 脚本启动/停止App、已安装检查、剪贴板、屏幕状态、音频、内存及版本查询未开放。 |
| SetClipboard | 设置剪贴板 | 缺少 | 脚本启动/停止App、已安装检查、剪贴板、屏幕状态、音频、内存及版本查询未开放。 |
| GetClipboard | 获取剪贴板 | 缺少 | 脚本启动/停止App、已安装检查、剪贴板、屏幕状态、音频、内存及版本查询未开放。 |
| IsScreenOn | 检测屏幕是否点亮 | 缺少 | 脚本启动/停止App、已安装检查、剪贴板、屏幕状态、音频、内存及版本查询未开放。 |
| PlayMusic | 播放音乐 | 缺少 | 脚本启动/停止App、已安装检查、剪贴板、屏幕状态、音频、内存及版本查询未开放。 |
| SwitchScript | 切换脚本 | 范围外 | 跨项目运行/双开管理/模拟定位不在当前首版范围。 |
| CheckPackage | 检验包名 | 缺少 | 脚本启动/停止App、已安装检查、剪贴板、屏幕状态、音频、内存及版本查询未开放。 |
| LoadHelp | 加载教程 | 新版替代 | 我的→使用指南为App入口，不是运行时脚本函数。 |
| OpenRunLog | 开启运行日志 | 新版替代 | 日志设置、提示样式、运行延迟与随机等待已提供；没有所有旧运行期全局修改API。 |
| CloseRunLog | 关闭运行日志 | 新版替代 | 日志设置、提示样式、运行延迟与随机等待已提供；没有所有旧运行期全局修改API。 |
| RunShellCommand | 运行shell命令 | 范围外 | 不允许任意Lua文件执行、Shell、Java反射或Native扩展入口。 |
| GetGuaGuaID | 获取登录的一键玩ID | 范围外 | 旧账号、会员、代答及专属PC服务，不计首版缺口。 |
| DateAdd | 日期增加天数 | 缺少 | 缺随机数/字符串、日期时间与日期运算可视化函数；点击随机偏移不等于随机值函数。 |
| GetGuaGuaUserName | 获取登录的一键玩用户名 | 范围外 | 旧账号、会员、代答及专属PC服务，不计首版缺口。 |
| StartThread | 启动一个线程 | 新版替代 | task.spawn/cancel、timer.every/cancel已接通；同VM结构化任务，不等价旧独立VM/任意线程ID。 |
| StopThread | 停止一个线程 | 新版替代 | task.spawn/cancel、timer.every/cancel已接通；同VM结构化任务，不等价旧独立VM/任意线程ID。 |
| StartTimerEx | 启动定时器 | 新版替代 | task.spawn/cancel、timer.every/cancel已接通；同VM结构化任务，不等价旧独立VM/任意线程ID。 |
| StopTimer | 停止一个定时器 | 新版替代 | task.spawn/cancel、timer.every/cancel已接通；同VM结构化任务，不等价旧独立VM/任意线程ID。 |
| GetGuaGuaUserVipEnd | 获取登录用户到期日期 | 范围外 | 旧账号、会员、代答及专属PC服务，不计首版缺口。 |
| StopRunScript | 停止运行脚本 | 缺少 | UI停止已可用；缺脚本主动停止整个运行、清理后重启入口的专用积木。 |
| AgainRunScript | 重新运行脚本 | 缺少 | UI停止已可用；缺脚本主动停止整个运行、清理后重启入口的专用积木。 |
| StartRecordTouch | 监控手指操作 | 部分覆盖 | 按键工具真实Root录制已接；缺脚本函数调用录制开始/停止与结果读取。 |
| StopRecordTouch | 停止监控手指 | 部分覆盖 | 按键工具真实Root录制已接；缺脚本函数调用录制开始/停止与结果读取。 |
| GetAppVersion | 获取一键玩版本号 | 缺少 | 脚本启动/停止App、已安装检查、剪贴板、屏幕状态、音频、内存及版本查询未开放。 |
| SetTipWindow | 设置提示窗 | 新版替代 | 日志设置、提示样式、运行延迟与随机等待已提供；没有所有旧运行期全局修改API。 |
| AdapterWindowed | 适配应用窗口化 | 部分覆盖 | 有设计映射和小窗适配；不能视为识别任意目标App窗口的旧函数。 |
| CheckDOApp | 判断双开应用中是否安装APP | 范围外 | 跨项目运行/双开管理/模拟定位不在当前首版范围。 |
| StartDOApp | 启动双开应用中的APP | 范围外 | 跨项目运行/双开管理/模拟定位不在当前首版范围。 |
| CloseDOApp | 关闭双开应用中的APP | 范围外 | 跨项目运行/双开管理/模拟定位不在当前首版范围。 |
| InstallDOApp | 双开应用中安装APP | 范围外 | 跨项目运行/双开管理/模拟定位不在当前首版范围。 |
| UnInstallDOApp | 双开应用中卸载APP | 范围外 | 跨项目运行/双开管理/模拟定位不在当前首版范围。 |
| OcrTesseract | OCR文字识别 | 延期扩展 | 当前ONNX限字母数字单行、另有字库；缺通用中文检测识别/框结果，不复制旧多引擎及通用AI。 |
| OcrEx | OCR扩展识别 | 延期扩展 | 当前ONNX限字母数字单行、另有字库；缺通用中文检测识别/框结果，不复制旧多引擎及通用AI。 |
| OcrXunTu | OCR与寻图结合 | 延期扩展 | 当前ONNX限字母数字单行、另有字库；缺通用中文检测识别/框结果，不复制旧多引擎及通用AI。 |
| StringThan | 字符比对 | 部分覆盖 | 变量计算/条件字符比较已有；旧专用比对模式需核实，不宣称同名协议等价。 |
| LandingRun | 登录运行脚本 | 范围外 | 旧账号、会员、代答及专属PC服务，不计首版缺口。 |
| RunJavaClass | 运行Java函数 | 范围外 | 不允许任意Lua文件执行、Shell、Java反射或Native扩展入口。 |
| RunCppClass | 运行C函数 | 范围外 | 不允许任意Lua文件执行、Shell、Java反射或Native扩展入口。 |
| FreeMemory | 清理内存 | 范围外 | 资源由生命周期/租约释放；不复刻强制清理系统内存。 |
| NodeFind | 节点查找 | 范围外 | 首版Root-only，不做无障碍及节点自动化。 |
| SimGps | 模拟定位 | 范围外 | 跨项目运行/双开管理/模拟定位不在当前首版范围。 |
| RandomMinSleep | 全局空循环随机改变 | 新版替代 | 日志设置、提示样式、运行延迟与随机等待已提供；没有所有旧运行期全局修改API。 |
| OpenAccessible | 开启无障碍 | 范围外 | 首版Root-only，不做无障碍及节点自动化。 |
| NodeFindEx | 节点查找扩展 | 范围外 | 首版Root-only，不做无障碍及节点自动化。 |
| NodeFastFind | 节点快速查找 | 范围外 | 首版Root-only，不做无障碍及节点自动化。 |
| LineSleep | 全局运行延迟 | 新版替代 | 日志设置、提示样式、运行延迟与随机等待已提供；没有所有旧运行期全局修改API。 |
| SetPluginInParam | 设置插件调用参数 | 已覆盖 | flow.argument.* / flow.return.*类型化插件调用参数链。 |
| GetPluginInParam | 获取插件调用参数 | 已覆盖 | flow.argument.* / flow.return.*类型化插件调用参数链。 |
| SetPluginOutParam | 设置插件返回参数 | 已覆盖 | flow.argument.* / flow.return.*类型化插件调用参数链。 |
| GetPluginOutParam | 获取插件返回参数 | 已覆盖 | flow.argument.* / flow.return.*类型化插件调用参数链。 |
| OcrTesseract2 | OCR文字识别2 | 延期扩展 | 当前ONNX限字母数字单行、另有字库；缺通用中文检测识别/框结果，不复制旧多引擎及通用AI。 |
| OcrEx2 | OCR扩展识别2 | 延期扩展 | 当前ONNX限字母数字单行、另有字库；缺通用中文检测识别/框结果，不复制旧多引擎及通用AI。 |
| AIIdentify | AI识别 | 延期扩展 | 当前ONNX限字母数字单行、另有字库；缺通用中文检测识别/框结果，不复制旧多引擎及通用AI。 |
| NoConfusionImage | 不混淆指定图片 | 范围外 | 旧混淆豁免开关不适用于当前冻结/签名打包模型。 |
| NoConfusionPlugin | 不混淆指定插件 | 范围外 | 旧混淆豁免开关不适用于当前冻结/签名打包模型。 |
| InitPcPlugin | 初始化PC一键玩插件 | 范围外 | 旧账号、会员、代答及专属PC服务，不计首版缺口。 |
| CallPcPlugin | 调用PC一键玩插件 | 范围外 | 旧账号、会员、代答及专属PC服务，不计首版缺口。 |

### 动态界面（49项）

| 旧函数 | 用途 | 当前状态 | 对照说明 |
|---|---|---|---|
| GetBackImage | 获得窗体背景 | 部分覆盖 | 背景颜色可设置；运行时背景图片读写不同于背景色，尚缺。 |
| SetBackImage | 设置窗体背景 | 部分覆盖 | 背景颜色可设置；运行时背景图片读写不同于背景色，尚缺。 |
| IsWindowVisible | 获取可见状态 | 部分覆盖 | 控件可见/启用读写和主窗show/hide已有；主窗状态/禁用查询无完整等价操作。 |
| ShowWindow | 设置可见状态 | 部分覆盖 | 控件可见/启用读写和主窗show/hide已有；主窗状态/禁用查询无完整等价操作。 |
| IsWindowDisable | 获取禁用状态 | 部分覆盖 | 控件可见/启用读写和主窗show/hide已有；主窗状态/禁用查询无完整等价操作。 |
| DisableWindow | 设置禁用状态 | 部分覆盖 | 控件可见/启用读写和主窗show/hide已有；主窗状态/禁用查询无完整等价操作。 |
| GetWindowRect | 获取窗体大小 | 部分覆盖 | 控件设计像素x/y/width/height已能读写；主窗口屏幕位置、组合rect未提供等价接口。 |
| SetWindowPos | 设置位置和大小 | 部分覆盖 | 控件设计像素x/y/width/height已能读写；主窗口屏幕位置、组合rect未提供等价接口。 |
| SwitchDlg | 切换窗体 | 已覆盖 | UI.command page/show/hide以及新布局对话框；跨项目/任意布局路径不开放。 |
| GetWindowText | 获得按钮文本 | 已覆盖 | 控件value/text/visible/enabled/fontPx/textColor白名单读写；旧命名由统一界面操作替代。 |
| SetWindowText | 设置按钮文本 | 已覆盖 | 控件value/text/visible/enabled/fontPx/textColor白名单读写；旧命名由统一界面操作替代。 |
| GetFontImage | 获得字体色 | 已覆盖 | 控件value/text/visible/enabled/fontPx/textColor白名单读写；旧命名由统一界面操作替代。 |
| SetFontImage | 设置字体色 | 已覆盖 | 控件value/text/visible/enabled/fontPx/textColor白名单读写；旧命名由统一界面操作替代。 |
| GetFontSize | 获得字体大小 | 已覆盖 | 控件value/text/visible/enabled/fontPx/textColor白名单读写；旧命名由统一界面操作替代。 |
| SetFontSize | 设置字体大小 | 已覆盖 | 控件value/text/visible/enabled/fontPx/textColor白名单读写；旧命名由统一界面操作替代。 |
| GetRadioSelID | 获取当前选择的ID | 部分覆盖 | 控件当前值、全部items可读写；缺选中索引/多选索引及逐项增删改的专用操作。 |
| GetRadioSelIDText | 获取选择ID的文本 | 部分覆盖 | 控件当前值、全部items可读写；缺选中索引/多选索引及逐项增删改的专用操作。 |
| SetRadioSelID | 设置ID为选择状态 | 部分覆盖 | 控件当前值、全部items可读写；缺选中索引/多选索引及逐项增删改的专用操作。 |
| GetCheck | 获取选择状态 | 已覆盖 | 控件value/text/visible/enabled/fontPx/textColor白名单读写；旧命名由统一界面操作替代。 |
| SetCheck | 设置选择状态 | 已覆盖 | 控件value/text/visible/enabled/fontPx/textColor白名单读写；旧命名由统一界面操作替代。 |
| GetCount | 获取总行数 | 部分覆盖 | 控件当前值、全部items可读写；缺选中索引/多选索引及逐项增删改的专用操作。 |
| GetCurSel | 获取选择行 | 部分覆盖 | 控件当前值、全部items可读写；缺选中索引/多选索引及逐项增删改的专用操作。 |
| SetCurSel | 设置选择行 | 部分覆盖 | 控件当前值、全部items可读写；缺选中索引/多选索引及逐项增删改的专用操作。 |
| GetItemText | 获取指定行文本 | 部分覆盖 | 控件当前值、全部items可读写；缺选中索引/多选索引及逐项增删改的专用操作。 |
| SetItemText | 设置指定行文本 | 部分覆盖 | 控件当前值、全部items可读写；缺选中索引/多选索引及逐项增删改的专用操作。 |
| DeleteItem | 删除一行 | 部分覆盖 | 控件当前值、全部items可读写；缺选中索引/多选索引及逐项增删改的专用操作。 |
| DeleteAllItem | 删除全部内容 | 部分覆盖 | 控件当前值、全部items可读写；缺选中索引/多选索引及逐项增删改的专用操作。 |
| InsertItem | 插入一行文本 | 部分覆盖 | 控件当前值、全部items可读写；缺选中索引/多选索引及逐项增删改的专用操作。 |
| SyncGroupControl | 组框里所有控件同步 | 缺少 | 容器已存在，但没有组内批量同步/修改的脚本操作。 |
| CallJsFunction | 调用JS函数 | 范围外 | 没有WebView控件/JS任意执行，非首版必需。 |
| LoadHtml | 加载Html | 范围外 | 没有WebView控件/JS任意执行，非首版必需。 |
| ReceiveTip | 接收运行提示 | 部分覆盖 | 提示、事件、结构化日志/运行变量检查已有；不是导出执行源码/全量变量的脚本函数。 |
| OutRunPluginName | 导出运行插件名 | 部分覆盖 | 提示、事件、结构化日志/运行变量检查已有；不是导出执行源码/全量变量的脚本函数。 |
| OutRunPluginItem | 导出运行插件项 | 部分覆盖 | 提示、事件、结构化日志/运行变量检查已有；不是导出执行源码/全量变量的脚本函数。 |
| OutRunVariable | 导出运行变量 | 部分覆盖 | 提示、事件、结构化日志/运行变量检查已有；不是导出执行源码/全量变量的脚本函数。 |
| GetEventID | 获得事件ID | 部分覆盖 | 提示、事件、结构化日志/运行变量检查已有；不是导出执行源码/全量变量的脚本函数。 |
| ShowMainInterface | 激活显示主界面 | 已覆盖 | UI.command page/show/hide以及新布局对话框；跨项目/任意布局路径不开放。 |
| HideMainInterface | 隐藏主界面 | 已覆盖 | UI.command page/show/hide以及新布局对话框；跨项目/任意布局路径不开放。 |
| MoveGuaGuaFloatingWindow | 移动悬浮球 | 缺少 | 球可人工拖动；脚本设置位置API缺少。 |
| PopupImage | 弹出一个图片 | 缺少 | 布局框可展示图片，但没有命名图片浮窗、事件、透明度和坐标查询独立API。 |
| PopupImageEvent | 设置弹出图片事件 | 缺少 | 布局框可展示图片，但没有命名图片浮窗、事件、透明度和坐标查询独立API。 |
| PopupImageBack | 设置弹出图片背景 | 缺少 | 布局框可展示图片，但没有命名图片浮窗、事件、透明度和坐标查询独立API。 |
| PopupImageTitle | 设置弹出图片显示文字 | 缺少 | 布局框可展示图片，但没有命名图片浮窗、事件、透明度和坐标查询独立API。 |
| PopupImageFontSize | 设置弹出图片文字大小 | 缺少 | 布局框可展示图片，但没有命名图片浮窗、事件、透明度和坐标查询独立API。 |
| PopupImageFontColor | 设置弹出图片文字颜色 | 缺少 | 布局框可展示图片，但没有命名图片浮窗、事件、透明度和坐标查询独立API。 |
| PopupImageTrans | 设置弹出图片透明度 | 缺少 | 布局框可展示图片，但没有命名图片浮窗、事件、透明度和坐标查询独立API。 |
| PopupImageClose | 关闭弹出图片 | 缺少 | 布局框可展示图片，但没有命名图片浮窗、事件、透明度和坐标查询独立API。 |
| PopupImageGetX | 获取弹出图片X坐标 | 缺少 | 布局框可展示图片，但没有命名图片浮窗、事件、透明度和坐标查询独立API。 |
| PopupImageGetY | 获取弹出图片Y坐标 | 缺少 | 布局框可展示图片，但没有命名图片浮窗、事件、透明度和坐标查询独立API。 |

## 易编已检查266个静态点号条目

以下按注册源码分列，同名基础用途可以由统一UI操作等覆盖。列出的说明是差异摘要，不复制旧帮助文案，不把“注册存在”当Native算法验证。完整旧项目兼容仍需针对真实调用的参数、返回、索引、取消及副作用分别确认。

### x/mn0.java（92个静态点号条目）

| 易编条目 | 当前状态 | 对照说明 |
|---|---|---|
| Coordinate.toScreenRect | 部分覆盖 | 宿主坐标映射/Input.resolvePoint可用；缺面向用户的矩形转换积木。 |
| FloatWindow.addStopButton | 缺少 | 缺脚本命名轻量浮窗/事件/样式API；运行控制球和布局对话框不是此类任意浮窗。 |
| FloatWindow.close | 缺少 | 缺脚本命名轻量浮窗/事件/样式API；运行控制球和布局对话框不是此类任意浮窗。 |
| FloatWindow.closeAll | 缺少 | 缺脚本命名轻量浮窗/事件/样式API；运行控制球和布局对话框不是此类任意浮窗。 |
| FloatWindow.create | 缺少 | 缺脚本命名轻量浮窗/事件/样式API；运行控制球和布局对话框不是此类任意浮窗。 |
| FloatWindow.getEvent | 缺少 | 缺脚本命名轻量浮窗/事件/样式API；运行控制球和布局对话框不是此类任意浮窗。 |
| FloatWindow.removeStopButton | 缺少 | 缺脚本命名轻量浮窗/事件/样式API；运行控制球和布局对话框不是此类任意浮窗。 |
| FloatWindow.setAlpha | 缺少 | 缺脚本命名轻量浮窗/事件/样式API；运行控制球和布局对话框不是此类任意浮窗。 |
| FloatWindow.setBackground | 缺少 | 缺脚本命名轻量浮窗/事件/样式API；运行控制球和布局对话框不是此类任意浮窗。 |
| FloatWindow.setImage | 缺少 | 缺脚本命名轻量浮窗/事件/样式API；运行控制球和布局对话框不是此类任意浮窗。 |
| FloatWindow.setInteraction | 缺少 | 缺脚本命名轻量浮窗/事件/样式API；运行控制球和布局对话框不是此类任意浮窗。 |
| FloatWindow.setPosition | 缺少 | 缺脚本命名轻量浮窗/事件/样式API；运行控制球和布局对话框不是此类任意浮窗。 |
| FloatWindow.setSize | 缺少 | 缺脚本命名轻量浮窗/事件/样式API；运行控制球和布局对话框不是此类任意浮窗。 |
| FloatWindow.setText | 缺少 | 缺脚本命名轻量浮窗/事件/样式API；运行控制球和布局对话框不是此类任意浮窗。 |
| FloatWindow.setVisible | 缺少 | 缺脚本命名轻量浮窗/事件/样式API；运行控制球和布局对话框不是此类任意浮窗。 |
| Image.calculateSimilarity | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Image.color | 部分覆盖 | 多点找色已有；旧multiColor.json记录目录格式需转换，不直接读旧数据文件。 |
| Image.colorBatch | 缺少 | 缺多点记录批量全部命中与颜色连通块框结果。 |
| Image.colorMask | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Image.colorStats | 部分覆盖 | 取色/比色/多点比色/找色/计数/透明像素找图已有；字符串颜色、方向、分数及返回协议不全部同旧API。 |
| Image.compareColor | 部分覆盖 | 取色/比色/多点比色/找色/计数/透明像素找图已有；字符串颜色、方向、分数及返回协议不全部同旧API。 |
| Image.compareMultiColor | 部分覆盖 | 取色/比色/多点比色/找色/计数/透明像素找图已有；字符串颜色、方向、分数及返回协议不全部同旧API。 |
| Image.concat | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Image.convert | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Image.crop | 部分覆盖 | Screen.crop帧裁剪API存在；缺独立裁剪积木/运行文件输出，不等于文件图像处理。 |
| Image.findColor | 部分覆盖 | 取色/比色/多点比色/找色/计数/透明像素找图已有；字符串颜色、方向、分数及返回协议不全部同旧API。 |
| Image.findColorBlock | 缺少 | 缺多点记录批量全部命中与颜色连通块框结果。 |
| Image.flip | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Image.getColor | 部分覆盖 | 取色/比色/多点比色/找色/计数/透明像素找图已有；字符串颜色、方向、分数及返回协议不全部同旧API。 |
| Image.getInfo | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Image.grayscale | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Image.match | 部分覆盖 | 像素单/多模板、多位置找图已有；不是旧OpenCV普通/批量算法的等价实现。 |
| Image.matchBatch | 部分覆盖 | 像素单/多模板、多位置找图已有；不是旧OpenCV普通/批量算法的等价实现。 |
| Image.matchEdge | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Image.matchFeature | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Image.matchGray | 已覆盖 | OpenCV灰度找图已有；分数口径仍按本项目契约，不宣称旧阈值等价。 |
| Image.matchMask | 部分覆盖 | 取色/比色/多点比色/找色/计数/透明像素找图已有；字符串颜色、方向、分数及返回协议不全部同旧API。 |
| Image.matchScale | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Image.matchShape | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Image.ocr | 部分覆盖 | 字库OCR已有；字库格式/查找方向/旧参数与得分不能一概等价。 |
| Image.ocrEx | 缺少 | 缺逐字位置/尺寸/得分集合及原生结果读取配置。 |
| Image.ppOcr | 延期扩展 | ONNX按用户决定仅英文数字单行；通用中文、多行检测/框结果及PP模型接口未接。 |
| Image.ppOcrEx | 延期扩展 | ONNX按用户决定仅英文数字单行；通用中文、多行检测/框结果及PP模型接口未接。 |
| Image.ppOcrImage | 延期扩展 | ONNX按用户决定仅英文数字单行；通用中文、多行检测/框结果及PP模型接口未接。 |
| Image.ppOcrLine | 延期扩展 | ONNX按用户决定仅英文数字单行；通用中文、多行检测/框结果及PP模型接口未接。 |
| Image.qrDecode | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Image.qrEncode | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Image.releasePpOcrCache | 延期扩展 | ONNX按用户决定仅英文数字单行；通用中文、多行检测/框结果及PP模型接口未接。 |
| Image.replaceColor | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Image.resize | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Image.rotate | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Image.screenshot | 部分覆盖 | 采集帧/手动截图保存已有；脚本运行期保存PNG尚缺。 |
| Image.setHotCaptureDuration | 新版替代 | 真实取帧及显式不可变缓存；不开放未经新鲜度证明的隐藏热缓存期限。 |
| Image.threshold | 缺少 | 缺运行期图像变换/二维码或扩展匹配API；手动图片工具不算脚本能力。 |
| Math.angle | 缺少 | 缺随机值、四舍五入、范围或角度/轮盘计算的可视化函数；现有受限表达式未提供这些函数。 |
| Math.clamp | 缺少 | 缺随机值、四舍五入、范围或角度/轮盘计算的可视化函数；现有受限表达式未提供这些函数。 |
| Math.distance | 入口缺口 | 已有真实API，但当前没有独立可视化积木。 |
| Math.flipAngle | 缺少 | 缺随机值、四舍五入、范围或角度/轮盘计算的可视化函数；现有受限表达式未提供这些函数。 |
| Math.inRange | 缺少 | 缺随机值、四舍五入、范围或角度/轮盘计算的可视化函数；现有受限表达式未提供这些函数。 |
| Math.joystickX | 缺少 | 缺随机值、四舍五入、范围或角度/轮盘计算的可视化函数；现有受限表达式未提供这些函数。 |
| Math.joystickY | 缺少 | 缺随机值、四舍五入、范围或角度/轮盘计算的可视化函数；现有受限表达式未提供这些函数。 |
| Math.randInt | 缺少 | 缺随机值、四舍五入、范围或角度/轮盘计算的可视化函数；现有受限表达式未提供这些函数。 |
| Math.randStr | 缺少 | 缺随机值、四舍五入、范围或角度/轮盘计算的可视化函数；现有受限表达式未提供这些函数。 |
| Math.round | 缺少 | 缺随机值、四舍五入、范围或角度/轮盘计算的可视化函数；现有受限表达式未提供这些函数。 |
| ScreenDraw.batch | 缺少 | 缺脚本透明绘图层、命名图形和更新/清理接口。 |
| ScreenDraw.circle | 缺少 | 缺脚本透明绘图层、命名图形和更新/清理接口。 |
| ScreenDraw.clear | 缺少 | 缺脚本透明绘图层、命名图形和更新/清理接口。 |
| ScreenDraw.close | 缺少 | 缺脚本透明绘图层、命名图形和更新/清理接口。 |
| ScreenDraw.line | 缺少 | 缺脚本透明绘图层、命名图形和更新/清理接口。 |
| ScreenDraw.path | 缺少 | 缺脚本透明绘图层、命名图形和更新/清理接口。 |
| ScreenDraw.rect | 缺少 | 缺脚本透明绘图层、命名图形和更新/清理接口。 |
| ScreenDraw.remove | 缺少 | 缺脚本透明绘图层、命名图形和更新/清理接口。 |
| ScreenDraw.setAllStyle | 缺少 | 缺脚本透明绘图层、命名图形和更新/清理接口。 |
| ScreenDraw.setVisible | 缺少 | 缺脚本透明绘图层、命名图形和更新/清理接口。 |
| ScreenDraw.text | 缺少 | 缺脚本透明绘图层、命名图形和更新/清理接口。 |
| Script.addRuntimePrompt | 部分覆盖 | 日志/提示/调试设置或状态已有，但缺脚本运行中开启/关闭/清除相应功能的专用API。 |
| Script.clearInputTrace | 部分覆盖 | 日志/提示/调试设置或状态已有，但缺脚本运行中开启/关闭/清除相应功能的专用API。 |
| Script.disableInputTrace | 部分覆盖 | 日志/提示/调试设置或状态已有，但缺脚本运行中开启/关闭/清除相应功能的专用API。 |
| Script.disableLog | 部分覆盖 | 日志/提示/调试设置或状态已有，但缺脚本运行中开启/关闭/清除相应功能的专用API。 |
| Script.enableInputTrace | 部分覆盖 | 日志/提示/调试设置或状态已有，但缺脚本运行中开启/关闭/清除相应功能的专用API。 |
| Script.enableLog | 部分覆盖 | 日志/提示/调试设置或状态已有，但缺脚本运行中开启/关闭/清除相应功能的专用API。 |
| Script.getCurrentProjectPath | 新版替代 | 采用项目资源及data相对路径，不开放直接依赖App私有绝对路径的脚本接口。 |
| Script.pause | 缺少 | 运行球有暂停继续停止；缺脚本自身暂停/恢复/清理后重启入口函数。 |
| Script.removeRuntimePrompt | 部分覆盖 | 日志/提示/调试设置或状态已有，但缺脚本运行中开启/关闭/清除相应功能的专用API。 |
| Script.restart | 缺少 | 运行球有暂停继续停止；缺脚本自身暂停/恢复/清理后重启入口函数。 |
| Script.resume | 缺少 | 运行球有暂停继续停止；缺脚本自身暂停/恢复/清理后重启入口函数。 |
| UI.closeDialog | 已覆盖 | UI对话框及page/show/hide命令已接通；新对话框复用已保存页面。 |
| UI.closeMainWindow | 已覆盖 | UI对话框及page/show/hide命令已接通；新对话框复用已保存页面。 |
| UI.destroyProgram | 需决策 | 结束运行可由用户停止；脚本销毁UI服务/整个程序实例没有专用API。 |
| UI.setFloatBallPosition | 缺少 | 运行球可人工拖动；脚本位置设置无API。 |
| UI.showDialogAndWait | 已覆盖 | UI对话框及page/show/hide命令已接通；新对话框复用已保存页面。 |
| UI.showMainWindow | 已覆盖 | UI对话框及page/show/hide命令已接通；新对话框复用已保存页面。 |

### x/vy.java（33个静态点号条目）

| 易编条目 | 当前状态 | 对照说明 |
|---|---|---|
| Data.jsonGetBool | 部分覆盖 | Data.get/keys/length/text加类型转换能处理标量；旧宽松转换/路径语法并非等价。 |
| Data.jsonGetFloat | 部分覆盖 | Data.get/keys/length/text加类型转换能处理标量；旧宽松转换/路径语法并非等价。 |
| Data.jsonGetInt | 部分覆盖 | Data.get/keys/length/text加类型转换能处理标量；旧宽松转换/路径语法并非等价。 |
| Data.jsonGetJson | 缺少 | 当前Data.get拒绝对象/数组子值；缺嵌套JSON子结构读取。 |
| Data.jsonGetText | 部分覆盖 | Data.get/keys/length/text加类型转换能处理标量；旧宽松转换/路径语法并非等价。 |
| Data.jsonHas | 缺少 | 缺存在性/类型/JSON合法性专用函数；不能把缺失键与JSON null可靠区分。 |
| Data.jsonKeys | 部分覆盖 | Data.get/keys/length/text加类型转换能处理标量；旧宽松转换/路径语法并非等价。 |
| Data.jsonSize | 部分覆盖 | Data.get/keys/length/text加类型转换能处理标量；旧宽松转换/路径语法并非等价。 |
| Data.jsonType | 缺少 | 缺存在性/类型/JSON合法性专用函数；不能把缺失键与JSON null可靠区分。 |
| Data.jsonValid | 缺少 | 缺存在性/类型/JSON合法性专用函数；不能把缺失键与JSON null可靠区分。 |
| Data.regexExtract | 缺少 | 没有有界正则表达式API/积木。 |
| Data.regexExtractAll | 缺少 | 没有有界正则表达式API/积木。 |
| Data.regexMatch | 缺少 | 没有有界正则表达式API/积木。 |
| Data.textAfter | 缺少 | 部分可用现有表达式组合，但没有对应易用函数/积木和边界语义。 |
| Data.textBefore | 缺少 | 部分可用现有表达式组合，但没有对应易用函数/积木和边界语义。 |
| Data.textBetween | 缺少 | 部分可用现有表达式组合，但没有对应易用函数/积木和边界语义。 |
| Data.textContains | 缺少 | 部分可用现有表达式组合，但没有对应易用函数/积木和边界语义。 |
| Data.textCount | 缺少 | 部分可用现有表达式组合，但没有对应易用函数/积木和边界语义。 |
| Data.textEndsWith | 缺少 | 部分可用现有表达式组合，但没有对应易用函数/积木和边界语义。 |
| Data.textFind | 部分覆盖 | 变量计算len/trim/sub/find/replace已实现；旧索引/字节规则与UTF-8规则需区分。 |
| Data.textInsert | 缺少 | 部分可用现有表达式组合，但没有对应易用函数/积木和边界语义。 |
| Data.textJoin | 缺少 | 部分可用现有表达式组合，但没有对应易用函数/积木和边界语义。 |
| Data.textLeft | 缺少 | 部分可用现有表达式组合，但没有对应易用函数/积木和边界语义。 |
| Data.textLength | 部分覆盖 | 变量计算len/trim/sub/find/replace已实现；旧索引/字节规则与UTF-8规则需区分。 |
| Data.textRemove | 缺少 | 部分可用现有表达式组合，但没有对应易用函数/积木和边界语义。 |
| Data.textRepeat | 缺少 | 部分可用现有表达式组合，但没有对应易用函数/积木和边界语义。 |
| Data.textReplace | 部分覆盖 | 变量计算len/trim/sub/find/replace已实现；旧索引/字节规则与UTF-8规则需区分。 |
| Data.textReplaceFirst | 缺少 | 部分可用现有表达式组合，但没有对应易用函数/积木和边界语义。 |
| Data.textRight | 缺少 | 部分可用现有表达式组合，但没有对应易用函数/积木和边界语义。 |
| Data.textSplit | 缺少 | 部分可用现有表达式组合，但没有对应易用函数/积木和边界语义。 |
| Data.textStartsWith | 缺少 | 部分可用现有表达式组合，但没有对应易用函数/积木和边界语义。 |
| Data.textSub | 部分覆盖 | 变量计算len/trim/sub/find/replace已实现；旧索引/字节规则与UTF-8规则需区分。 |
| Data.textTrim | 部分覆盖 | 变量计算len/trim/sub/find/replace已实现；旧索引/字节规则与UTF-8规则需区分。 |

### x/pi.java（17个静态点号条目）

| 易编条目 | 当前状态 | 对照说明 |
|---|---|---|
| Audio.getVolume | 缺少 | 缺音频播放/停止与音量读取设置API。 |
| Audio.play | 缺少 | 缺音频播放/停止与音量读取设置API。 |
| Audio.setVolume | 缺少 | 缺音频播放/停止与音量读取设置API。 |
| Audio.stop | 缺少 | 缺音频播放/停止与音量读取设置API。 |
| Key.action | 部分覆盖 | 对应点击/按下/弹起/移动/长按/滑动及设备键积木；仅单指，不复制任意组合动作或旧指针ID。 |
| Key.down | 部分覆盖 | 对应点击/按下/弹起/移动/长按/滑动及设备键积木；仅单指，不复制任意组合动作或旧指针ID。 |
| Key.hold | 部分覆盖 | 对应点击/按下/弹起/移动/长按/滑动及设备键积木；仅单指，不复制任意组合动作或旧指针ID。 |
| Key.inputText | 缺少 | 字符串输入、设备键独立按下/释放缺API；KeyEvent点击不是持续键按下。 |
| Key.keyClick | 部分覆盖 | 对应点击/按下/弹起/移动/长按/滑动及设备键积木；仅单指，不复制任意组合动作或旧指针ID。 |
| Key.keyDown | 缺少 | 字符串输入、设备键独立按下/释放缺API；KeyEvent点击不是持续键按下。 |
| Key.keyUp | 缺少 | 字符串输入、设备键独立按下/释放缺API；KeyEvent点击不是持续键按下。 |
| Key.move | 部分覆盖 | 对应点击/按下/弹起/移动/长按/滑动及设备键积木；仅单指，不复制任意组合动作或旧指针ID。 |
| Key.swipe | 部分覆盖 | 对应点击/按下/弹起/移动/长按/滑动及设备键积木；仅单指，不复制任意组合动作或旧指针ID。 |
| Key.tap | 部分覆盖 | 对应点击/按下/弹起/移动/长按/滑动及设备键积木；仅单指，不复制任意组合动作或旧指针ID。 |
| Key.up | 部分覆盖 | 对应点击/按下/弹起/移动/长按/滑动及设备键积木；仅单指，不复制任意组合动作或旧指针ID。 |
| Key.upAll | 新版替代 | 任务退出/停止释放持有触点；不提供破坏其他任务所有权的全局弹起。 |
| OtherLanguage.load | 范围外 | 不装载其他语言任意执行环境。 |

### x/ha0.java（100个静态点号条目）

| 易编条目 | 当前状态 | 对照说明 |
|---|---|---|
| Network.fileDelete | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.fileDownload | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.fileUpload | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.ftpDelete | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.ftpDownload | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.ftpList | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.ftpMakeDir | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.ftpUpload | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.httpDelete | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.httpGet | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.httpPost | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.httpPut | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.openUrl | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.redisDelete | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.redisIntAdd | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.redisIntRead | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.redisIntWrite | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.redisRead | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.redisRenew | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.redisWrite | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| Network.urlDownload | 未规划通用能力 | HTTP/下载/打开URL、FTP/Redis及云文件能力未提供；普通网络与旧后端服务分开决策。 |
| System.clearMemory | 需决策 | 系统写/强停需能力与用户授权设计；不因已取得Root而默认开放。 |
| System.forceStopApp | 需决策 | 系统写/强停需能力与用户授权设计；不因已取得Root而默认开放。 |
| System.getBrightness | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.getClipboard | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.getCurrentApp | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.getDate | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.getDateTime | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.getDeviceInfo | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.getScreenSize | 入口缺口 | 已有真实API，但当前没有独立可视化积木。 |
| System.getScreenState | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.getScreenTimeout | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.getTime | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.getTimeMillis | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.isAppInstalled | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.isConnected | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.isValidated | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.isWifiEnabled | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.keepAwake | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.launchApp | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.openSettings | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.setBrightness | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.setClipboard | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.setDateTime | 需决策 | 系统写/强停需能力与用户授权设计；不因已取得Root而默认开放。 |
| System.setScreenTimeout | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| System.setWifi | 需决策 | 系统写/强停需能力与用户授权设计；不因已取得Root而默认开放。 |
| System.shell | 范围外 | 禁止任意Shell字符串。 |
| System.vibrate | 缺少 | 缺脚本设备状态/应用管理/剪贴板/日期时间/系统设置查询写入；App设置入口不算脚本API。 |
| UI.deleteControlItem | 部分覆盖 | items整体替换可用，逐项增删改缺专用操作。 |
| UI.deletePageConfig | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.getControlChecked | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.getControlCurrentIndex | 部分覆盖 | 当前值/多选值JSON已有；选择索引、显示文字和存储值分离未完整开放。 |
| UI.getControlEnabled | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.getControlHeight | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.getControlInputText | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.getControlItems | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.getControlMaximum | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.getControlMinimum | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.getControlPlaceholder | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.getControlSelectedIndex | 部分覆盖 | 当前值/多选值JSON已有；选择索引、显示文字和存储值分离未完整开放。 |
| UI.getControlSelectedIndexes | 部分覆盖 | 当前值/多选值JSON已有；选择索引、显示文字和存储值分离未完整开放。 |
| UI.getControlSelectedText | 部分覆盖 | 当前值/多选值JSON已有；选择索引、显示文字和存储值分离未完整开放。 |
| UI.getControlSelectedValue | 部分覆盖 | 当前值/多选值JSON已有；选择索引、显示文字和存储值分离未完整开放。 |
| UI.getControlSelectionMode | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.getControlStep | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.getControlText | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.getControlValue | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.getControlVisibility | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.getControlWidth | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.getControlXPosition | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.getControlYPosition | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.getControlZIndex | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.insertControlItem | 部分覆盖 | items整体替换可用，逐项增删改缺专用操作。 |
| UI.loadPageConfig | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.resetPage | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.savePageConfig | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.setControlBackgroundColor | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.setControlBackgroundImage | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.setControlChecked | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.setControlCurrentIndex | 部分覆盖 | 当前值/多选值JSON已有；选择索引、显示文字和存储值分离未完整开放。 |
| UI.setControlEnabled | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.setControlHeight | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.setControlInputText | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.setControlItems | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.setControlMaximum | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.setControlMinimum | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.setControlPlaceholder | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.setControlSelectedIndex | 部分覆盖 | 当前值/多选值JSON已有；选择索引、显示文字和存储值分离未完整开放。 |
| UI.setControlSelectedIndexes | 部分覆盖 | 当前值/多选值JSON已有；选择索引、显示文字和存储值分离未完整开放。 |
| UI.setControlSelectionMode | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.setControlStep | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.setControlText | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.setControlValue | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.setControlVisibility | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.setControlWidth | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.setControlXPosition | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.setControlYPosition | 已覆盖 | 统一UI.command/getValue/setValue和界面操作积木提供对应基础状态/设计像素读写；不要求一项一个同名包装。 |
| UI.setControlZIndex | 缺少 | 设计时有部分对应属性；运行期白名单不包含这些操作/页面配置持久化。 |
| UI.switchMainPage | 已覆盖 | UI对话框及page/show/hide命令已接通；新对话框复用已保存页面。 |
| UI.updateControlItem | 部分覆盖 | items整体替换可用，逐项增删改缺专用操作。 |

### x/w90.java（24个静态点号条目）

| 易编条目 | 当前状态 | 对照说明 |
|---|---|---|
| File.copy | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.countDirectories | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.countFiles | 部分覆盖 | Files.list+Data可组合计数/筛选，但不是独立统计函数。 |
| File.countFilesByExtension | 部分覆盖 | Files.list+Data可组合计数/筛选，但不是独立统计函数。 |
| File.deleteLine | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.exists | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.formatSize | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.fromBase64 | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.getDirectoryStats | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.getExtension | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.getSize | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.insertLine | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.isDir | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.isFile | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.mkdir | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.move | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.read | 部分覆盖 | 私有data目录64KiB UTF-8读/写/删除已接通；旧通用路径、二进制/追加模式不全部支持。 |
| File.readLine | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.remove | 部分覆盖 | 私有data目录64KiB UTF-8读/写/删除已接通；旧通用路径、二进制/追加模式不全部支持。 |
| File.rename | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.toBase64 | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.unzip | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |
| File.write | 部分覆盖 | 私有data目录64KiB UTF-8读/写/删除已接通；旧通用路径、二进制/追加模式不全部支持。 |
| File.zip | 缺少 | 缺脚本文件/目录管理、行操作、元数据、Base64或zipAPI。 |

## 定位依据

- 一键玩菜单：资料/易编精灵/16_yijianwan分析/sources/sources/com/yijianwan/kaifaban/guagua/script/fun/funList.java；界面子菜单funJieMian.java；参数funAnJian/funXunTu/funWenJian/funWangLuo/funQiTa.java。
- 完整索引：资料/易编精灵/16_yijianwan分析/脚本能力原始目录.json，216项；该目录用于身份和参数位置核对，不沿用历史实现标记。
- 易编注册与文案：资料/易编精灵/01_反编译源码/sources/sources/x/mn0.java、vy.java、pi.java、ha0.java、w90.java；wt.y的固定Base64+循环XOR字符串还原只作数据读取，未执行旧代码。
- 智构实际契约：[API快照](../../schema/api-schema/snapshot/contract.json)、[积木目录](../../schema/block-catalog/blocks/)。
- 智构入口：[FunctionCatalog](../../android/apps/studio-android/src/main/java/com/autoscript/studio/editor/FunctionCatalog.kt)。
- 智构受限计算：[variable_expression.rs](../../rust/tools/flow-compiler/src/variable_expression.rs)。
- 智构运行属性：[ScriptUiRuntimeProperties](../../android/modules/script-ui/src/main/java/com/autoscript/script/ui/ScriptUiRuntimeProperties.kt)、[ScriptUiCommandState](../../android/modules/script-ui/src/main/java/com/autoscript/script/ui/ScriptUiCommandState.kt)。
- 新增对话框：[本批验证记录](../verification/script-dialogs-2026-10-11.md)。

结论：不能称函数库已经完整对齐老版；基础按键、像素视觉、流程/任务、变量计算和基本UI已成链，通用脚本工具、细化UI与文件/OCR还有明确缺口。已存在能力与严格旧语义兼容是两件事，不给未经证明的百分比。
