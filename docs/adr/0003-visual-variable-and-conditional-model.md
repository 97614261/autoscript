# ADR 0003: 可视化变量与判断链模型

## 状态

已接受（2026-09-20）

## 背景

易编精灵的可视化编辑支持全局/当前源文件变量、整数/字符/浮点/图像类型，以及 `IF / ELSEIF / ELSE` 判断链。当前 Studio 仅把变量当作 Flow 内 JSON 标量，并只支持二路 `control.if`。这使变量选择、跨 Flow 复用和判断链编辑无法对齐。

## 决策

1. 项目清单新增可选 `variables` 声明表；旧项目缺失该字段时按空表迁移，禁止删除旧数据。
2. 变量声明由 `name`、`scope`（`global` 或 `flow`）、可选 `flowId`、`type`（`integer`、`number`、`string`、`image`）组成。声明才是类型真相；节点只引用名字。
3. `variable.set` 升级为受限表达式赋值，而不是任意 Lua；初期允许字面量、变量引用和 `+ - * / ..`，并在编译期做类型检查。
4. 判断保留结构化节点：`control.if` 的 `elseIf` 是有序条件列表，`then`、每个 `elseIf`、`else` 均是命名子块。删除或移动判断时必须以整条链为单位。
5. 编译器将变量声明、赋值和判断链编译为冻结 Lua；Runner 只运行冻结产物，不获得编辑器数据模型。

## 后果

- Project Store、Flow IR、Rust 编译器、Block Catalog 和 Compose 编辑器需要同步升级与测试。
- 数据版本保持兼容：现有 `control.if` 的 `then/else` 仍可读取，缺失 `elseIf` 等价为空列表。
- 不引入任意表达式执行、Context、Shell 或 Lua 逃逸路径。
