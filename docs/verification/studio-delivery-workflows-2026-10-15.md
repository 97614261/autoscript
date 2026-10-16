# Studio 六项交付复验（2026-10-15）

## 结论

**本批指定的六项已结束；不代表所有设备和所有参数100%。** 经用户授权重启MuMu 0恢复输入分发，并临时使用仅androidTest的QA键盘。最新生产包连续两轮完成36/36项真实设备验收，两轮完成码-1、pass=true、cleanup=true；每轮测试后原输入法均已恢复、QA键盘已停用。此前系统冻结、零高度输入法和失败轮次保留在下文，不用旧25/10结果代替本批。

## 同一六项范围

| 项目 | 本批结果 | 仍需完成 |
| --- | --- | --- |
| 1 软键盘及遮挡 | 非零键盘实体、实际InputConnection提交、焦点、关闭按钮无遮挡及`ui.get`回读通过 | 仅MuMu/API32和自有QA键盘；其他输入法/真机/API24未验收 |
| 2 编辑弹窗 | 变量新增/放弃、循环预设返回、有真实模板的图像配置/取消、源码草稿全屏/放弃均通过 | 全参数/全部人工视觉不由本批替代 |
| 3 重点积木组合 | 找图后判断点击并循环、未命中重试、真实总超时、优先停止、插件参数/返回/跳转全部通过 | 全177积木的参数笛卡尔积不在本批 |
| 4 修复及回归 | 修复宿主完成唤醒丢失、图像预览提前回收、IME遮挡与重建输入控件；对应主机及设备回归通过 | OCR歧义仍为既有准确率限制 |
| 5 构建及安装 | full生产包及测试包构建、983项主机检查、Lint及APK检查通过，保留数据安装MuMu成功 | 不是正式签名发行或ARM验收 |
| 6 完成度核对 | 新36项全流程连续两轮通过，原始失败与边界同账保留 | 本有限批次无剩余阻断；范围外项目见文末 |

## 恢复后修复与最新证据

- 唤醒故障：原pump在派发结束删除已排队唤醒，可能删除派发期间新到的native宿主完成通知，表现为RUNNING但停在`system.elapsed`。改为采样/派发前合并旧唤醒，新增4项确定性/接线回归。修复后真实截图、匹配、分支、Root点击、循环、未命中重试、总期限及停止全部执行通过。
- 预览故障：`DisposableEffect`释放时读取可变preview状态，旧effect可能把新发布缩略图回收，引发`Canvas: trying to use a recycled bitmap`。已发布有界缩略图不主动recycle，保留渲染引用交给GC；未发布/取消解码仍显式释放，源图与768px缩略预算未放宽。使用真实模板的最终图像配置用例通过。
- 键盘故障：真实QA键盘高度352px时，旧布局关闭按钮在键盘后方。API30+采用全屏WindowMetrics的IME边界并合并最新附着回调，避免小窗移开后局部overlap消失造成反复扩回；窗口在可用区域定位，视口变化复用输入控件，不重建InputConnection。实测键盘`[0,928,720,1280]`，关闭按钮`[36,805,683,869]`，`imeObserved=true`、`occlusionCoverage=checked`、`qaKeyboardCommit=true`。
- 夹具纠正：跨插件节点身份加入独立命名空间；图像专项明确声明截图/找图能力；Activity启动增加8秒等待界限；最大化过渡期间Accessibility窗口暂缺按重试而非立即异常处理。旋转最后执行，并在恢复后做真实触摸，不仅检查几何。
- 自有组合专项12/12、弹窗专项5/5、真实键盘专项5/5先分别通过。最终完整套件36/36连续两轮通过（每轮包括重连3次、方向恢复后真实触摸），原始证据`build/studio-delivery-final-full-real-ime-v2.log`、`build/studio-delivery-final-full-real-ime-v3.log`。第二轮重新启动目标并创建新工程，不复用上一轮执行结果。测试仅操作当前新建合成工程，不清理历史失败轮项目或私人项目。
- 共享原生View套件10/10通过，但该独立测试进程与QA输入法同包，Instrumentation启动会重建输入法，实际IME没有出现：`imeObserved=false`、`requestAccepted=false`、`occlusionCoverage=not_observed`。这10项只证明控件/输入连接及尺寸恢复，**不能算真实键盘遮挡通过**；遮挡证据来自上述运行服务真实布局窗专项和完整套件。此前单用`isVisible`推断键盘的口径已修正为非零边界。
- 最终主机门禁3分17秒/916任务通过：Studio755、client31、service147、script-ui50，合计983项、981通过、0失败/错误、2既有跳过；Lint Fatal/Error=0。结果见`build/studio-delivery-final-source-gate.log`，不把前序Rust或其他配置结果冒称本次新跑。
- 最新生产APK177264221字节，SHA-256 `6D92A6ADF90BC5D41D054C3B11B7AA527FD345CB69BD850FC1A0A1C902E5044F`；压缩载荷173009396，其他布局/签名/填充4254825字节。双ABI原生剥离及动态符号、原ONNX摘要检查通过，无Runner/候选模型，生产Manifest/dex不含QA键盘。仅测试APK声明BIND_INPUT_METHOD；临时启用/恢复由有finally的独立脚本执行并复核。
- 最后一次夹具调整后的Lint再次执行42秒/540任务通过（3执行、537最新）；结构化结果解析/进程保护、PowerShell解析、519本地链接和显式20文件空白/冲突检查通过。最终只读设备复核`DispatchEnabled: true`、`DispatchFrozen: false`，原Sogou输入法为当前默认且QA组件不在启用列表。未清应用数据、未运行私人脚本、未提交Git。有限六项无剩余阻断。

