# ADR 0017：循环编辑、位置指标与中途检查

状态：接受，2026-09-29。用户要求对齐、完善循环功能。先记录协议决定，再实施。

## 来源与边界

核对旧一键玩 `script/insertCycle.java`、`config/configCycle.java` 与易编 `x/rr/w72/qh.java`、`service_tk_xh.xml`。新版五项紧凑布局为主，旧版空循环（等待）和超时/超次操作放在同面板展开区。旧版只导出了超限标记，新版onClick反编译缺失，因此不冒充复用了不可见native执行逻辑。

## 决定

- 共享结构化循环命令负责两个可视化入口，禁止指标命令退回模糊搜索误插新循环。选择变量使用声明与插件参数的类型/作用域；支持维护后返回，不丢草稿。UI不执行Lua。
- 现有control.repeat/while v1兼容；while增加可选durationUnit，缺省milliseconds，旧durationVariable仍表示毫秒。新面板默认秒，可明确选择毫秒/秒/分钟。固定时长继续冻结为durationMs，不改变历史数据。
- 新增control.loopmetric v1（metric=count/elapsed，name，unit缺省milliseconds），是循环体内独立步骤，读取最近结构循环的当前次数或该位置的单调耗时；新面板时间默认秒，旧metric编辑提示按毫秒兼容。旧循环头index/iteration/elapsed变量仍每轮开始写入。
- 新增control.loopcheck v1（metric，limit，limitVariable可选，unit缺省milliseconds），独立超次/超时检查，达到用户明确设置的阈值时退出最近循环。不是伪造旧native的状态码；界面说明“达到阈值结束最近循环”。必须位于循环体，不跨插件/任务隐式取外层状态。
- 空循环复用真实task.sleep，不新增无意义节点。等待由既有调度截止时间及任务取消控制，不用UI计时或忙等；固定输入限制24小时，变量等待保留task.sleep既有非负整数契约，不改变历史等待节点范围。
- 次数0..1000000，安全上限1..1000000，时长1..86400000毫秒；严格拒绝空值、负数、非有限数值、分数次数、超限和小于1ms时长，不静默修正。UI解析用十进制，Rust执行前复查变量；时间单位显式缩放。
- 无限循环保留次数安全上限及任务取消/资源限制；用户可配置上限但不可取消。到上限明确报错，不伪装永久无限。
- 生成器版本升至plugin-v5使旧冻结校验失效并重新编译；不删除项目或已有节点，不让Runner依赖Flow编译器。

## 验证

已覆盖两个入口的共享命令转换、插入位置/循环祖先、嵌套指标和中途检查、秒/毫秒兼容、变量类型及运行边界。Rust全工作区249项、Studio 171项单元测试通过，格式、契约/Schema检查、静态检查和Debug构建通过（静态检查仍有非阻断警告）。设备验收留给用户；具体结果和边界见loop-alignment-delivery.md。
