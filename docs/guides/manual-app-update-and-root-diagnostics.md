# 手动更新与 Root 截图排错

## 使用更新

2026-10-21 后续按 ADR0137 扩展：Studio 0.1.2 / 3 支持前台自动检查、确认后 App 内下载并调用系统安装。可关闭自动检查，下载支持进度及取消，安装前核对真实 APK 与当前 App 签名。旧 App 需要先手动安装一次；以下手动/浏览器流程描述 0.1.1 / 2 的初版。当前操作见文档站「App 内更新」。

在「我的 → 关于智构 → 应用更新」手动检查。默认没有后台轮询或 APK 下载服务，确认后用浏览器打开配置站点的固定下载页，由 Android 系统完成安装确认和签名校验。

站点复用已有 `downloads/release.json`，仅当前版本及 `alternatives` 参与架构匹配，不将历史包当升级。比较数值 `versionCode`，不是版本名、日期或不同 SHA。Studio 本批为 `0.1.1` / `2`，后续要发布升级必须增加版本代码。Runner 不随之改版。

构建时可在忽略的 `android/local.properties` 添加：

```properties
autoscript.updateSite=https://updates.example.com
```

也可用 `-Pautoscript.updateSite=https://updates.example.com`。地址不能包含账号、路径、查询或片段；不要将实际地址和凭据提交仓库。App 可更改本机更新地址。保留既有脚本 HTTP 请求的网络策略，不借更新功能改变它；更新地址优先使用 HTTPS，HTTP 有显式未加密提示。

服务器必须先部署新的静态包和真实 APK 清单。仅编译 App 不会更新服务器；旧服务器清单继续显示旧版本是正常行为。HTTP 清单不可信，不依据其文字声称包已经验签或安全。

## ARM 截图

x86 模拟器正常不代表 ARM 真机已通过：架构、Android 版本、Root 管理器及 SELinux 都可能不同。先分清 Root 后端未就绪与 Root 已认证但截图失败。

本批 Studio 通过系统安装器解压原生库，Root 守护程序优先从安装原生库目录执行，减少执行 app-writable 私有目录文件的兼容风险。保留旧包有界提取回退，不关闭 SELinux、不切免 Root、不放宽认证。这个风险不等于已确认用户手机的具体原因。

截图工具在等待原会话 Root-ready 事件时仍显示，实际采集前才隐藏，失败后恢复工具并显示原因。关闭或更换会话会取消等待，不把其他会话的结果回填。

## 查诊断

Root 服务首次初始化会尝试授权一次；空闲五分钟释放、断线或授权失败后不再后台重复申请。再次运行/单步、截图、触控取点或录制时按需连接，也可在权限与运行环境点击「连接 Root / 重试授权」。首次授权可等待最多 60 秒；拒绝或超时后需要主动重试，单纯刷新状态不会重新弹授权。

「我的 → 运行环境 → Root / 截图诊断 → 查看 / 复制」可查看固定阶段码，不必先运行脚本。设备连接后也可读取 logcat 的 `ZhigouDiagnostics` 标签。

App 诊断独立保存在私有 `files/app-diagnostics/app.log`，上一段为 `app.previous.log`，每段最多 64 KiB；后台队列最多 64 条，实时窗口最多 100 条，写盘失败或丢弃会标注。它们不是项目资源，也不写入脚本的 `script.log`。不记录原始 su 输出、私有路径、会话密钥、截图或脚本。

| 代码 | 含义 |
| --- | --- |
| `ROOT_INSTALLED_DAEMON` | 使用系统安装的原生守护程序 |
| `ROOT_EXTRACTED_DAEMON` | 已走旧提取回退，需特别检查执行权限 |
| `ROOT_SU_START_FAILED` | 无法创建 su 进程，不等于已证明设备没有 Root |
| `ROOT_PROCESS_PERMISSION_DENIED` | 进程报告访问/执行拒绝，具体授权或系统策略需设备证据 |
| `ROOT_PROCESS_LINK_FAILED` | 可执行程序链接阶段失败 |
| `ROOT_START_TIMEOUT` / `ROOT_ATTACH_FAILED` | 未收到就绪或认证连接失败 |
| `ROOT_READY` | 后端完成认证，仍不代表已截图成功 |
| `ROOT_IDLE_RELEASED` | 整个认证会话空闲后释放，等待主动操作重新连接 |
| `CAPTURE_BACKEND_FAILED` | 已发起截图，但采集后端未返回画面 |
| `CAPTURE_READY` | 本次预览生成成功，不保证其他方向/窗口均通过 |

代码测试、APK 检查、MuMu 验收与 ARM 真机验收分别记录，不使用模拟器结果替代真机结论。
