# OCR策略与模型候选评估

2026-10-15。用户允许评估，正式模型/策略替换前再确认。生产模型、解码、阈值和默认full未变更；这里不是准确率放行。

## 固定模型实际比较

MuMu 0 / API32 / 显示720×1280；**测试进程os.arch=aarch64、64位，是模拟器ARM转译，不是真机**。现有PP-OCRv4英文ONNX为7653044字节，SHA-256 `e8770c967605983d1570cdf5352041dfb68fa0c21664f49f47b155abd3e0e318`。字32px、初始行图高56px，白底黑字/抗锯齿，MONOSPACE/SANS_SERIF两字体，置信度500‰。

每字体5条公开文本：a–z、A–Z、0–9、`Il1O0lI10`、`A1b2Z9`；每条每策略重复3次，40个条件共120次调用，不是120个独立业务样本。第二轮9655ms，先前一轮9382ms不合并分位数。

| 策略 | 整行精确次数 | 热样本数 | P50 ms | P95 ms | P99 ms |
|---|---:|---:|---:|---:|---:|
| 原图 | 21/30 | 29 | 55.678 | 111.266 | 112.762 |
| 明确紧ROI（文字框外2px） | 21/30 | 30 | 73.684 | 184.317 | 187.035 |
| 四边增加16px白边 | 15/30 | 30 | 50.238 | 72.641 | 73.807 |
| 双线性放大2倍 | 21/30 | 30 | 56.357 | 108.750 | 115.279 |

原图首次冷初始化不进入热分位数，其他策略使用同一已热会话。耗时包含既有BGR/缩放/归一化、推理和解码，不含夹具预先加白边/放大的位图成本、Root截图、Binder或输入。不以混合长度的小样本尾分位数承诺业务速度；夹具逐项报告帧/ROI和公开输出。

各策略为固定执行顺序而非随机交叉，不能依据这组小样本分位数宣称某策略普遍更快；首要结论是本合成集没有准确率改善。

等宽小写四策略都失败：原图/紧ROI/放大均为`abcdefghijk1mnopqrstuvwxyz`，白边还漏字。两字体的`Il1O0lI10`四策略全部失败；等宽原图`I11001110`，无衬线原图`I1001110`。白边还使原本正确的部分大写/小写变差。

结论：不推荐隐式默认收紧ROI、加白边或放大来修复混淆；不做全局1/l替换。评估`pass=true`只表示完整执行，`allExact=false`表示未全对；原`ocr-diagnostics`/`vision`门禁继续失败。

## 候选模型初筛（未下载/执行/替换）

以下为最初初筛记录。用户确认执行最后六项后，已完成固定v5候选实测，结果更差、不推荐替换；最新数据见[最后六项记录](../verification/studio-final-six-closeout-2026-10-15.md)。生产模型未变。

官方[PaddleOCR识别模型文档](https://github.com/PaddlePaddle/PaddleOCR/blob/main/docs/version3.x/module_usage/text_recognition.en.md)列英文`en_PP-OCRv5_mobile_rec`约7.5MB，并提供Paddle权重入口；这不是本项目ONNX/APK实测体积。英文项未给可直接采用的Android ORT耗时，官方准确率也不是本合成集结果，不能保证解决l/1。

优先候选为英文移动v5，暂不选多语/服务端大模型。仓库[Apache-2.0许可](https://github.com/PaddlePaddle/PaddleOCR/blob/main/LICENSE)不替代具体权重、导出器和分发NOTICE审查。

候选还需固定官方文件/摘要、核对权重许可、独立有界ONNX导出及算子/字典/CTC检查，再在同一输入比较准确率/速度/内存/体积，并验证现有ORT1.19.2、API24及两ABI。本机捆绑Python没有onnxruntime/onnx/paddle/paddle2onnx；没有为此静默安装工具或执行外部代码。生产固定摘要校验不放宽为任意外部模型加载。候选真正实测优于现有且用户再次确认后才另批替换，当前不称“新模型已评估通过”。

## 复现

测试源为`android/modules/runtime-service/src/androidTest/java/com/autoscript/runtime/service/OcrStrategyEvaluationFixture.kt`。构建full隔离测试APK、保留数据安装后：

```powershell
.\scripts\run-studio-device-acceptance.ps1 -DeviceSerial 127.0.0.1:16384 -Suite ocr-evaluation
```

仅生成公开位图，不采屏/运行项目/改Studio选择。设备90秒检查、单次适配器最多5秒、宿主120秒、报告64KiB上限。