## 历史观测与失败（恢复前，不代表最新状态）

- 显式设备：`127.0.0.1:16384`，MuMu 0 / API32，起始分辨率 720×1280；只创建当前轮所属的 `project-qa-*` 工程和公开合成资源。
- 键盘专项最后一轮：变量计算、三次循环/索引、判断、标记跳转、确认及布局输入回读均通过；确认取消、布局等待中停止、确认等待中停止、两种弹窗超时返回也通过。五个记录中四个通过，单独的实体键盘记录失败，不能将总结果当作通过。
- 输入法元数据曾报告 `mInputShown=true`、`isVisible=true`，但其 `mFrame=[0,1280][720,1280]` 高度为零，Accessibility 也未给出有效 IME 矩形。因此先前“已显示”的系统状态不足以证明键盘实体或遮挡覆盖。未切换输入法、未更改显示软键盘设置。
- 第一轮弹窗专项在恢复未指定方向时超时。夹具现复用既有 `restoredScreenOrientation`（UNSPECIFIED→USER），不修改系统旋转设置。第二轮旋转、函数库关闭、会话与源码保留检查通过，但下一次日志按钮真实触摸被系统拒绝。
- 系统记录为 `Window freeze timeout expired` / `App freeze timeout expired`；只读输入状态为 `DispatchEnabled=true`、`DispatchFrozen=true`。稍后复查及仅重启智构 App 后仍冻结，不把它误记为日志按钮业务失败。
- 两轮弹窗专项均报告 `cleanup=true`；只清理当轮自己持有的项目与运行。不扫描旧 QA 或用户工程，不清应用数据。后续将旋转边界用例放到最后，新增固定阶段状态便于定位，不降低失败门禁。
- 当时已请求重启授权，未答复前未重启；随后用户明确允许，本批仅重启MuMu 0一次，不清数据。

本地原始证据（均为忽略的产物，不入项目备份）：`build/studio-delivery-dialogs-first.log`、`build/studio-delivery-dialogs-second.log`、`build/studio-delivery-final-gate.log`、`build/studio-delivery-apk-audit.log`、`build/studio-delivery-workflow-build.log`。键盘专项的结果由当前会话工具输出保留，未伪造本地日志文件。

## 历史主机门禁与候选（已被上方新包取代）

- 隔离 full Gradle 门禁：4分19秒，1010任务，55执行/955最新，`BUILD SUCCESSFUL`；包含 Studio、runtime-service、script-ui 单测、Studio Lint、生产包及两种测试包。
- XML统计：Studio 754/0失败/0错误/1既有跳过；runtime-service 142/0/0/1；script-ui 48/0/0/0。合计944项、942通过、2既有跳过。Lint Fatal/Error=0。
- 旋转夹具次序及固定阶段状态更新后，Studio androidTest 单独重编29秒/323任务通过；该最新版夹具尚未重新安装或运行，不冒用此前结果。
- 生产 APK：`build/profiles/components/development/full/apps/studio-android/outputs/apk/debug/studio-android-debug.apk`，177264891字节，SHA-256 `FE013B0C0B210E80044ECFAFB418F7106A39AD3F61B078277FA54D792CECD5A0`。
- 压缩载荷173008732字节，其他布局/签名/填充4256159字节，未超过16MiB布局上限；arm64-v8a / x86_64 原生符号及剥离检查通过。原7653044字节ONNX及固定摘要不变，无候选模型、无内嵌Runner。
- 结构化设备结果解析/进程保护回归通过；更新记录后的仓库布局519个本地链接通过，显式改动文件空白检查及PowerShell解析通过。没有提交Git、删除旧包或用户文件。

## 当时拟定的恢复顺序（现已执行）

1. 只读确认输入分发恢复，保留数据安装最新测试夹具。
2. 先执行不含旋转的弹窗与五项重点组合，再执行旋转边界；若系统仍冻结，保留证据并明确报阻断，不用语义点击替代真实触摸冒称通过。
3. 复验实体键盘；仅在确实出现非零IME边界时检查遮挡，未观察到仍保留未验收状态。
4. 重跑主流程与共享View回归后，才更新本表和首版总账为实际结果。

本批不代表ARM真机/API24、177种积木全参数边界、权限撤销与系统强制回收均通过；OCR大小写/数字歧义未解决，生产模型未替换。延期的独立Runner、下载器、模板库、AI/云功能不扩展。
