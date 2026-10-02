# 灰度找图、字母数字OCR与独立停止通道交付

日期：2026-09-29。三个请求范围已实现并完成主机验证；未操作设备，不以编译/测试宣称厂商兼容或优于参考软件的性能。

## 用户入口

- 项目设置启用所需能力。灰度找图：`screen.capture`、`vision.template`、`vision.opencv`；字母数字OCR：`screen.capture`、`ocr.onnx`。不自动扩大项目权限。
- 可视化编辑→图像→识别类型，选择“灰度找图 · OpenCV”或“字母数字 · ONNX”；函数库也能选择对应真实积木，共用现有紧凑识别弹窗。
- 默认自动截图并释放；关闭后使用已有帧变量，不释放用户帧。灰度模板从登记图片/截图选择；OCR不要求模板或旧字库文件，必须框选一行文字区域。
- 新节点保存自动升级项目runtimeApi至1.7。旧像素算法、字库OCR和旧项目保持兼容。灰度找到变量为1/0，位置为模板左上角；未找到清空位置/得分。
- 停止仍用原按钮，不新增操作：独立认证通道取消旧输入，确认释放后才正常结束；无法确认会报`ROOT_STOP_CLEANUP_FAILED`并禁止继续输入，需重新连接RootDaemon。

## 算法与边界

OpenCV4.10.0灰度最高分匹配，TM_SQDIFF_NORMED/1-平方差，纯黑退化特殊处理；不缩放/旋转。与旧Rust首个像素命中的分数不同，不悄悄改旧算法。ROI≤4,194,304像素、模板≤262,144像素，总计算估计≤200亿，5秒截止与块间取消；越界/超预算明确报错。

ORTAndroid1.19.2+固定PP-OCRv4英文移动模型，单行BGR/NCHW/48高，宽高比≤20/最大宽960。CTC只输出A–Z/a–z/0–9，按逐字置信度筛选；空结果平均分0。不含文本检测、中文、旋转矫正或外部模型。7,653,044字节模型经过哈希验证，源码/权重归属与三份许可随两端APK分发。

停止通道与普通请求分别认证/序列化，派生密钥、防跨通道重放，取消使用业务输入ID水位而非任意PID。先阻止旧请求，唤醒拥有子进程的watchdog，退出注入后UP；清理失败锁闭，过期ACK不能解除失败锁。原生长swipe仍可用；没有可用UP后端的设备明确失败，不自动禁用长滑动。无优先协商位的旧Daemon仍只有旧能力，应更新。停止控制期限8秒，不是硬实时停止时延承诺。

## 验证结果

- Rust全仓237项通过；fmt、契约生成校验、diff检查通过。
- Clippy退出0，仍有81条警告（含已有与本批风格/转换警告），没有声称`-D warnings`通过。
- Studio155项、project-store33项、runtime-service8项JUnit通过，合计196项，无失败/错误/跳过。
- Studio lintDebug无错误，23警告/19提示；Studio与Runner Debug均构建通过，arm64-v8a/x86_64，API24链接目标。
- 实际主机ONNX推理：ABCXYZ、abcxyz、0123456789、Hello123、A1B2c3正确，非统计准确率测评。实际OpenCV分块样例坐标为(89,61)。脚本`tools/native-vision-smoke.py`使用隔离测试依赖，不参与APK。
- 水位/旧输入/重复停止/过期应答/失败锁/重新运行/认证隔离与Java桥EOF/异常路径回归；未测真机触点残留、真实停止时延。
- APK核对两ABI native库、模型、许可均存在；Studio专属编译库只在Studio，Runner仍只加载冻结Lua。

构件：`apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk`（415,771,606字节）；`apps/runner-template-android/build/outputs/apk/debug/runner-template-android-debug.apk`（282,365,456字节）。双ABI/未去符号开发包较大，不是最终单arm64发布体积。

用户自行打包命令不变：`.\gradlew.bat :apps:studio-android:assembleDebug`。

## 尚未实测

API24厂商兼容、16KB页、灰度/OCR实际截图效果、弹窗布局、长滑动停止后的真实触点释放。性能另按设备/分辨率/ROI/模板尺寸/P50/P95/P99记录。原长期计划的参考不透明“图像数据”协议仍未恢复，本批不冒充完全对齐该未知类型。
