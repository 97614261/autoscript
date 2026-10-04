# 可视化插件源码编辑 · 计划 1–5

## 入口与状态

可视化编辑器底栏「编辑 → 源码」。源码、积木预览和函数参考共用一份编辑草稿。原插件仍保存为 JSONL，变量、插件参数、返回声明和界面仍由项目清单管理。不是编辑编译器生成的运行 Lua，也不会新增第二份独立脚本。

- 源码可修改；「积木预览」只解析、定位，不写入，点击条目返回对应源码行。
- 「校验并应用」经过语法、结构、完整 Flow 编译检查后进入一次撤销记录，随后调用既有自动保存路径；保存失败保留未保存积木草稿并在宿主显示错误，不伪装保存成功。
- 原草稿变化、运行状态变化、关闭/取消、切换插件时不允许迟到校验覆盖编辑器。
- 原始导出失败不能提交；未知函数、结构、身份、变量及非法语法明确报错。错误包含行列，编译器节点/JSONL 行也映射回源码位置。
- 程序树、撤销重做和存储格式保持兼容；本批不删除旧 Lua 项目或调试页面。

## 语言边界

这是 Lua 5.4 语法的**可视化描述子集**，不是任意 Lua 双向转换。解析器在 `android/studio-compiler`，不使用 VM 执行源码；UI 不依赖 Lua 实现。唯一最终执行语义来自既有 Rust Flow 编译器，数值计算、变量类型/作用域、输入取消、资源和能力校验均不另写一套。

所有积木目录契约动态映射：每个 kind 分段首字母大写，如 `input.tap → Input.Tap`、`flow.call.branch → Flow.Call.Branch`。参数必须是具名表；动态参数应使用既有 `valueVariable`、`timesVariable` 等变量字段。函数参考页展示完整目录及字段名。新节点版本按当前契约赋值，保留节点版本不静默升级。

```lua
Input.Tap({x = 100, y = 200})
Task.Comment({message = "说明"})

-- 以下变量必须已在变量维护页声明，或是当前插件参数
count = 3
total = count
total = total + 1
total = Calculate("integer(total / 2)")

if count > 0 and not (total == 0) then
  Input.Tap({x = 100, y = 200})
elseif count == 0 then
  Task.Comment({message = "零"})
else
  Task.Comment({message = "其他"})
end

for _ = 1, 3 do
  Task.Comment({message = "循环体"})
  break
end

::done::
goto done
```

以上赋值转换到设置/复制/计算积木；表达式继续使用现有 Rust 有界表达式语法，未声明变量和不可用函数不会自动创建/放行。`local`、自定义函数、模块、自由闭包、动态参数调用、任意 return/repeat-until 等未映射语法拒绝应用。裸 `not x > 0` 拒绝，必须 `not (x > 0)`，避免优先级误解。

为了保留每个现有积木的全部设置，导出循环和条件使用显式描述器：

```lua
if Condition({variable="count", operator="greaterThan", value=0}) then
  -- 分支
else
end

for _ = 1, Repeat({times=3, indexVariable="count", elapsedVariable="elapsed"}) do
  -- 次数和时间输出仍是项目变量，不引入隐藏 Lua 局部变量
end

while Condition({variable="count", operator="lessThan", value=10, maxIterations=10000}) do
  count = count + 1
end
```

普通 `while count < 10 do ... end` 映射为最大 10000 次的条件循环；无限/限时循环使用相应 `Control.Cycle` 契约，保留取消和让步参数，不悄悄取消上限。其他有子区的函数使用 `{["body"]=function() ... end}` 等具名子区；它们是解析结构，不执行闭包。函数子区必须与契约一致。

`Json.Array({ ... })` 区分数组和具名表，`Json.Null` 保留空值，避免 nil 删除字段；数组、空数组、空对象、空值、字符串转义和数字精度均可往返。字符串使用普通单双引号和规定 Lua 转义，不接受长字符串/长注释；普通逐行和行尾 `--` 注释变成注释积木，`--!` 是保留说明行。

`--@node {"id":"...","disabled":false}` 是节点身份/禁用标记。保留标记维持原 ID、版本和子区身份；移动重新生成顺序键，语义顺序来自源码。复制或改类型时删除复制段/修改段的旧标记，让解析器创建新 ID；外来或重复身份报错。禁用状态不通过注释整段代码模拟。

## 上限与验证

源码和转换后 JSONL 各 1 MiB，最多 4096 节点、200000 词法项，结构/参数递归 32 层；自然复合条件沿用 64 项/8 层限制，elseif 最多 32。导出和解析在后台，定期检查取消；校验流程设 30 秒超时。底层同步 Binder 编译请求不能硬中断，但返回后取消的调用不允许写入，界面不在主线程等待。

全目录往返测试覆盖契约名、原版本/禁用状态、所有参数、复杂数组/对象/空值；另覆盖嵌套分支与循环、自然赋值与逻辑表达式、跳转、未知代码拒绝、非法身份、修改/删除/重排、撤销重做、行列定位、输入上限、取消及长条件链。真实运行、权限和素材仍由现有编译器及运行服务检查；测试不执行用户私人脚本。

Gson 2.11.0 为仓库既有依赖（Apache-2.0），本次只复用至 Studio 工具链，无新增第三方库、Lua VM、JNI 协议或 ABI。Android minSdk 24、Runner 依赖边界不变。

## 本批交付记录（2026-10-04）

1. ADR 0039、产品约束和语法边界已记录。
2. 70 个现有积木契约均有可逆 Lua 表示，保留身份、版本、禁用、参数和子区；支持行区间映射。
3. 有界词法/递归下降解析、自然赋值/比较/分支/限次和条件循环/跳转及具名容器已接通；未知内容阻断，不执行源文本。
4. 全目录契约映射及现有 Rust 类型/资源/能力/作用域检查复用，未加入第二套运行语义。
5. 共享源码草稿、积木预览、函数参考、行列错误、校验后可撤销应用、自动保存、取消及陈旧上下文保护已接通两个可视化宿主。

最终 `build.cmd -Verify -StartupTimeoutSeconds 60 -InstallTimeoutSeconds 120` 成功，Debug APK 更新安装至 MuMu 0（127.0.0.1:16384），数据保留。Studio 293 项、runtime-client 17 项、project-store 58 项、script-ui 28 项全通过；runtime-service 51 项中 50 通过、1 项既有 Windows 平台限制跳过。新增 10 项源码测试，其中全目录测试遍历 70 个契约。Lint 0 Error、35 Warning、16 Hint，相关空白检查通过。`cargo test --offline -p flow-compiler --lib` 84 项全通过。未进行私人脚本或设备输入实测；旧 Lua 项目入口清理阶段未执行。
