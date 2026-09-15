# Runner 发布打包

## 边界

发布分为两个可复验阶段：

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
cargo run -p release-packager -- prepare `
  examples/hello-project `
  build/releases/hello-runner `
  com.autoscript.hello `
  1 `
  1.0.0

.\gradlew.bat :apps:runner-template-android:assembleDebug `
  -Pautoscript.releaseDir=build/releases/hello-runner

cargo run -p package-probe -- `
  --require-embedded-release `
  apps/runner-template-android/build/outputs/apk/debug/runner-template-android-debug.apk
```

`release-packager`有意拒绝覆盖已有输出目录，避免旧文件混入新发布。重新发布时应使用新的空目录或由调用方显式归档旧目录。

## 签名边界

当前命令生成Android debug签名包，可用于开发安装和完整链路验证。生产密钥、远程构建、证书轮换、渠道签名与在线授权仍未接入；在这些边界落地前，工作台不会把“打包APK”显示为已完成的线上发布能力。
