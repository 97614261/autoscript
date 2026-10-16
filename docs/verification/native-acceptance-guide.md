# 新版交付/验收操作

## 构建和包的用途

仓库 PowerShell：

```powershell
.\build.cmd -Verify
```

日常双 ABI Debug 开发包，测试和 Lint 成功后更新 MuMu **0 号实例**，保留智构的数据和应用 ID；不自动卸载、清数据或运行任何脚本。APK：`android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk`。安装失败只代表安装未确认，不等于构建失败；先看输出中的失败阶段。

```powershell
.\build-mobile.cmd -Verify
```

独立 arm64 手机候选包：`build/profiles/arm64/apps/studio-android/outputs/apk/debug/studio-android-debug.apk`，无 x86_64；两配置输出及任务缓存隔离。当前Studio默认保留full视觉、OpenCV/ONNX/OCR模型，不默认内嵌Runner；独立Runner工作仍延期。此命令**不安装到 MuMu**，用既有Debug签名，不声称生产发布包。实际发布需用户提供受保护的发布签名策略，不能把密钥放进仓库。选择有arm64的设备手动更新，不要卸载换签名以绕过错误。

原始 CI 等价：`gradlew.bat :apps:studio-android:assembleDebug -Pautoscript.abiProfile=arm64 --project-cache-dir (Join-Path (Get-Location) 'build/profiles/arm64/.gradle')`（从仓库根执行）。未知profile或手机配置缺少隔离缓存参数会配置失败：仅改输出目录时Gradle会清理上一配置的过期输出，任务历史必须也隔离。`scripts/verify-native-packaging.ps1 -Profile arm64`校验ABI、JNI动态符号、模型原件及默认不内嵌Runner；仅显式离线嵌入场景才使用`-EmbeddedRunner`要求匹配模板。默认profile校验双ABI。报告在对应build目录。

报告同时列出 APK 物理大小与 ZIP 条目压缩总量；增量开发 APK 的布局/填充空间可能不同于手机配置的首次构建，不将物理体积差全部归为 ABI 裁减。当前优化没有删除模型或功能。

## 编辑/运行验收（用户操作，不代跑私人脚本）

先备份真实工程，用 `build/native-example/native-workflow.zip` 公开工程做以下检查：

1. 点编辑立即打开弹窗；设置在左，右侧最小化为灵动环球，展开保留草稿；点标题选择插件。关闭/最小化不是删除工程。
2. 变量、循环、跳转固定值/变量按钮、共享底部按钮在设备实际字体缩放下居中。函数库列表点名称看详情、点加入配置；换分类/搜索从首项显示，详情返回不丢位置。大字体/横屏内容可滚动，底部确认不被盖住。
3. 识别加入→修改回显→保存→重进；选择变量按当前插件作用域/类型筛选；未声明能力必须明确拒绝，不自动授权。快速换截图不会出现上一张图的尺寸或结果。
4. 编辑器运行保存并直接执行默认参数，不能再套一层配置；项目列表启动先展示设计界面，界面的运行校验后立即隐藏，再执行。独立 Runner 同样先配置，无界面直接运行。
5. 连续点击运行只允许一个启动；Root 未就绪可取消/超时；失败后重试可用。运行浮球展开暂停/继续/停止，不显示相机，主动截图模式才出现相机。
6. 单步到下个真实检查点；停止/重跑后旧位置与变量不能恢复。长按/滑动/按下后的停止释放触点（用公开测试点位，不操作私人业务）。运行提示与应用开发日志分开；短提示到时消失，替换/点击关闭后旧定时回调不能关掉新提示。

上轮已有真实编译器/Lua 主机、暂停取消输入清理、Root 启动等待、备份恢复和独立 Runner 验签回归；这些不是 Android 真机全流程验收。请记录设备、Android、分辨率、字体缩放及具体步骤/截图，不能只用“100%完成”代替验收。

## 公开识别设备验收（可选，由用户执行）

