# Runner 发布打包

2026-10-10：构建脚本仅在已生成Studio APK的ZIP布局开销超过16 MiB时，校验备份后刷新单个APK输出，避免增量ZIP膨胀；不删项目或整个build缓存。备份有界保留在build/apk-output-backups，两个ABI配置隔离。新包验证失败可从备份恢复；这不是正式签名或用户数据恢复。代码职责目录见[source-layout-map.json](../source-layout-map.json)。

Studio日常验证仍用build.cmd -Verify：Studio、共享脚本UI、项目存储单测，Lint、Debug构建后更新安装MuMu0。启动和安装子进程有超时，支持-StartupTimeoutSeconds和-InstallTimeoutSeconds（10–600，安装默认180）；超时只停止本次子调用，不卸载/清数据/停止ADB服务，也不能报告安装成功。普通build.cmd只打包并安装，不执行上述验证集。Runner发布流程如下。

## 边界

发布分为两个可复验阶段，支持主机构建与设备内构建两个出口：

1. `release-packager`从项目目录读取权威`project.json`、Lua/Flow和已登记资源，生成不可变发布目录。
2. Runner Gradle模块复验并注入该目录，生成携带独立应用ID、名称和版本的APK。

Studio草稿、`.studio`状态、旧备份和`generated`目录不会直接进入发布包。可视化项目始终从当前Flow重新编译，不能把旧的`generated/main.lua`冒充当前生成物。

## 发布目录

```text
release.json
payload/
  main.lua
  source-map.json  # 仅可视化项目
  assets/images/...
  dictionaries/...
```

`release.json`严格遵循`schema/release-schema/release.schema.json`。当前发布格式为v3；`releaseId`绑定应用ID、版本、项目元数据、按字典序规范化的`project.json.capabilities`、设计参数、Runner动态配置定义、Lua/source map摘要以及有序资源清单。能力清单最多64项，每项最多128字节，篡改能力、配置定义、source map或顺序都会使发布物失效。工具拒绝路径穿越、符号链接、额外文件、缺失文件、摘要变化、未知字段和超过512 MiB的载荷。

可选`project.json.runnerUi`声明最多32个文本、整数、布尔或单选字段。Studio“项目设置”提供经过同一清单校验器的严格JSON编辑入口，空白保存用于删除表单；保存会执行清单冲突检测、使旧生成代次失效并原子更新`project.json`。Runner使用传统Android View渲染配置，按`releaseId`隔离保存用户值，并在每次启动前生成单行受控边界前缀`RunnerConfig`。`RunnerConfig`是脚本可读写的普通Lua table，并非安全存储；安全保证是用户值不回写不可变发布包，也不参与`releaseId`，而配置定义本身参与摘要。可视化发布必须携带同次编译的source map，发布复验及Runner加载都会核对其`generationId`与生成Lua头，运行错误会扣除配置前缀行并定位到`flowId/nodeId`。

Runner启动时会再次校验并把同一能力白名单通过AIDL/JNI交给Rust。所有会产生宿主交互或资源访问的Lua API在执行器分派前按API契约检查能力；未声明调用以`CAPABILITY_DENIED`失败，不能到达Root、截图或资源后端。纯同步且无宿主副作用的计算函数不参与宿主分派。

## 本地可安装构建

以下命令使用仓库内示例项目生成debug签名APK：

```powershell
Push-Location .\rust
cargo run -p release-packager -- prepare `
  ../examples/hello-project `
  ../build/releases/hello-runner `
  com.autoscript.hello `
  1 `
  1.0.0
Pop-Location

.\gradlew.bat :apps:runner-template-android:assembleDebug `
  -Pautoscript.releaseDir=build/releases/hello-runner

Push-Location .\rust
cargo run -p package-probe -- `
  --require-embedded-release `
  ../android/apps/runner-template-android/build/outputs/apk/debug/runner-template-android-debug.apk
Pop-Location
```

`release-packager`有意拒绝覆盖已有输出目录，避免旧文件混入新发布。重新发布时应使用新的空目录或由调用方显式归档旧目录。

## 签名边界

上面的主机命令生成Android debug签名包，用于开发安装和完整链路验证。设备打包使用设备独有的AndroidKeyStore密钥，不会沿用主机debug证书；已有同包名Runner的证书不同会明确提示不能原位升级，Studio不自动卸载或清理数据。

## Studio设备端打包

项目工作台“打包应用”入口执行真实构建，可取消，最多120秒；成功后可导出APK到系统文件选择器，或通过只读FileProvider分享。只有冻结资源、重建ZIP、生成签名及复验都成功后，才提交私有目录中的唯一APK文件。失败/取消清理本次暂存文件，已有APK不覆盖。失败的文件选择器导出会尝试删除本次新建的未完成文档，私有目录中的完整APK仍保留。

