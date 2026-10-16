# 函数库基础工具第一批 · 2026-10-11

决策：[ADR0067](../adr/0067-bounded-script-utility-functions.md)。使用：[基础工具](../guides/script-utilities.md)。状态与剩余范围：[执行计划](../reference/function-library-development-plan.md)。

## 本批交付

新增13个API、16种积木，当前78个API、89种积木。函数库新增计算/系统分类；随机整数/字符串、四舍五入/限幅/范围、距离/角度/轮盘、RGB通道、屏幕尺寸/单调时钟/Unix时间均有真实执行链。

配置采用紧凑输入和固定值/声明变量选择，输出严格类型；维护变量复用原页，未保存关闭确认。库加入、标题栏插入与修改共用面板；受限源码生成契约往返，声明与运行双重校验。Math.distance通过原声明的core.math契约执行，明确有限数值预算，不继续使用绕过能力检查的直接Lua包装。

新增源码按职责放在editor/和Rust现有crate/tool目录。编译器只在dev-dependencies引用已有本地runtime-executor/runtime-scheduler，用于冻结Lua真实执行测试；没有新增外部依赖，也没有改变生产模块依赖方向。

## 最终检查

- scripts/check-rust.ps1 -Offline成功：格式、严格Clippy(-D warnings)、全工作区测试、schema-check及api-codegen一致性；检查269个JSON、99个Schema、78函数、89积木。新增测试覆盖随机端点/字符模式/上限，负半值取整、角度/颜色，能力拒绝和无宿主随机调用，16积木冻结后真实RuntimeExecutor执行，变量类型/声明/未知字段/旧API拒绝。
- build.cmd -Verify成功：Studio555项、script-ui37项、project-store66项、runtime-client19项、runtime-service79项（1跳过），零失败。Lint零错误，仍有32警告、17提示。
- Debug APK构建及内嵌Runner构建成功，已更新MuMu 0（127.0.0.1:16384），保留应用数据；APK位于android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk。
- 布局和文档链接检查、git diff --check通过。

首次编译检查捕获并修正了范围表字段引用、测试模块引用和函数长度问题，未通过禁用静态规则/删测试解决。没有运行私人脚本，安装成功不代表触摸/键盘/字体边界都已设备验收。

## 可手测

1. 项目设置明确启用数值与随机工具/设备显示尺寸能力；先声明整数、浮点、字符结果变量。
2. 函数库计算/系统加入工具，查看固定值和来源变量切换及维护变量，错误类型不允许加入。
3. 随机整数同端点7/7应得到7，随机字符串按模式/长度返回；四舍五入-3.5得到-4，距离(0,0)-(3,4)得到5。
4. 屏幕宽高写入不同整数变量；单调时钟用于耗时，Unix时间用于时间戳；查看输出日志，不混作同一时钟。
5. 关闭未保存配置确认放弃，不插入新节点；保存、撤销和源码往返保留全部参数。

## 未交付

日期格式/运算，文本/中文输入、剪贴板和App操作仍待实现。数据/文件、界面增强、图像/OCR与运行控制后续批次仍未完成；不把本批说成五阶段或旧函数库100%对齐。
