# 智构原生收尾 1–4

2026-10-03用户决定：本轮只完善新版原生，不逐条适配旧工程特殊Native语义/外部库。现有协议、数据兼容及独立转换器保留，不删除用户数据，不把旧工程阻断数计作新版功能完成率。

1. 打包：按APK真实条目分解大小；保留OCR模型、灰度找图和双ABI，裁减打包副本中的Rust符号，保留本机构建原件；MuMu更新安装不得卸载清数据。
2. 页面：原生识别/结果配置加入与修改的变量入口、类型/错误校验和回显；预览样式与运行样式一致，沿用现有紧凑风格。
3. 稳定性：检查编译/准备/Root就绪/提交/取消代次，重复运行、暂停继续、停止清理、错误后再次运行；增加缺失回归。
4. 完整示例：仅合成公开工程，串联变量、复合判断、循环、多图识别/结果属性、插件调用、界面、提示；测试编译执行、保存备份恢复及Runner冻结，未知旧源不执行。

每项记录代码/主机验证/设备验收分别状态。设备交互由用户验收，不自动执行私人脚本。

## 本批落地与验证（2026-10-03）

1. 打包副本去除DWARF，NDK版本统一为已有30；原件继续位于target及模块build/generated/unstripped。Studio Debug从575,087,635降至301,423,771字节，约减少47.6%；Runner模板从242,297,320降至146,123,240字节。六个双ABI Rust ELF均无debug_info，动态符号表与原件相同；OCR模型7,653,044字节，SHA-256与原件相同。两ABI、OpenCV、ONNX及手机打包模板保留。剩余大项为OpenCV/ONNX库、内嵌Runner、Compose Debug DEX，不是OCR模型主导。`scripts/verify-native-packaging.ps1`可复验，不就地改变原件。
2. 图像加入、函数库加入、修改三个入口接上维护变量，保留草稿。新多结果找图/结果属性按局部遮蔽全局及插件参数的实际规则筛选类型，加入前检查声明/能力，不自动授权。结果属性改用同一紧凑面板，枚举选择、序号边界、明确错误；不显示无关截图/帧控件。旧隐式变量输出兼容保留。设计器取消未设置却自动出现的装饰边框，避免预览/运行样式差异。页面实际触摸与视觉效果待用户验收。
3. 启动准备（图像解码、文件资源、同步Binder）移到IO线程，保留通知驱动Root等待、会话代次、启动互斥与取消清理。新回归覆盖旧完成不能释放新请求、重复取消后重试、Root失败/超时/代次变化/忙状态拒绝；真实Lua执行器20轮暂停/继续/取消及故障后重试，任务自有帧清理至0。既有优先停止、输入触点释放与迟到宿主结果回归一并通过。没有声称同步Binder本身可强制中断，也没有替用户运行私人脚本。
4. `examples/native-workflow-project`和`scripts/build-native-example.py`提供公开合成工程及可导入ZIP。真实编译器/Lua5.4配确定性宿主夹具验证找图成功/失败、循环参数边界、变量计算、AND/NOT判断、插件调用、运行输出/开发日志/短时提示、控件更新与界面事件绑定。Kotlin仓库验证导入→保存→备份→恢复；修复Gson未执行构造默认值导致可选luaFiles/luaDirectories/variables缺失时被误判损坏的问题，显式null仍拒绝。冻结发布复验通过；真实Runner模板重建、独立SDK v2验签、16KB对齐及两ABI通过，产物不含编辑器/Flow草稿。模板是合成1×1像素；设备通常走安全未找到分支，不发送点击/滑动。

验证：Rust九个相关crate合计220个单测通过；Studio260、project-store58、script-ui28、runtime-client13通过；runtime-service48项中47通过、1因Windows无创建符号链接权限跳过。Lint0 error、32 warning、16 hint，仍有既有Clippy告警；fmt、API快照和打包脚本回归通过。Debug构建成功。首轮ADB连接超时未确认安装，后续`build.cmd -Verify`使用显式`--no-streaming -r -t`已成功更新MuMu0号127.0.0.1:16384，保留应用数据；未卸载、未清数据、未重启模拟器/ADB服务器。

公开产物：`build/native-example/native-workflow.zip`、`build/native-example/release`、`build/native-example/native-workflow-runner.apk`（一次性测试签名，仅主机验签，未安装Runner）。报告：`build/native-packaging-audit/verification.txt`与`build/native-example/runner-verification.txt`。主机验证与安装完成不等于全功能设备验收或旧工程100%适配。
