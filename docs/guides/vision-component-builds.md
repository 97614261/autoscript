# 可选视觉组件构建

这是开发构建矩阵，不是组件下载器或“勾选即安装”。默认安装版本仍为完整视觉配置；基础版的项目设置/能力可用性入口尚待后续接通，不自动改写已有项目声明。

## 四类内容

| visionProfile | OpenCV | ONNX Runtime | 固定字母数字模型 |
|---|---|---|---|
| base | 无 | 无 | 无 |
| opencv | 有 | 无 | 无 |
| onnx | 无 | 有 | 有 |
| full | 有 | 有 | 有 |

显式配置使用独立输出和缓存：`build/profiles/components/<abiProfile>/<visionProfile>`。缺少正确绝对缓存路径会拒绝配置，避免互相清理生成物；普通full构建仍走既有开发目录。

从仓库根执行基础版示例：

```powershell
.\gradlew.bat :android:runtime-service:testDebugUnitTest :apps:studio-android:assembleDebug '-Pautoscript.visionProfile=base' --project-cache-dir 'C:\Users\TY-04\Desktop\autoscript\build\profiles\components\development\base\.gradle' --offline --console=plain
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-native-packaging.ps1 -VisionProfile base
```

切换成opencv、onnx或full时，属性、缓存路径最后一段及检查参数必须一起更换。手机配置同时加`-Pautoscript.abiProfile=arm64`，并将组件路径中的development换为arm64；核对脚本同时传`-Profile arm64`。这些实验配置不自动安装到MuMu，亦不是正式发布包。

## 源码边界

- runtime-service的main仅含SDK中立适配器、取消/生命周期、缓冲与ROI校验、张量预处理、CTC解码。
- `src/opencv/java`、`src/opencv/assets`提供灰度匹配及对应许可证；固定Android库名`opencv_java4`，不使用AAR中桌面风格的`Core.NATIVE_LIBRARY_NAME`。
- `src/onnx/java`、`src/onnx/assets`提供推理与哈希固定的模型及许可证。ONNX不引用OpenCV；像素预处理是有界BGR、线性缩放、通道归一化和零填充。
- 工厂仅在build目录生成，只引用本配置的固定后端类。没有任意插件类名、URL、shell、外部模型路径或代码下载。
- 无对应后端时返回`VISION_COMPONENT_NOT_INSTALLED`，不是未找到图像或成功空结果。

## 离线 Runner 模板

项目的「打包应用」支持导入本地模板 APK、刷新检查并选择与声明需求完全相符的组合。模板只用于打包，不安装或加载到智构，不代表智构运行时已安装对应视觉 SDK。下载尚未开放。

导入要求 APK v2 签名证书与当前智构发行证书一致。使用其他机器或不同证书导出的开发模板不能直接导入；不能用自行填写的摘要绕过此检查。固定协议/API、Android24最低版本、ABI、模型摘要、许可证和实际 Android 清单都须通过检查。保存最多8份模板、总计1GiB，失败或取消只清理本次暂存，不覆盖已有文件。

从仓库根导出当前完整开发模板：

```powershell
.\gradlew.bat :apps:runner-template-android:exportRunnerComponent --offline --console=plain
```

输出在`android/apps/runner-template-android/build/outputs/components/runner-full-development.apk`。这是与智构开发签名一致的离线打包组件，不是可执行的项目运行包或正式发布包。首次需要先正常构建 Studio，使既有开发签名可用。

基础版示例沿用隔离配置：

```powershell
.\gradlew.bat :apps:runner-template-android:exportRunnerComponent '-Pautoscript.visionProfile=base' --project-cache-dir 'C:\Users\TY-04\Desktop\autoscript\build\profiles\components\development\base\.gradle' --offline --console=plain
```

组件输出在该隔离目录下`apps/runner-template-android/outputs/components`；CV/ONNX/手机配置按上面的对应参数一起切换。签名导出不改既有 unsigned 测试模板，不携带私人项目，不自动安装。

四类开发模板均导出后可运行真实矩阵检查：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-runner-components.ps1
.\gradlew.bat :apps:studio-android:testDebugUnitTest '-Pautoscript.verifyComponentMatrix=true' --offline --console=plain
```

先等当前构建结束再运行，不要让两个 Gradle 进程同时写同一配置的测试输出。普通构建跳过需要这四份离线导出文件的矩阵用例，保留其他本地测试。

## 已验证与限制

基础/OpenCV-only/ONNX-only/full已经完成开发构建与包内容检查。MuMu API32的28组公开合成张量与OpenCV4.10原路径逐位一致，另有基础版两个缺组件拒绝用例。原始证据见[预处理报告](../verification/onnx-preprocessing-parity-2026-10-13.json)。这不是Android24/25、ARM真机、OCR准确率或Root截图端到端性能验收。

数值路径对照参考[OpenCV4.10的resize实现](https://raw.githubusercontent.com/opencv/opencv/4.10.0/modules/imgproc/src/resize.cpp)，实际接通以合成数据精确比较及后续设备记录为准，不仅凭“都是双线性”宣称等价。