单独测试APK生成图案，不读用户工程、截图、私人脚本，不需Root，不注入业务点击、不覆盖智构。共享控件测试只在测试包自建Activity里操作真实View；文件隔离只操作自建私有临时文件。没有新测试框架依赖。

```powershell
.\gradlew.bat :android:runtime-service:assembleDebugAndroidTest
# 以下 adb 换成 SDK adb 的完整路径，serial 换成明确的设备。
adb -s 127.0.0.1:16384 install --no-streaming -r -t android/modules/runtime-service/build/outputs/apk/androidTest/debug/runtime-service-debug-androidTest.apk
adb -s 127.0.0.1:16384 shell am instrument -w com.autoscript.vision.acceptance/com.autoscript.runtime.service.VisionAcceptanceInstrumentation
adb -s 127.0.0.1:16384 shell run-as com.autoscript.vision.acceptance cat files/vision-acceptance/report.json
```

这不是 `connectedAndroidTest` 的 JUnit runner：用 Android 原生 Instrumentation 显式运行，测试入口仅存在 androidTest APK。测试有 90 秒套件检查（单次适配器最大 5 秒）；不自动启用或在每日构建里运行。报告仅含公开生成的文本/图案、设备参数，不含私人日志。

已安装独立测试包后，可用显式设备与固定套件命令执行：

```powershell
.\scripts\run-studio-device-acceptance.ps1 -DeviceSerial 127.0.0.1:16384 -Suite script-ui
.\scripts\run-studio-device-acceptance.ps1 -DeviceSerial 127.0.0.1:16384 -Suite sandbox
.\scripts\run-studio-device-acceptance.ps1 -DeviceSerial 127.0.0.1:16384 -Suite preprocessing
.\scripts\run-studio-device-acceptance.ps1 -DeviceSerial 127.0.0.1:16384 -Suite vision
.\scripts\run-studio-device-acceptance.ps1 -DeviceSerial 127.0.0.1:16384 -Suite ocr-evaluation
```

脚本不安装、不自动选设备或申请权限，调用有120秒宿主上限；同时要求Instrumentation完成码-1和结构化pass=true，ADB退出0不等于测试成功。输出仅保留有界公开合成报告；结果解析回归为`device-acceptance-result-test.ps1`。`missing-components`仅适用于已明确构建/安装的base测试包，预期灰度及ONNX均返回VISION_COMPONENT_NOT_INSTALLED，不能用full测试包跑此用例或据此自动切Studio配置。

共享View套件有45秒总期限、每个主线程步骤最多5秒，覆盖各控件构造/测量/绘制、两种视口尺寸、无选中/移除/恢复、旧表单、弹出行复用、跨页更新/配置恢复、真实控件回调、禁用祖先和运行/对话框按钮通知。不会执行脚本或Root操作；这些程序断言不替代视觉检查、系统旋转、键盘及Studio悬浮会话验收。沙箱用例覆盖符号链接/目录链接/硬链接拒绝及目标未修改，结束只清理本次自建临时目录。

`ocr-evaluation`只评估现有固定模型的公开输入策略，`pass=true`指评估完成，不是准确率全对；必须同时读取`allExact`和逐项结果。原vision/ocr-diagnostics门禁不变，详见[评估记录](../reference/ocr-strategy-evaluation-2026-10-15.md)。

`ocr-candidate`需要显式以`-Pautoscript.acceptanceOcrCandidate=true`构建full/onnx的androidTest；默认false。它只接受忽略的`build/ocr-evaluation/assets/candidate/en_PP-OCRv5_rec_mobile.onnx`固定官方7872351字节及SHA-256，验证任务拒绝其他文件/内容，不实现下载器或任意模型路径。候选仅测试资产，正常测试包应去掉该开关重建；生产Studio/Runner/provider模型不改。设备期限150秒，宿主上限180秒。最新实测候选准确率更差，**不能因为评估完成或速度更快就替换生产**，见[最后六项记录](studio-final-six-closeout-2026-10-15.md)。

