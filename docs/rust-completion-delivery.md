# Rust 完善阶段交付（2026-09-29）

## 结果，不是全功能完成声明

本文件前一批验证记录保留；后续批次已实现灰度找图、字母数字OCR与当前Daemon独立停止通道，见 [native-vision-stop-delivery.md](native-vision-stop-delivery.md)。P3参考“图像数据”协议仍未知，设备验证仍未执行。

完整长计划与逐项状态见 [rust-completion-plan.md](rust-completion-plan.md)，新增决策见 ADR0012/0013/0014，第三方核对见 [vision-native-dependency-review.md](vision-native-dependency-review.md)。未删除用户数据，未提交/覆盖用户现有未提交工作，未操作设备。

## 已落地链路

- runtimeApi1.7/找图节点v2、契约快照/stub/HTML/schema/迁移同步；旧1.5/1.6与默认八参数识别仍保留。
- 找图五方向、固定/具名变量ROI、单模板/声明图片列表/文件夹，最多64模板和共享预算/取消/截止；返回首个匹配的模板/得分/位置，不偷偷改成最高分。
- 独立裁剪帧、不透明句柄、任务所有权与预算；原帧释放后裁剪仍可用。
- 按访问频次识别；自动截图不在跳过轮次执行；五种找到后动作、屏幕像素偏移与时长串到真实输入。
- 找图是否找到支持声明整型/数值1/0，旧隐式boolean及布尔插件参数兼容；输入/结果变量类型、别名、模板列表编辑保留和非法值回归。
- Task.spawn/cancel、Timer.every/cancel真实Lua生产接口与四个可视化积木；同VM有界回调登记、参数冻结、非重入、合并漏tick、父任务结束取消和异常传播。
- 有健康单指后端时，滑动一个连续事务、最多200段、精确时长、i64插值、控制通知等待、段间取消；API24 Root桥使用固定事件白名单，EOF/异常清理。
- 系统input/screencap子进程有截止；等待保留未回收PID，加入watchdog后才回收；超时输入不重试。
- Studio编译器从共享引擎移到专属JNI库；Runner不打包该库。编译与草稿校验仍走原runtime-api/service路径，Runner编译请求明确报告不可用而不调用缺失native方法。

全局标量可以由任务共享，屏幕帧/裁剪仍绑定创建任务，不能把父任务帧直接给子任务复用；回调应自行截图。子任务结束时其图像租约会清理，不以全局保存句柄延长图像生命周期。

## 主机验证与构件

- `cargo fmt --all --check`、`cargo test --workspace --offline --quiet`：226项通过。
- `cargo clippy --workspace --all-targets --offline --quiet`退出0，但仍有警告（现有复杂度/转换与部分新增测试/接口风格）；没有声称通过`-D warnings`。
- `cargo run -p api-codegen --offline --quiet -- check`、`git diff --check`通过；Git仅提示部分文件的LF/CRLF转换。
- Studio154项、project-store33项、runtime-service4项JUnit全部通过，共191项，0失败/错误/跳过。
- Studio lintDebug：0错误，23警告、19提示。两端Debug构建成功，arm64-v8a及x86_64（API24链接目标）。
- Cargo normal dependency tree确认engine-jni不含flow-compiler/flow-ir/studio-compiler；APK确认专属编译库只在Studio；arm64动态符号确认共享引擎不再导出可视化编译/草稿校验，专属库有对应符号。

产物位置：

- `apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk`
- `apps/runner-template-android/build/outputs/apk/debug/runner-template-android-debug.apk`

原打包命令不变：`./gradlew.bat :apps:studio-android:assembleDebug`。Gradle缓存锁等待是并发Cargo构建串行化提示，不等同编译失败。

## 尚未完成及后续步骤

1. 参考“图像数据”：需要可靠返回类型定义/可反编译代码，或明确接受本项目“匹配区域图像”作为替代，不能直接勾选等价。
2. 旧Daemon无优先协商位仍有旧限制；当前Daemon独立通道已实现中途终止原生input并尝试UP，不支持释放时明确失败。实际厂商触点行为待测。
3. OpenCV灰度找图已接通；版本/STL/API24/16KB页设备兼容待验证。
4. ONNX固定英文模型与字母数字解码已接通；指定单行ROI，不含检测/中文/旋转，实际截图效果待测。
5. 设备兼容与性能：按用户要求未测。主机单测不能证明厂商Root权限/反射输入桥、实际捕获格式、叠加窗穿透或触摸时序。

## 用户设备验收建议

- 在已有插件修改找图：方向/变量ROI、相似度/容差、文件夹与输出保存后重开；确认没有丢草稿。
- 用不同模板验证首个命中顺序；无匹配清空结果；声明整型找到变量得到1/0。
- 循环7次、频次3：只有第1/4/7轮截图识别；跳过轮次不点上一次位置。
- 点按、按住、停顿、定时弹起和长滑动中停止，确认触点释放；桥不可用降级不算该停止验收通过。
- 定时器回调有等待时不重入；取消/停止后不再输出，重复运行没有资源残留。
- Studio编辑与打包可用；Runner仅加载冻结产物，不出现编辑器或编译流程。
- 性能另记录设备/分辨率/ROI/模板尺寸与P50/P95/P99，不把Debug包测试当发布性能结论。
