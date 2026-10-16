# Studio 第八阶段：设置与清理记录

2026-10-14。用户要求先完成Studio开发，测试与安装统一后置。本记录不声明第九阶段开发冻结或设备验收完成。

## 已处理

- 设置编辑采用原始能力/调试/随机策略共同基线，保留脏草稿，明确冲突与重新读取，继续复用存储层原子保存。
- 设置窗口按项目隔离；异步操作取消与忙状态清理；高级界面JSON未保存关闭确认。
- 系统权限入口从项目设置移除，仍归“我的”。用户提示统一插件/图像语义，不改协议字段。
- 删除 `RunnerUiDesignerDraft.shrinkAll`（8行）及仅服务它的旧测试（16行）。引用检查仅有该测试，没有生产调用；菜单入口已按用户此前决定移除。
- 删除混合 `EditorEntryDialogs.kt` 内AI模拟页面及专属侧窗/字段/附件按钮，还有无调用的 `EditorColumnPage`，共82行；清理提前返回后重复的调试尺寸/渲染分支及无用import。保留AI枚举与入口为未开放说明，不接网络、密钥或模型。
- 保留仍有调用或兼容意义的文件管理、旧参数适配、共享弹窗及路由，不删除整文件或模块；所有ADR、契约、许可证、模型和历史性能证据保留。
- 文档索引区分当前Studio开发范围和历史验证；修正README仍称默认内嵌Runner的旧描述。没有为凑清理数量删除有引用的文档。

## 恢复快照

本地忽略目录下的改前快照：

`build/cleanup-backups/studio-stage8-before-c94982efb8014b4d8517fc34e3ed7dbc.zip`

ZIP为31,163字节，包含3份完整混合文件，原位置如下：

- `android/apps/studio-android/src/main/java/com/autoscript/studio/editor/EditorEntryDialogs.kt`
- `android/apps/studio-android/src/main/java/com/autoscript/studio/designer/RunnerUiDesignerModel.kt`
- `android/apps/studio-android/src/test/java/com/autoscript/studio/designer/RunnerUiDesignerModelTest.kt`

ZIP条目使用上述文件的基本文件名。已读取条目目录，ZIP SHA-256：`84C8720AA853D3D21122D82E394DB986B9E21B8D5E2A2DAD6F7503B3A623E2DA`。恢复时先解压至独立临时目录，只取所需退役声明核对恢复，不直接覆盖持续开发中的活跃文件。此ZIP不进入Gradle/Cargo或APK，不保证能通过Git找回其中原本未跟踪的内容。

## 未做

本轮不执行单元测试、Lint、完整APK构建、安装或用户脚本；不推进独立Runner、下载器及provider设备验收，不清理未知本地产物或用户资料。

## 编译与静态检查

- 使用本地Gradle 8.14.3离线编译Studio源码及测试源码：`compileDebugKotlin`、`compileDebugUnitTestKotlin`，最终 `BUILD SUCCESSFUL in 1m 40s`；200个任务，3个执行、197个保持最新。测试源码编译成功不代表测试已执行。
- 本批修改文件的空白检查通过，包含未跟踪的Android源码与测试文件。
- 仓库布局检查通过：241个本地链接、0个local-only引用、22个Rust成员，归档未进入构建。
- 以上仅为编译与静态检查，不代表界面、并发、运行或设备验收完成；第九阶段尚未执行。
