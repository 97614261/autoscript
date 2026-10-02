# 变量页面重排与循环源码对比（2026-09-29）

> 本文保留修改前的审查证据。用户随后授权“对齐，完善”，下述循环缺口已在后续批次处理；当前状态以[循环对齐交付](loop-alignment-delivery.md)和ADR 0017为准。

> 用户随后要求变量页移除类型筛选、压缩行高。卡片和双列已由[紧凑变量列表](variable-page-compact-delivery.md)取代；本文以下变量布局描述为历史记录。

## 本批范围

变量页实际改版，页面和悬浮可视化编辑器复用 `VisualVariableManagerDialog.kt`。循环仅审查，不擅自修改运行时单位、节点协议或用户已有循环。

变量页：白底/蓝色标题和轻分区；局部/全局分段导航显示数量；搜索与四类型筛选组合；类型色标、变量名、作用域摘要组成卡片；编辑、赋值/计算、删除各有独立操作区。420dp以上正文两列，否则单列；不再强制保留360dp空内容区，列表高度按条目数封顶，长列表懒加载。新增/编辑另开紧凑双列类型表单，说明初始值而不伪造运行中的数值。草稿未保存有状态提示、放弃确认，图像不提供标量计算入口；同名遮蔽仍保留声明但阻止错误的赋值访问。

## 参考源码证据

### 一键玩（旧版）

- `资料/易编精灵/16_yijianwan分析/sources/sources/com/yijianwan/kaifaban/guagua/script/insertCycle.java`：8项选择、时长/次数/等待下拉、自定义常量或变量、指标变量选择；确认后另选插入位置。
- 同目录 `config/configCycle.java`：限时（秒）、限次、空循环（毫秒等待）、无限、获取循环时间、获取循环次数、循环超时、循环超次。获取指标生成独立位置的步骤；不是只给循环头添加字段。
- `script/fun/varName.java`：维护和赋值分开；整数/浮点可赋值，字符另有属性/分组，寻图类型有对象属性，不能把“寻”直接当新图像帧。

### 易编（新版）

- `资料/易编精灵/10_页面布局/apktool_out/res/layout/service_tk_xh.xml`：5项——无限、限次、限时、获取次数、获取时间；固定值/变量分段；次数/时长预设；四个变量选择区；40dp标题/底栏、30dp输入，无大段强制空白。
- `资料/易编精灵/01_反编译源码/sources/sources/x/rr.java` 对应上述布局（2131427630），`d()` 调用 `w72`：次数选择“整”，时间选择“浮”；`b/c`分别读固定输入或变量名，`g`切换两组控件。
- `x/qh.java` 常量按 `wt.y` 的Base64/重复密钥XOR算法解码核对：次数预设1(次)、2(次)…；时长预设0.5(秒)、1(秒)…。
- `x/w72.java`：带类型过滤的局部/全局选择和快速新增；声明及赋值布局参考 `service_tk_var_layout.xml`、`service_var_select.xml`、`service_tk_var_assignment.xml`。
- `rr.onClick` 在当前Java反编译导出中缺失，不能据此断言新版指标写入时机、完整时长范围或native执行语义与我们完全一致。可确认的是布局、可见类型与预设和上述可读方法。

## 当前循环对比

| 项目 | 参考 | 当前代码 | 判断 |
| --- | --- | --- | --- |
| 固定限次 | 两版都有 | LoopPage→repeat:fixed→control.repeat→Lua for | 已接通；固定0次允许跳过 |
| 变量限次 | 整型变量 | timesVariable；编译器检查整型、运行时0..1000000 | 有运行能力；UI选择器没有类型过滤 |
| 固定限时 | 旧秒、新版秒预设 | 秒/毫秒/分钟换算durationMs；单调时钟结束判断 | 已接通；错误输入会被静默修正，不够严谨 |
| 变量限时 | 新版选择浮型，预设文本为秒 | durationVariable直接作为毫秒数；UI没有单位提示 | 不对齐，变量值含义容易错；变量时长没有固定时长的24小时上限/有限数值校验 |
| 无限 | 两版可选 | always=true，但control.while默认maxIterations=10000 | 有循环但不是字面无限；安全上限须保留并明确提示 |
| 获取次数/时间 | 旧版独立步骤；新版五项可见 | 页面更新最近结构循环index/iteration/elapsed字段，循环每轮进入时写值 | 页面有实现；时间为毫秒，非该位置即刻读取/最终完成耗时 |
| 悬浮获取指标 | 应与页面一致 | ProjectPage.insertFloatingBlock未处理metric命令；退回“条件循环”模糊搜索 | 入口未接通，有误插循环风险，优先修复 |
| 次数/时长预设 | 新版有真实下拉列表 | 次数DenseLoopValue的onUnit为空；时间下拉只切换单位 | 不完整，不等价于参考预设 |
| 类型/作用域选择及新增 | 易编w72过滤整/浮并支持新增 | LoopPage只拿List<String>；不能显示作用域/类型或快速新增 | 不完整；同名遮蔽及未声明名字最终靠编译器拒绝 |
| 输入和错误反馈 | 输入检查再加入 | 次数非法默认1、超限coerce；时间非法/负数最终变1ms | 需拒绝错误输入，不应静默改值 |
| 空循环/超时/超次步骤 | 旧版独立操作 | 等待已有task.sleep（在跳转）；循环页无超时/超次独立步骤 | 旧版完整能力尚未对齐；不能仅以功能在别处存在视为同入口完成 |

当前代码位置：`EditorEntryDialogs.kt:LoopPage`、`VisualProjectScreen.kt` 的metric分支和 `legacyDockBlockArguments`、`ProjectPage.kt:insertFloatingBlock`、`tools/flow-compiler/src/analyze.rs:RepeatArgs/WhileArgs/validate_declared_variable_types`、`render.rs:render_repeat/render_while`。现有变量次数测试、固定限时转换、最近循环祖先测试不覆盖完整弹窗行为。

## 后续建议次序（未在本批实现）

1. 统一页面/悬浮循环命令处理，指标命令只绑定最近循环，禁止误插。
2. 接入类型/作用域变量选择和快速新增；明确秒/毫秒转换，兼容旧项目，不直接改变已保存durationVariable语义。
3. 实现预设、自定义数值，前端与Rust一致拒绝空值、非法值、非有限数值、越界，不静默修正。
4. 显示无限循环的安全上限；明确指标采样时机和单位，再设计独立指标步骤、超时/超次分支。需要改节点/运行契约时先补ADR与迁移策略。
5. 增加真实Lua执行和两种入口命令回归；真机由用户测试。

## 验证边界

本批不操作设备。变量页增加4项JVM测试：作用域/搜索/类型组合、编辑重名/命名边界、被遮蔽全局、参数及图像赋值限制；Studio全量165项测试通过，无失败/错误/跳过。Android lint和Studio Debug构建通过；cargo fmt检查、git diff空白检查通过。未更改Rust循环实现，不重复宣称完整循环实测；源码审查不代表循环已全部对齐或真机像素验收。新安装包仍在 `apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk`。
