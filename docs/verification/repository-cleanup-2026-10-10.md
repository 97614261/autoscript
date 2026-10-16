# 无用文件清理 · 2026-10-10

用户明确要求实际删除无用文件和文档。本批仅删除已核对用途的退休文件，不按名称清理仍活跃代码，不删除私人脚本、参考资料或设备证据。

## 实际范围

- 删除36份过期/重复文档：33份阶段计划及重复交付/缺口记录、旧README副本、旧M27验收清单和重复项目交接文档。
- 删除 archive/studio-ui/2026-10-02、2026-10-04、2026-10-10 三批退休归档，共46个文件：无入口UI片段、独立Lua编辑页及专属测试、32个废弃drawable、未用预览文案和归档元数据。
- 合计82个文件。均为退休副本或重复文档；活跃参数适配、Lua引擎、历史工程读写和备份兼容保留。
- 保留11份历史性能/异常调查证据，见 [历史索引](../../archive/project-history/2026-10-10/README.md)。不把历史数字当当前完成率。
- 当前21份专项文档按 guides / reference / verification 分类；ADR与源码映射保持原位置。接手说明收敛为当前边界，不再附过时Git现场和阶段指令。

## 保留与限制

当前手册、全部ADR、契约、许可证、模型、JNI、构建和测试脚本、用户资料/与截图/、本地性能证据均保留。
灰度/OCR准确率、厂商Root输入及触点释放、API24/16KiB页兼容、设备布局和正式发布仍需验收；未保存内存草稿不承诺强杀恢复。旧工程未知Native算法/返回状态/控件单位仍须证据，不因删除旧计划而视为完成。
本批不继续设备测试或安装，不执行私人脚本。

## 恢复

删除前按原始相对路径制作ZIP，全部82个条目逐文件SHA-256校验一致：
`build/cleanup-backups/retired-files-20261010-e7b5636ecb724515a032b28eb07d9e0b.zip`（本地忽略文件，不提交、不打入APK）。

ZIP仅包含上述退休文件，可解压到独立临时目录找回。恢复页面仍须核对当前契约和入口，不能直接覆盖活跃源码。未提交文件不保证能靠Git恢复，应保留此备份至确认清理无误。

## 被删除的文档

- `archive/project-history/2026-10-10/docs/cross-app-editor-delivery.md`
- `archive/project-history/2026-10-10/docs/debug-page-delivery.md`
- `archive/project-history/2026-10-10/docs/designer-return-orientation.md`
- `archive/project-history/2026-10-10/docs/editor-bottom-actions-delivery.md`
- `archive/project-history/2026-10-10/docs/editor-input-image-permissions.md`
- `archive/project-history/2026-10-10/docs/editor-panels-unification.md`
- `archive/project-history/2026-10-10/docs/editor-toolbar-redesign.md`
- `archive/project-history/2026-10-10/docs/first-version-code-closeout.md`
- `archive/project-history/2026-10-10/docs/function-library-delivery.md`
- `archive/project-history/2026-10-10/docs/image-recognition-gap.md`
- `archive/project-history/2026-10-10/docs/image-workflow-redesign.md`
- `archive/project-history/2026-10-10/docs/input-runtime-gap.md`
- `archive/project-history/2026-10-10/docs/input-ui-workflow-audit.md`
- `archive/project-history/2026-10-10/docs/jump-panel-delivery.md`
- `archive/project-history/2026-10-10/docs/loop-alignment-delivery.md`
- `archive/project-history/2026-10-10/docs/m27-page-alignment.md`
- `archive/project-history/2026-10-10/docs/m27-ui-plan.md`
- `archive/project-history/2026-10-10/docs/native-capability-delivery.md`
- `archive/project-history/2026-10-10/docs/native-completion-1-6.md`
- `archive/project-history/2026-10-10/docs/native-delivery-followup-1-4.md`
- `archive/project-history/2026-10-10/docs/native-finishing-1-4.md`
- `archive/project-history/2026-10-10/docs/native-vision-stop-delivery.md`
- `archive/project-history/2026-10-10/docs/project-progress-2026-10-04.md`
- `archive/project-history/2026-10-10/docs/root-input-readiness-fix.md`
- `archive/project-history/2026-10-10/docs/runtime-ui-vision-hardening-plan.md`
- `archive/project-history/2026-10-10/docs/rust-completion-delivery.md`
- `archive/project-history/2026-10-10/docs/rust-completion-plan.md`
- `archive/project-history/2026-10-10/docs/studio-completion-1-5.md`
- `archive/project-history/2026-10-10/docs/studio-home-profile-redesign.md`
- `archive/project-history/2026-10-10/docs/studio-legacy-ui-audit.md`
- `archive/project-history/2026-10-10/docs/variable-calculation-delivery.md`
- `archive/project-history/2026-10-10/docs/variable-page-compact-delivery.md`
- `archive/project-history/2026-10-10/docs/variable-page-loop-audit.md`
- `archive/project-history/2026-10-10/README-before-cleanup.md`
- `docs/m27-manual-acceptance.md`
- `项目交接与后续计划.md`

## 验证

- 本地Studio单元测试477项全部通过，含3项始终执行的退休生产边界检查及源码路径映射检查；不依赖已删除的归档清单，不因归档不存在而跳过。
- Lint 0错误、29警告、17提示，保留现有告警；Debug构建成功，耗时1分45秒。生产代码与APK任务无需重新编译的部分为UP-TO-DATE，不把归档删除当作包体进一步优化。
- 文档检查91个本地链接通过，归档未进入构建；git diff --check通过。备份82个条目SHA-256一致，删除前再次核对原文件未改变。
- 没有安装、继续设备测试、执行私人脚本或提交Git。本批未重新运行Rust或手机候选门禁。

## 第二轮复查

再次检查Git范围内764个文本文件的文档路径、脚本和测试引用；全仓库97份Markdown（包含独立工具、公开示例和docs-site）没有断链。修正开发约束里的旧归档状态，以及ADR0016和转换指南中的两处已删除阶段文档引用。历史性能报告中标注的“原位置”和删除清单属于有意保留的追溯信息，不作为当前文件入口。

没有发现新的已确认无用资源，不追加删除文件。M26/M27脚本是显式设备测试/旧基线排查工具，不是当前验收入口，不能因暂不测试而认定无用；未运行这些脚本。活跃模块、源码路径映射及恢复备份继续核对，用户工程和资料保持不变。