- 灰度：96×64 公开图、8×8 模板，ROI(10,12,86,58)，期望绝对坐标(31,23)、≥990‰；30 次热运行，并检验缺失、取消和关闭后的错误。
- OCR：等宽字体生成的大写/小写/数字/混合四行，单行宽高比≤20，逐字符门槛500‰；每组10次热运行。报告 expected/actual 和整行精确次数；不匹配会报告 pass=false，不能把“成功返回”当“正确识别”。字体和型号属于测量条件，合成文本不能代表业务识别率。
- 冷启动单列，热运行按 nearest-rank P50/P95/P99，同时标出样本数；小样本尾分位数不能用作广泛性能结论。这只测像素适配器，不含 Root 捕获、Binder、点击或全脚本延迟。
- 多模板结果顺序、绝对坐标、上限/额外截断探测槽、预算和取消在 `pixel-vision` 公开夹具及真实 Lua 执行器回归中验证；设备脚本多模板与捕获链仍需用公开工程人工验收，报告不将它算作设备实测。

## 备份、升级和独立 Runner

### 可选的真实Studio与固定Provider自动验收

`run-studio-overlay-acceptance.ps1 -DeviceSerial <明确设备>`仅调用已安装的Studio androidTest APK，创建并清理自己新建的QA项目，使用Android UiAutomation触摸实际观测到的测试控件。**Instrumentation会重启目标Studio进程**：已有进程默认拒绝；先保存/关闭用户窗口，再明确传`-ConfirmTargetRestart`。测试内部继续拒绝其他编辑、配置或活动运行；不会代点权限、停止私人脚本、清数据或重启设备。总宿主上限150秒。不要把这个命令加入日常自动打包安装链。

明确追加`-FinalClosure`扩展到20项自有工程验收：多插件可逆源码/组合草稿保存/陈旧拒绝、图片同名/原像素、全部控件保存/设计恢复、备份及空目录、实际工作台生成链执行、设计器加页撤销及方向返回、配置启动/读参/停止返回、日志/函数库全屏和客户端重连。设备期限240秒、宿主300秒；没有放宽原9项断言。它不代表177种积木所有参数、所有图像等待或权限/系统回收/键盘/视觉矩阵全覆盖；失败报告保持pass=false，清理不能证明所有权时保留对象。

外置Provider使用独立base测试包：构建`:android:runtime-service:assembleDebugAndroidTest -Pautoscript.abiProfile=development -Pautoscript.visionProfile=base -Pautoscript.acceptanceProviders=true`，并传base对应的绝对`--project-cache-dir`。该开关只把同签名固定组件加入测试资产，非base明确拒绝；正式App资产和默认full不变。手动保留数据安装测试APK后，用`run-studio-device-acceptance.ps1 -DeviceSerial <明确设备> -Suite providers`。真实导入/选择/准备/加载只在测试包私有沙箱，钉住快照保留到进程退出，下一进程按已有租约恢复规则处理，不删除活动或未知文件。扩展`-Suite provider-soak`固定20000灰度/2000OCR调用，设备期限7分钟、宿主上限8分钟，保持原图案/模型/算法并只报告采样内存，不冒充任意长期业务或历史峰值。`-Suite ocr-diagnostics`需full测试包，保留原准确率失败，不与Provider套件混用。

升级前导出备份；更新安装保留数据。公开工程：编辑插件/变量/界面→保存→导出→在新项目目录恢复→核对入口、变量作用域、图片实际目录和虚拟分组、界面绑定，再运行。不要用删除原工程或清数据作迁移。

用手机打包入口从公开工程导出 Runner，检查能力校验、冻结 Lua/资源和 V2 签名。Runner 只能显示已设计界面/执行，不应出现项目列表、编辑器或 Flow 草稿。主机打包回归产物 `native-workflow-runner.apk` 用一次性测试身份，仅验签测试，不能当正式签名发布。

旧一键玩转换工具继续独立；未确认等价的节点仍保留并阻断编译，不用空操作清零。新版这批交付不能代表旧工程兼容百分比。
