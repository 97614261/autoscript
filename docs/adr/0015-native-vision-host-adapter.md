# ADR 0015：灰度找图与字母数字 OCR 的窄宿主适配

状态：实现完成，主机验证通过；设备兼容待验收。日期：2026-09-29。

用户确认首期 OpenCV 灰度找图、ONNX 拉丁字母与数字。默认 Rust 像素识别和字库 OCR 保持不变；新算法使用独立 API、能力与积木，不改变旧相似度语义。

Android runtime-service 组合 OpenCV Java 与 ONNX Runtime；引擎通过强类型 HostRequest 和固定 JNI 回调调用适配器。UI 仅依赖 runtime-api/积木契约，不直接使用第三方库。帧必须通过任务所有权校验，从有界不可变 FramePool 租约读取；同步 JNI direct buffer 只在调用期间有效，Java 不保存该缓冲区或指针，不通过 AIDL ByteArray/图片文件中转。

灰度匹配采用 OpenCV TM_SQDIFF_NORMED，分数为 1-归一化平方差，返回最高分位置；与 Rust 首个像素匹配显式区分。灰度化忽略 alpha，不支持缩放/旋转。ROI/模板/工作量有上限，按小块计算并检查取消及截止，纯色模板也可匹配。

OCR 首期是用户指定单行 ROI，不包含文本检测、旋转校正、中文或任意外部模型。固定 PP-OCR 英文移动识别模型，输入 BGR/NCHW、48像素高度、归一化[-1,1]，最大宽960（宽高比≤20），短图补到320；CTC去重/blank解码后只保留 A-Z/a-z/0-9。模型97类=blank+元数据95字符+追加空格，按官方预处理/字典规则与真实模型主机样例校验。固定模型资源附来源、哈希、Apache-2.0与权重归属，运行时校验。推理单线程、禁止telemetry，5秒取消/RunOptions终止，所有tensor/result/mat用后关闭；终止不是硬实时承诺。

初次集成锁定本机已核对的 OpenCV 4.10.0、ONNX Runtime Android 1.19.2（不是声称最新版）；许可证 Apache-2.0/MIT，两个ABI体积与API24风险见依赖核对。正式发布前仍需API24真机、16KB页与更新状态验收。宿主构建与测试不等同设备验证。

新增能力 vision.opencv 与 ocr.onnx 由 project.json.capabilities 授权，随冻结 Lua 保留；SDK/模型安装在两端共享 runtime-service，编译积木只在 Studio。旧项目兼容，不删除数据，不向 Lua 开放 shell、模型路径、Context 或 native 指针。

灰度ROI≤4,194,304像素、模板≤262,144像素，总候选数×模板面积≤200亿，每次native块的该估计≤3200万；块边界按模板尺寸重叠，结果按得分优先、同分上/左优先。纯黑模板避免归一化零分母，精确黑区域为1，其余为0。实际时间取决于设备与OpenCV优化，5秒截止在块间检查，不能保证单个native块即时中断。

可视化新增 vision.findgray / ocr.alphanumeric v1，图像识别紧凑弹窗共用；灰度积木还需vision.template加载登记模板。默认自动截图/释放；关闭后引用帧变量且不释放用户帧。写入新节点自动升级项目runtimeApi至1.7。默认Rust找图、字库OCR与旧节点不变。
