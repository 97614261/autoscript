# ADR-0001：Root 输入运行时与后端同线程持有

- 状态：已采纳
- 日期：2026-09-18
- 影响模块：`automation-core`、`engine-core`、`engine-jni`、`root-*`

## 背景

旧实现把 `InputArbiter` 放在 Lua 引擎线程的 `EngineSession` 中，但真实输入由独立的
`autoscript-root-io` 线程同步发送给 RootDaemon。生产路径绕过仲裁器，停止时生成的
`StopPlan` 也无法交给真实后端，导致事务公平性、触点状态和停止清理都只是不可达设计。

## 决策

1. `InputRuntime` 和 `AutomationBackend` 由 `autoscript-root-io` 同一线程持有，不在两个
   线程各维护一份输入状态。
2. Lua 仍只产生强类型 `HostRequest`。Root I/O 线程完成坐标映射，将 tap、swipe 和
   keyevent 包装为 `InputTransaction`，再执行 `enqueue -> next -> dispatch ->
   note_dispatched -> complete`。
3. stop 使用独立原子信号加 `ExternalHostQueue::interrupt()` 唤醒 Root I/O 线程；它停止
   仲裁器并释放存活触点，但不关闭可复用的 Root 连接。Session reset 通过独立控制消息重建
   仲裁器。
4. Root 协议版本升为 3，增加结构化单触点 Down/Move/Up 命令。Lua 不获得 shell、Binder、
   文件描述符或 native 指针。
5. 单触点原语使用能力位协商。Android API 24/25 继续使用 INPUT_BASIC，不宣称指针能力；
   API 26 及以上才由当前 shell 后端声明 `INPUT_POINTER_SINGLE`。缺少该能力不得阻断基础输入
   和截图握手。

## 后果

- 输入状态、实际派发和停止清理有唯一所有者，`release_pointer_ids` 不再跨线程丢失。
- 当前 Lua tap/swipe/keyevent 全部经过仲裁器；后续复合手势可以复用同一事务模型。
- `/system/bin/input swipe` 仍是一次同步 Root 请求，停止只能在命令边界生效；要做到长滑动
  的毫秒级中断，需要后续引入可取消的持久化注入后端，不能伪装成当前阶段已解决。
- Root protocol v2 客户端与 v3 daemon 不兼容，Runner 和 daemon 必须成对发布。
