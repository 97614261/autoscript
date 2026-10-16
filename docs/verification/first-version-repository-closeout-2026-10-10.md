# 首版代码收口与仓库整理（2026-10-10）

用户授权六项代码完善和目录整理；暂停设备测试。本轮不安装APK、不运行私人脚本，不声称整体产品100%。

下面目录整理为本批完成时的历史记录；随后用户要求实际删除退休文件，最新目录与恢复范围见[清理记录](repository-cleanup-2026-10-10.md)，不再保留全部旧UI与阶段计划。

随后Rust工作区及缓存另按[ADR0056](../adr/0056-contained-rust-workspace.md)集中至rust/；下面“未移Cargo配置”的描述仅指本次较早批次。

## 六项实现

1. 数据恢复：只读列表不清理事务文件；清单读取受预算限制。插件缺失仅恢复已提交previous副本，保留来源、核对根和积木身份、撤销旧生成物。存在未提交tmp、只有bak或损坏现有文件时拒绝猜测。运行API提升与插件源码使用同一回滚日志。内存未保存草稿仍不能在强杀后保证恢复。
2. 状态一致：编辑器、首页、运行环境、权限页和控制台共享文字口径；停止清理不称空闲，断连旧READY不称权限已就绪。编辑器显示未保存插件数量及保存中状态。未更改Root鉴权或停止控制。
3. 图像参数：已保存图片识别测试和回填共用有界不可变配置，明确像素/灰度的容差、阈值、方向及半开范围；灰度不借用像素容差。异常及取消释放测试忙状态。没有新增算法或设备提速结论。
4. 弹窗：公共面板和确认窗受实际可用高度约束，键盘空间不靠固定屏幕高度推断；普通字号保持紧凑，大字号扩大控制高度。图像公共输入/选择控件同步适配；未声称每个自定义弹窗均已视觉验收。
5. 维护：编辑器拆出积木树投影、旧格式适配和参数弹窗，原包名、函数身份及调用保留。活跃Studio源码与测试分九个职责目录，移动前后SHA-256一致。保留活跃Legacy适配和历史Lua数据。新增文档链接/归档构建检查并接入CI。
6. 包体与交付：APK组成报告区分未压缩条目、ZIP压缩载荷和头部/签名/对齐开销，单列内嵌Runner，拒绝重复ZIP条目。构建前检查已生成Studio APK的异常布局开销，超过16 MiB先校验备份，再只移除这个APK输出，让Gradle完整重建；development/arm64隔离，最多保留每配置3份旧APK备份，不删除缓存目录或项目。SkipBuild不执行该步骤。无效ZIP或重定向路径明确拒绝，备份在build/apk-output-backups。没有删模型、必要JNI导出或切ABI冒充体积优化。

## 目录整理

- 44份已被后续记录替代的阶段文档移至 archive/project-history/2026-10-10/docs，增加历史标记，并修正本地Markdown相对链接；原README也保留。
- 8张根目录历史布局/截图移至 archive/local-evidence/2026-10-10，逐张校验SHA-256，不进入Git。
- 删除scripts/__pycache__中3份可再生Python3.8字节码缓存，空缓存目录同时移除；可运行源码、竞品资料、用户截图输入和构建产物未删除。
- 五个无活跃引用的旧图标移到archive/studio-ui/2026-10-10/resources，保留原位置和SHA-256。已生成APK输出的刷新另按上述可恢复备份规则，不删除模块build目录。
- 186个活跃Kotlin源码/测试按editor/image/input/runtime/project/packaging/designer/navigation/ui分类，package仍是com.autoscript.studio。路径映射见source-layout-map.json；仅移动不改变字节的文件均校验SHA-256。
- 根README只放当前入口、构建与目录说明；docs/README为文档索引。未移build.cmd、gradlew、Cargo/Gradle配置、schema或各Android/Rust模块。

## 验证

- Android五个模块共660项：659通过、1项跳过、0失败；Studio477、script-ui28、project-store64、runtime-client17、runtime-service74（含1跳过）。整理后的完整门禁成功，耗时2分28秒。
- Lint 0错误、29警告、17提示，比本轮前少5条未使用资源警告；未通过baseline或屏蔽规避。Rust相关三个包169通过、1项性能基准跳过；fmt通过，Clippy退出0但保留既有警告。
- 仓库检查通过204个本地文档链接，归档未进入构建。PowerShell语法、APK输出恢复测试和构建安装脚本的模拟命令测试通过；模拟测试的“安装成功”不是实际设备安装。
- 原开发APK300,132,808字节，重建后231,118,681字节，减少69,014,127字节（约23%）。ZIP布局/签名/对齐从69,186,750降到175,141字节，压缩载荷230,943,540字节。双ABI、原生动态导出、7,653,044字节ONNX模型和内嵌Runner模板摘要均通过校验；没有减少识别能力或删除模型。
- 开发APK：apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk；旧输出有SHA-256校验备份，保存在build/apk-output-backups/development。Debug签名，不是正式发布包；本轮未生成手机候选或安装到设备。
- package-probe通过：开发包双ABI、ELF机器类型、原生库存储及16 KiB对齐、APK Signing Block结构有效；不将签名块存在冒充正式发布签名。
- git diff --check通过。没有继续模拟器或真机测试，没有自动安装。

设备布局、进程死亡/存储故障时的实际恢复、厂商Root输入、灰度/OCR准确率、独立Runner升级和正式签名仍需后续验收，不用主机通过替代。
