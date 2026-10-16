# 第三至五批本次补强验证

日期：2026-10-11（用户时区）。

## 范围

第三批运行期背景图片、主窗/脚本界面浮球位置、容器组显隐/启用/重置已接通。第四批新增裁剪、连续截图、读取指定系列帧和释放系列积木。第五批新增Task.stop、Log.setEnabled、Prompt.setEnabled，函数库提供中文标题与行为说明。契约为130个API、145种积木。

Task.stop先清理待显示命令并发送停止标记，再走调度优先停止；Android据此区分主动停止和正常结束，关闭提示并拒绝迟到显示。读取已有系列帧仅要求core.task，不要求无关的screen.capture能力。

## 已执行

- cargo fmt --all；cargo clippy --workspace --all-targets --offline -- -D warnings：通过。
- cargo test --workspace --offline：通过。能力声明调整后额外运行编译器107项回归及新增最小权限测试，均通过。
- schema-check：433个JSON文档、155个Schema、130个API、145个积木定义通过；api-codegen check通过。
- 主动停止/输出开关4项测试通过；包括子任务停止整个运行、帧清理、停止标记、日志保留与提示队列控制。
- 帧资源联动测试通过：真实2×1 RGBA帧→连续采集→借用→裁剪→取色→释放，检查资源计数归零。
- build.cmd -Verify：首次发现目录测试仍期待旧截图分类，修正预期后完整Android构建通过。Studio559项、script-ui47项均零失败；runtime-service81项零失败、1项跳过。
- Studio Lint：0错误、32警告、17提示；Debug构建通过。
- 仓库布局检查：162个本地链接、22个Rust成员通过；git diff --check通过（存在仓库既有换行提示）。

MuMu安装未完成：构建脚本尝试启动0号实例，但等待Android启动120秒后超时，未进入ADB安装。未卸载或清除应用数据。APK保留于android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk；模拟器启动后可运行build.cmd -SkipBuild仅安装本次产物。

## 未完成及验收边界

未运行用户私人脚本，未做真机或Android窗口交互验收；背景位图渲染、拖动/旋转和连续截图设备表现仍需后续验收。

第四批仍缺运行期原像素PNG保存、图片变换、逐字OCR/文本查找、批量多点找色与颜色连通块。第五批仍缺清理后重启、脚本录制和音频。命名浮窗/绘图仍单独评估，不把当前能力算成五批全部完成。
