# 调试面板交付说明

可视化编辑的“调试”页现在把四种输出明确分开：运行提示（`task.runprompt` → `Prompt.show`，用户可见的悬浮状态）、弹出提示（原 `task.prompt` → `Prompt.toast`，按项目弹窗样式到时消失）、开发日志（`task.log` → `Log.info`，仅用于开发日志）和注释（`task.comment`，不执行）。原有 `task.prompt` 的含义和版本保持不变；新增 `task.runprompt` v1，不迁移或删除已有节点。

运行输出可以填写文字或选择已有变量。选择变量时插入节点的 `valueVariable`，文字作为编辑器中的说明；改用文字可清除变量绑定。空内容、非法变量名和超过 2048 UTF-8 字节的内容会在加入前拒绝。可视化插入使用编辑器内部的结构化参数，避免从 Lua 字符串反解析而丢失换行、引号等字符；Lua 编辑继续插入对应的脚本 API。

“提示设置”是自动运行事件的分类过滤，只影响可视化插件运行时生成的循环、跳转、寻图和变量等提示；手动插入的提示不受分类过滤。全局“显示用户提示”同时控制 `Prompt.show` 与 `Prompt.toast`；开发日志独立。运行前延迟及弹窗样式仍是项目持久化配置。弹窗样式仅用于短时弹窗，不改变运行提示悬浮窗。开发环境页只陈列当前 Root 能力，不再给未实现的后端或调试开关展示可操作控件。

本批主机验证：`cargo fmt --all -- --check`、`cargo test --offline -p flow-compiler`、`cargo run --offline -p schema-check -- .`、`cargo clippy --offline -p flow-compiler --all-targets`、Studio `testDebugUnitTest`、`lintDebug`、`assembleDebug`。Clippy 当前仍报告仓库原有警告，`-D warnings` 会在 `flow-ir` 的既有代码处失败。设备端悬浮窗与多分辨率布局仍需人工验收。

弹窗样式预览始终在预览区水平和垂直居中，用于查看尺寸、字体、颜色及圆角；X/Y 只控制真实屏幕中的弹窗位置，仍使用左上角坐标及 -1 自动居中的规则。预览文字也关闭额外字体留白。
