# 函数库补强批次 1–5 验证（2026-10-12）

## 本批范围

- 可选项目音频资源：导入、删除、备份、Runner 分发及运行前冻结；旧 data 音频保持兼容。
- 私有 data 文件元数据、规范 Base64、目录统计及事务式 ZIP 压缩/解压。
- 有界纯文本剪贴板、App 可见性查询与启动请求。
- Root 受控 Unicode 粘贴：先写剪贴板，再发粘贴键；不安装或切换输入法。
- 命名触摸穿透浮窗、声明图片及有界线段/矩形/圆绘图。
- 新积木的 runtimeApi 1.8 草稿升级提案；校验不写正式项目。

设计依据为 ADR 0074–0080。能力通过原生配置、契约、编译器和运行层接通，不仅供旧工程转换器使用。

## 本地验证

- Rust 全工作区单元测试通过；Clippy 全目标 `-D warnings` 通过。
- Schema 检查：520 JSON 文档、184 schemas、159 API、174 积木。
- API 契约快照、Lua stub 与 HTML 文档重新生成后一致性检查通过。
- Android 单元测试报告：project-store 68 项；runtime-service 104 项（1 项跳过）；Studio 562 项。均无失败或错误。
- Studio Lint 报告：0 errors、32 warnings、17 hints。

Debug APK：`android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk`。

## 未进行的验证

按用户要求未安装或启动 MuMu，未做真机验收。系统悬浮授权、MediaPlayer、后台剪贴板限制、Root 粘贴键及目标 App 接收结果仍需设备验证。粘贴不保证游戏自绘输入框或禁止粘贴的控件有效。本批不代表老版全部功能 100% 对齐。