Studio构建时将独立Runner的`assembleRelease`未签名APK和SHA-256元数据放入生成assets。Runner仍不依赖Studio编译器/项目仓库/编辑器；设备端通过Studio-only编译JNI复用同一`release-packager`。项目清单及加载的Lua/Flow源码必须与已保存文件一致，冻结期间清单变更会拒绝构建。仅登记的资源进入发布物，不携带草稿或额外项目文件。

当前模板属性明确锁定：包名`com.zhigou.yunxingban`、版本代码1、版本名`0.1.0`、桌面名称“自动化Runner”及Android默认图标。脚本项目名和脚本界面由冻结发布清单提供。没有实现任意二进制manifest重写，也没有伪可编辑的自定义包名/图标。不同项目共享同一个Runner包名，系统安装会替换上一次脚本；同设备Studio升级保留私有密钥，卸载Studio或清除其数据会丢失密钥，旧Runner便不能用新证书原位升级。

设备构建使用[Android APK Signature Scheme v2规范](https://source.android.com/docs/security/features/apksigning/v2)的RSA PKCS#1/SHA-256算法和1MiB分块摘要，兼容minSdk24；密钥使用[AndroidKeyStore](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec)，私钥不可导出。APK内部原生库STORE并16KB对齐，资源表STORE并4字节对齐。模板最多512MiB、最终APK最多1GiB，原生库仍采用仓库的开发构建配置，包体尚未做发布体积优化，设备需预留约三份APK的暂存空间。

`DeviceApkPackagingTest`使用真实模板和示例发布，调用Android SDK的独立`apksigner`验签、`aapt2`核对包名/版本/minSdk、`package-probe`核对ABI及嵌入发布，另外验证内容篡改拒绝、同签名身份连续构建、提交前取消和禁止覆盖。成功的主机测试产物保存在`build/device-packaging-verification/`；测试密钥仅在系统临时目录生成并销毁。主机验签不等于设备AndroidKeyStore、文件导出、安装/升级以及脚本运行已实测，设备验收状态需独立记录。

没有新增生产签名依赖，主机验签只使用已有Android SDK工具。远程构建、云发布、证书轮换、渠道签名、代码加密、外部Android扩展插件APK与在线授权保持未开放。

## 2026-10-03打包符号裁减

ADR0036仅裁减模块build目录中的Rust打包副本，Cargo原件不动。Studio Debug由约575MB降至301MB（十进制），Runner模板由242MB降至146MB；OCR模型约7.7MB且哈希不变。OpenCV、ONNX、arm64-v8a/x86_64及设备打包能力均保留。仍是开发配置，不等于完成发布版R8/ABI分发/原生库定制优化；暂存空间预算继续按实际模板大小计算。

日常`build.cmd -Verify`现在包含Studio、script-ui、project-store、runtime-client、runtime-service单测与Lint，成功后使用`adb install --no-streaming -r -t`更新MuMu选定实例。安装限时且保留数据，不自动卸载；本轮MuMu0号更新成功。可执行`scripts/verify-native-packaging.ps1`核对实际APK的ELF、模型和嵌入模板。

完整原生公开示例在`examples/native-workflow-project`，运行`scripts/build-native-example.py`生成忽略目录下的可导入ZIP。新增Runner主机测试使用该示例冻结资源，独立SDK验签并核对ABI/对齐，不含Flow/编辑器源码；测试签名APK不作为生产签名或设备实测结论。

## 2026-10-13独立Runner边界

默认Studio不再内嵌完整Runner模板；未安装打包组件时不能生成独立APK，界面明确说明下载尚未开放，不会自动联网。编辑与正式运行的现有宿主继续保留。独立模板本地库存、选择及OpenCV/ONNX可选产物正在按[执行计划](../reference/optional-vision-runner-execution-plan.md)实现，不能把本轮移除内嵌模板当作整套按需组件已完成。

开发者确实需要旧离线模板链时，可在原始Gradle命令显式传`-Pautoscript.embedRunnerTemplate=true`；这种候选须调用原生检查脚本的`-EmbeddedRunner`参数。正常`build.cmd`使用默认不内嵌模板并检查其确实缺席。手机命令仍须保留既有arm64属性和绝对项目缓存目录，不因这个开关混用ABI产物。

本轮实测包体与验证见[第一步记录](../verification/optional-runner-boundary-2026-10-13.md)。旧节中的内嵌模板和包体数字仅是当时的历史状态。
