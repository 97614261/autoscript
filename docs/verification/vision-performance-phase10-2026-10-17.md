# Vision performance phase 10 · 2026-10-17

## 设备范围

- 设备：MuMu `emulator-5554`，报告型号 `HONOR PGT-AN20`
- Android API：32
- 显示：720x1280
- 测试方式：已安装的独立合成 QA APK；不读取 Studio 项目，不运行私人脚本
- 当前生产模型和默认 `full` 视觉配置未替换

## Studio 性能套件

```text
scripts/run-studio-boundary-acceptance.ps1 -DeviceSerial emulator-5554 -Suite performance -ConfirmTargetRestart
```

9/9 通过：编辑窗展开、最大化/还原、更多菜单稳定、最小化/吸附球恢复、空闲单步、
运行高亮/暂停/继续、停止后恢复、关闭清理、Root 点击和新截图均通过，`cleanup=true`。

## 视觉链路

灰度找图和取消用例通过：

| 用例 | 结果 |
| --- | --- |
| 灰度偏移 ROI（96x64，8x8 模板） | 通过；热样本 P50/P95/P99 = 0.094/0.267/0.375 ms |
| 灰度未命中与取消 | 通过 |
| ONNX 大写 A-Z | 通过；P50/P95/P99 = 169.910/178.530/178.530 ms |
| ONNX 数字 0-9 | 通过；P50/P95/P99 = 85.973/89.950/89.950 ms |
| ONNX 混合 `AaBb09Zz` | 通过；P50/P95/P99 = 76.558/86.912/86.912 ms |
| ONNX 小写 a-z | **失败**；`l` 被识别为 `1`，10 次精确匹配为 0/10 |

Vision 主套件因此按安全门禁报告 `pass=false`，失败原因与此前已知的等宽字体
`l/1` 混淆一致，不是截图失败、Root 失败或匹配链路失败。

## 其他设备套件

- 预处理：28/28 通过，RGBA/BGRA、非紧凑 stride、最大尺寸样本的 tensor 差异均为 0。
- 脚本 UI：10/10 通过；键盘 `imeObserved=false`、底部 inset 为 0，因此只证明共享
  View 行为，不把它当作真实输入法遮挡验收。
- 私有沙箱：3/3 通过，符号链接和硬链接逃逸均被拒绝，目标文件保持不变。
- OCR diagnostics：等宽字体 2 项失败、sans-serif 2 项通过，复现同一 `l/1` 边界。
- OCR evaluation：评估套件通过，但明确 `evaluationOnly=true`、`productionChanged=false`，
  不代表生产模型已经替换或问题已经解决。

## 结论

第十阶段设备侧完成了可用链路验收和性能采样；Studio、灰度找图、预处理、UI、沙箱均
通过。OCR 小写等宽 `l/1` 是当前唯一明确失败项，保留现有模型，不在未经确认时替换。

本阶段强停了前台智构会话后仅启动合成 QA，测试结束由脚本清理；没有清理应用数据，
没有打开或执行私人项目。
