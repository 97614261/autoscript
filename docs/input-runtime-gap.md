# 输入与定时器运行时缺口：三个「阻塞点」的核实结论

面向：接手完整端到端流程的人

`AI接手说明.md` 第 5 条要求「先核实并修复三个阻塞完整流程的问题」。核实做完了，结论是**这不是三个问题，是同一个缺口的三个症状**，而且其中两个单独去修会产出可证明永不执行的代码。本文记录取证路径，便于复核。

核实日期：2026-09-16，基线 `wip/m27`。

## 结论速览

| 原始表述 | 核实结果 |
|---|---|
| `Timer.every` 回调完成路径 | 逻辑正确但**不可达**：`Scheduler::every` 在生产代码中无调用点，Lua 侧也没有 `Timer.every` API |
| `StopPlan.release_pointer_ids` 被 JNI 丢弃 | 属实，但该字段**恒为空**，修了是给空 Vec 加消费代码 |
| `InputArbiter` 未接真实输入路径 | **真正的根因**，且范围远大于「接一根线」 |

## 1. `Timer.every`：整条特性不可达

`runtime-scheduler/src/scheduler.rs:616` 的 `every()` 实现完整，语义是「固定速率、不可重入」。

非重入靠 `TimerKind::Interval` 的 `in_flight` 标志维持（`scheduler.rs:1251-1262`）：

- 首次到期：置 `in_flight = true`，推一个 `SchedulerEvent::TimerReady`
- 之后每次到期：因为 `in_flight` 仍为真，只累加 `pending_ticks`，**不推事件**
- 只有 `complete_timer_callback()`（`scheduler.rs:649`）会把 `in_flight` 清掉，并把攒下的 tick 合并成一次补发

取证：

```
rg 'Scheduler::every|\.every\(' engine/          -> scheduler.rs:616（定义）、scheduler.rs:1640（自身测试）
rg 'complete_timer_callback' engine/             -> scheduler.rs:649（定义）、1655/1666（自身测试）
rg '^#\[cfg\(test\)\]' engine/crates/runtime-scheduler/src/scheduler.rs  -> 1332
```

1640/1655/1666 全在 1332 起的 `mod tests` 内。生产路径上**没有任何调用点**。

所以：一旦有人把 `every` 接出去而不同时接 `complete_timer_callback`，间隔定时器会**只触发一次然后永久静默**。这是一个已经埋好的陷阱，但今天还不是一个正在发生的 bug。

执行器侧的对应位置是 `runtime-executor/src/lib.rs:1509`：

```rust
SchedulerEvent::TimerReady { task, .. } => {
    self.resume_inputs.insert(*task, LuaInput::None);
}
```

`timer` 字段被 `..` 丢掉了。将来接线时，回调轮次结束的地方需要拿到它来回调 `complete_timer_callback`。

## 2. `release_pointer_ids`：字段恒空

`engine-jni/src/lib.rs:977` 确实吞掉了整个 `StopPlan`：

```rust
Err(_) | Ok(_) => publish_engine_state(engine, signals),
```

但 `StopPlan.release_pointer_ids` 来自 `InputArbiter::pressed_pointers`，而 `pressed_pointers` 只有一个写入点——`input_arbiter.rs:196` 的 `note_dispatched()`。

取证：

```
rg 'InputArbiter|input_arbiter' engine/crates/engine-core/src/lib.rs
  -> 135（字段声明）、222（构造）、618 与 626（stop 中调用）
```

engine-core 里**没有** `enqueue` / `next` / `note_dispatched` 的调用。因此 `pressed_pointers` 恒为空集，`release_pointer_ids` 恒为 `[]`。

原始描述「中途停止滑动手势会让触摸屏卡住」在**今天的代码里不会发生**——因为今天根本不存在「按下但未抬起」的中间态（见下一节）。这个修复要等第 3 项落地后才有意义，届时它是必需的。

## 3. 真正的缺口：仲裁层与生产输入路径说的不是一种语言

生产输入路径（`engine-jni/src/lib.rs:159-160`）：

```rust
OP_INPUT_TAP   => dispatch_tap(client, &request.args, coordinates),   // -> client.tap(x, y)
OP_INPUT_SWIPE => dispatch_swipe(client, &request.args, coordinates), // -> client.swipe(start, end, duration)
```

`dispatch_tap`（181）和 `dispatch_swipe`（197）都是**同步、原子、一次性**的宿主调用。它们不产生 `PointerDown` / `PointerUp`，也没有办法在两次调用之间让某个触点保持按下——这正是 `pressed_pointers` 恒空的物理原因。

而 `InputArbiter`（`automation-core/src/input_arbiter.rs:96`）建模的是另一套东西：事务（`InputTransaction`）、任务间轮转公平（`round_robin`）、逐条 `InputCommand::PointerDown/PointerUp`、停止时释放存活触点。

两者之间本来设计了一层适配：`automation-core/src/backend.rs:12` 的 `AutomationBackend`，正好提供 `dispatch_input(request, task, commands)` 与 `release_pointers(pointer_ids)`。

```
rg 'impl.*AutomationBackend|AutomationBackend for' engine/   -> 无任何结果
```

**没有任何实现，连测试桩都没有。** 这是一个设计好但从未建造的层。

## 4. 真实工作范围

按依赖顺序：

1. **宿主指针原语**。Android 侧现在只暴露阻塞式的 `tap` / `swipe`。需要能表达「按下并保持」「移动」「抬起」的原语，否则仲裁器的命令模型无处落地。这一步决定了后面全部工作的形状，也是风险最大的一步（涉及 Root/无障碍注入通道的能力边界，须重新过一遍 Lua 不得触及 shell / Binder / native 指针的约束）。
2. **实现 `AutomationBackend`**，把 `InputCommand` 序列翻译成第 1 步的原语。
3. **改造 `dispatch_tap` / `dispatch_swipe`** 走 `enqueue` → `next` → 逐条下发 → `note_dispatched`，而不是直接调 `client`。此时 `pressed_pointers` 才会真的有内容。
4. **修 `engine-jni/src/lib.rs:977`**，把 `StopPlan.release_pointer_ids` 交给 `release_pointers`。此时第 2 节的修复才有意义。
5. **暴露 `Timer.every` 到 Lua**，并在回调轮次结束处调用 `complete_timer_callback`（拿 `runtime-executor/src/lib.rs:1509` 当前丢弃的 `timer` 字段）。

第 1 步是一个里程碑级的工作量，不是 M27 的收尾。建议单独立项，不要塞进当前验收轮。

## 5. 为什么不建议「先把能改的两处改了」

第 1、2 项的修复在第 3 项落地前，都是**不可达代码**：编译通过、单测能造数据跑绿，但生产路径上永远不执行。这类改动会让后续接手的人以为这两处已经验证过，实际上没有任何真实流量走过它们。宁可留着当前这份核实文档，也不要留下一段看起来已完成的死代码。
