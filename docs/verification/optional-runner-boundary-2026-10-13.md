# 独立 Runner 边界第一步验证

本记录对应ADR0094和[长期执行清单](../reference/optional-vision-runner-execution-plan.md)，不是全部可选组件已交付的证明。

## 已落地

- 默认Studio不内嵌Runner APK/元数据，普通preBuild不依赖Runner组装。打包组件缺失时UI和控制器都明确阻断，不联网、不冻结/签名不完整项目。
- 显式开发选项`autoscript.embedRunnerTemplate=true`保留原离线模板链；该选项不是下载或组件安装。主机打包测试仍组装并使用真实Runner，未把测试模板带回Studio资源。
- 项目声明生成基础/OpenCV/ONNX/完整四类的需求计划，显示“声明需求”而非“已安装”。没有改写旧项目能力，也没有实际生成四类可选组件。
- 体积报告将OpenCV、ONNX Runtime按ABI区分，模型和其他原生库分开；原始条目与ZIP载荷口径分开，外层Runner模板不重复展开计数。

## 最终验证

`build.cmd -Verify -MuMuManagerPath D:\soft\MuMuPlayer\nx_main\MuMuManager.exe`成功，包含本地测试、Lint、Debug构建、原生打包检查、仓库布局检查及保留数据安装到MuMu0（127.0.0.1:16384）。未运行私人脚本或设备点击验收。

| 模块 | 测试 | 失败/错误 | 跳过 |
|---|---:|---:|---:|
| Studio | 633 | 0 | 0 |
| RuntimeClient | 28 | 0 | 0 |
| RuntimeService | 105 | 0 | 1（既有） |
| ScriptUI | 47 | 0 | 0 |
| ProjectStore | 76 | 0 | 0 |

Lint为0错误、32警告、16提示；仓库检查186本地链接、0本地资料专用链接、22Rust成员；git diff --check通过。新增需求计划3项、分类报告1项测试通过。

最终开发APK为172,258,208字节；压缩载荷172,104,729字节，ZIP头/签名/对齐153,479字节。包含arm64-v8a和x86_64；确认无`assets/packaging/runner-template.apk`或其元数据。原生动态导出与调试符号裁减通过校验，OCR模型哈希不变。

此前303.6MB候选含约57MiB增量空洞，不能拿它当健康包基准宣称全部为依赖裁减收益。用户批准的最旧生成APK备份已在重新审核成功后删除；当前超标候选先核对摘要并备份再重建，没有删除项目、图片或脚本。

## 仍未完成

- OpenCV/ONNX本轮仍编入APK；ONNX仍依赖OpenCV预处理，不宣称已按需启用。
- 独立模板本地组件库存/导入、四类实际构建产物、兼容性校验与选择尚未实现。
- 组件下载明确延期。缺组件页面是实际不可用状态，不是已完成安装能力。
- 新图像工作台、额外Rust处理及设备性能/生命周期验收按长期计划继续。
