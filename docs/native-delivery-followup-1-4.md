# 智构交付收尾（第二轮 1–4）

本轮承接 `native-finishing-1-4.md`，不是重复上轮去符号与示例工作。不增加免 Root、云端业务或旧工程特殊语义。设备触摸/视觉由用户验收，主机测试、构建和 MuMu 更新安装由助手完成。

1. 编辑体验：共享面板按钮用真实容器居中，紧凑并限制高度；函数库换分类/搜索回到列表顶部，详情返回保留位置。核对加入、修改、回填的共同入口。
2. 运行收尾：单步读取加入请求身份，迟到暂停快照不能覆盖停止/新读取；提示到期、点击关闭和替换按独立身份处理，旧回调不能关闭新提示；补重复生命周期回归。
3. 识别验收：增加不含私有脚本的设备验收入口，生成公开灰度/单行字母数字图案，检验坐标、阈值、取消和关闭后错误；多模板在 Rust 测试覆盖顺序/限制/预算/取消。设备报告包含型号、分辨率、ROI、模板、冷启动和热运行 P50/P95/P99，未运行设备测试不报告准确率/耗时。
4. 交付：默认双 ABI MuMu 开发包不变；新增独立输出目录的 arm64 手机候选包，Studio 的内嵌 Runner 同 ABI；保留模型和 JNI 导出。不冒充生产签名，不自动用手机包覆盖模拟器。补升级/备份/独立 Runner 与使用验收说明。

## 结果

2026-10-03，代码及主机验证完成，设备体验/识别准确率保留人工验收，不声称全功能设备通过。

1. 共享面板确认文字取消固定 top padding，真实居中并去 font padding，按屏幕高度约束内容/底部；函数库分类/查询重新定位首项，详情关闭保留原列表。识别测试切换文件时绑定解码结果的路径/目录，不能暂时借用上一图片尺寸。加入、修改、变量维护的共享入口沿用上轮已接通逻辑，不另建平行弹窗。紧凑/大字体/横屏触摸效果待用户验收。
2. 单步快照以读取身份提交，停止/运行/新读取使旧身份失效，100轮迟到回归；短提示替换、到期、点击关闭按身份拒绝旧回调，并修正传统 TextView font padding。运行重复暂停取消/故障重试/触点释放继续由真实执行器回归覆盖。配额测试原依赖墙钟并在并行构建失败，改公开边界夹具/确定性时钟，生产2秒超时不改，独立取消/超时测试保留。
3. `VisionAcceptanceInstrumentation` 仅 androidTest APK 中，用生成图案直接测原生灰度/单行 ONNX，输出设备/分辨率/ROI/模板、冷启动、热样本数和nearest-rank P50/P95/P99、expected/actual/整行精确次数。编译成功但未运行设备，准确率和性能不填虚构数字。Rust公开多模板夹具通过，覆盖偏移坐标/顺序/限制/预算/取消；CTC主机测试覆盖62字符及阈值/帧数边界，不代表模型准确率。操作说明在 `native-acceptance-guide.md`。
4. 默认双ABI `build.cmd` 显式选development并更新MuMu；arm64 `build-mobile.cmd` 用独立目录和项目缓存。曾发现Gradle清理上一配置产物，已补强制缓存隔离门禁；现在手机→开发切换后手机APK SHA-256不变、模板仍在且两份审计同时通过。Runner重建器检查完整选定ABI依赖，独立package-probe显式手机参数，默认双ABI门禁保留。手机Debug候选105,575,013字节（约105.6MB），内嵌Runner61,500,175；最终开发包231,382,040字节（约231.4MB），Runner146,123,240。之前增量开发APK有额外ZIP布局空间，报告现在列出压缩条目总量与其他布局空间，不把所有物理差归于ABI。OCR模型7,653,044字节原SHA不变；所有原生导出保留；手机包无x86_64且不安装到模拟器。正式签名与真机验收未做。

验证：两配置各自 Studio263、project-store58、script-ui28、runtime-client13全部通过；runtime-service51项50通过，Windows符号链接权限1项跳过。Lint0错误，开发33 warning/16 hint、手机29 warning/16 hint，含现存问题及手机/动态ABI检查的ChromeOS提示，未宣称零告警。本轮Rust pixel-vision20、package-probe4、flow-compiler84、runtime-executor46、automation-core18通过；修改的两Rust包Clippy通过，cargo fmt与diff-check通过。PowerShell语法和真实受限CLI/安装目标/失败/超时回归通过；未知profile与未隔离手机配置确实拒绝。`build-mobile.cmd -Verify`和最终`build.cmd -Verify`成功，MuMu0号127.0.0.1:16384更新安装成功、保留数据，没有卸载/清数据/执行私人脚本。

审计：`build/native-packaging-audit/verification.txt`、`build/profiles/arm64/native-packaging-audit/verification.txt`；独立冻结Runner测试产物分别在各配置native-example/device-packaging-verification。设备测试APK编译成功但未安装/实测；按验收说明用户自行运行即可。旧转换器阻断状态没有用新版收尾结果覆盖。
