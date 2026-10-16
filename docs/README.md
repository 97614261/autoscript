# 文档入口

[Studio首版总账](reference/studio-first-version-master-plan.md) · [177种积木矩阵](reference/studio-block-coverage.md) · [总计划1–3阶段](verification/studio-master-stages-1-3-2026-10-15.md) · [总计划4–6阶段](verification/studio-master-stages-4-6-2026-10-15.md) · [集中验证结果](verification/studio-master-validation-2026-10-15.md) · [集中验收待办](reference/studio-acceptance-checklist.md)

[Android剩余项修复与实际验收](verification/studio-android-followup-2026-10-15.md)：组件真实清单、快照ART缓存、最大化系统栏、OCR诊断及目标进程重启保护。

[OCR策略与模型候选评估](reference/ocr-strategy-evaluation-2026-10-15.md)：固定模型120次公开实验和候选准入，不替换生产模型。

[最后六项收口](verification/studio-final-six-closeout-2026-10-15.md)：候选模型真实对比、图像/编辑数据/设计器/异常及最终交付证据。

用户已授权继续剩余验证阶段，当前执行Studio集中回归、Lint、打包及可用设备验收，具体通过/失败/阻塞以当日记录为准；独立Runner及下载器仍暂缓，不自动执行私人脚本。`build.cmd -Verify`会测试并安装到MuMu，安装保留数据。历史验证记录不作为本轮完成率或操作授权。

工程结构：[Android工作区](../android/README.md) · [Rust工作区](../rust/README.md)。根打包入口保持兼容，Android Studio打开android/。

## 使用手册 · guides

- [项目与插件组织](guides/project-plugin-organization.md)、[项目存储](guides/project-data-storage.md)、[可视化源码](guides/visual-lua-source.md)
- [Studio项目设置](guides/studio-project-settings.md)：原始基线、冲突保留、统一保存、重新读取与清理边界。
- [截图保存](guides/image-save-redesign.md)、[脚本界面](guides/script-interface.md)、[编辑器标题栏](guides/visual-editor-header.md)
- [可视化积木编辑](guides/visual-block-editing.md)：连续操作、批量选择、共享折叠、粘贴位置与缩进安全检查。
- [Studio变量与流程](guides/studio-variables-and-flow.md)：变量作用域、循环输出、JSON遍历组合、插件调用和函数库。
- [Studio图片工作台](guides/studio-image-workbench.md)：原像素保存、二值化预览、测试有效性、整批加入与帧释放。
- [Studio调试与提示](guides/studio-debug-and-prompts.md)：草稿关闭保护、透明度、日志分通道/详情与终止清理。
- [Root录制](guides/root-input-recording.md)、[运行日志](guides/runtime-file-logs.md)、[Runner打包](guides/release-packaging.md)
- [项目随机化](guides/project-randomization.md)：项目默认策略、单积木覆盖、精确手势与等待边界。
- [脚本基础工具](guides/script-utilities.md)：计算/系统分类、类型化变量、随机/几何/颜色/时间规则。
- [脚本对话框](guides/script-interface.md#脚本对话框)：确认选择与复用设计页面的布局弹窗，见[ADR0066](adr/0066-script-confirm-and-layout-dialogs.md)。
- [旧工程转换](guides/legacy-project-conversion.md)、[离线函数参考](../docs-site/generated/api.html)

## 参考资料 · reference

积木显示与执行注释见[积木摘要、别名与注释执行](guides/block-display-and-comments.md)，数据决策见[ADR 0063](adr/0063-node-display-aliases.md)。

- [参考源码入口](reference/reference-screen-source-map.md)、[品牌与图标](reference/studio-branding.md)
- [老版函数库逐项对照](reference/function-library-parity-2026-10-11.md)：一键玩216项与易编已检查的266个静态API条目，区分已覆盖、入口缺口与首版范围外能力。
- [函数库补强执行计划](reference/function-library-development-plan.md)：五批范围、逐项状态与延期边界。
- [转换器剩余边界](reference/legacy-converter-completion-plan.md)、[旧视觉兼容](reference/legacy-vision-compat.md)
- [视觉基准方法](reference/vision-benchmark.md)、[原生依赖审查](reference/vision-native-dependency-review.md)
- [可选视觉与 Runner 长期计划](reference/optional-vision-runner-execution-plan.md)、[Rust 图像性能基线](verification/image-performance-baseline-2026-10-14.md)
- [Rust 图像二值阈值](verification/rust-image-threshold-2026-10-14.md)

## 验证记录 · verification

- [运行期输入与页面配置](verification/runtime-ui-inputs-config-2026-10-11.md)：占位/范围/步长、参数快照和密码排除、构建安装验证。

- [函数库数据与列表补强](verification/function-library-data-and-list-2026-10-11.md)：49项工具、运行期列表、真实读写与剩余五批边界。

- [基础工具第一批](verification/script-utilities-2026-10-11.md)：16个积木、真实执行回归及构建安装，明确剩余范围。

- [脚本对话框](verification/script-dialogs-2026-10-11.md)：确认结果、布局复用、可取消等待及本批门禁结果。

- [编辑窗口与控件内容修正](verification/editor-window-and-control-content-2026-10-11.md)：撤回核对、即时关闭/运行隐藏和内容尺寸适配。

- [界面设计器工作区调整](verification/ui-designer-workspace-2026-10-11.md)：页面操作移到画布顶部、移除底部占位及构建安装验证。

- [界面设计恢复弹窗修正](verification/ui-designer-recovery-dialog-2026-10-11.md)：独立操作按钮、失效记录关闭和恢复保护，测试与构建安装结果。

- [编辑操作窗改版](verification/editor-edit-panel-redesign-2026-10-11.md)：紧凑选择区、无分组文字按钮、编辑/日志全屏与构建安装验证。

- [积木别名与注释执行](verification/block-display-and-comments-2026-10-11.md)：本批实现、数据往返和构建安装验证。

- [脚本界面设计器审查与修复](verification/script-ui-designer-review-2026-10-10.md)：右侧设计器能力、保存边界及运行控件预算。

- [最新草稿恢复与质量门禁进展](verification/editor-recovery-and-quality-gates-2026-10-10.md)：列明六阶段已实现内容及未完成边界。
- [交付与验收方法](verification/native-acceptance-guide.md)
- [新增脚本控件第一批](verification/additional-script-ui-controls-2026-10-10.md)：开关与密码的实现、验证及剩余边界。
- [设备验收记录](verification/vision-device-acceptance-2026-10-10.md)、[日志与停止交付](verification/vision-followup-and-log-cleanup-2026-10-10.md)
- [历史性能证据](../archive/project-history/2026-10-10/README.md)：保留测量条件和边界，不作为当前完成率。

## 架构与维护

- [运行期界面、帧资源与脚本控制](guides/runtime-ui-frame-control.md)：背景/位置/组、连续截图借用与释放、停止和输出开关。

- [架构决策](adr)、[源码路径映射](source-layout-map.json)、[开发约束](../AGENTS.md)
- [Rust工作区](../rust/README.md)：引擎与工具源码、Cargo配置集中在rust/，Android打包入口不变。
- docs/ 不再堆叠已被取代的阶段计划。当前手册原位维护，验证记录明确日期和范围，仍有价值的历史测量保留；删除须修复引用。
- 用户资料/、截图/ 和忽略的 archive/local-evidence/ 保留，不进入代码仓库。
- [项目随机化](verification/project-randomization-2026-10-11.md)：三项默认策略、单积木覆盖、精确手势、API1.8与测试边界。
