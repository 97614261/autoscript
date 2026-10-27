# Studio 收尾 4–6 · 网站更新、说明与交付冻结

日期：2026-10-21。承接[1–3交付](studio-closeout-1-3-2026-10-21.md)，本批仅补网站、发布工具和说明；不重开 Android 功能阶段，不再生成不同 APK，不操作真机或私人脚本，不提交 Git。

## 4 · 已有网站安全更新

新增 `docs-site/scripts/update-static.py`，与首次部署 `deploy-static.py` 分离。仅在服务器已有智构独立站点时运行，保留原上传目录 `/home/admin/zhigou-deploy`、站点根目录 `/var/www/zhigou-docs`、`releases` 和 `current` 入口。

- `--check` 只读检查当前直接 release 目录、完整 APK/历史清单、Nginx 配置及本机 HTTP，不创建部署锁或切换版本。
- 更新/回滚同时要求旧 APK 完整摘要与精确网站 release ID；同一 APK 的不同文档发布也会拒绝过期请求。
- 非阻塞 Linux 文件锁拒绝并发，特殊/链接锁文件拒绝；归档预检路径、重复、符号链接、总大小与磁盘预算后解包到独立新目录，验证通过才原子切换。
- 复核 HTTP 完整发布清单和下载页面摘要，覆盖每份 APK 的 MIME/大小/范围及404；HTTP 不对或切换失败时恢复并复核旧版本。
- 显式回滚只选保留的直接 release ID，不接收任意目录；旧版本及失败候选保留。不改 Nginx 配置、不 reload/restart、不安装软件、不改 SSH/防火墙或其他站点。

共享 HTTP 验证补上清单精确比对：旧包与新包可能字节数相同，单凭200和 Content-Length 不能证明当前版本正确。

## 5 · 使用与版本说明

网站新增插件/成功失败分支与连续编辑教程、安装/升级/备份教程，以及独立更新记录和本批详情。说明普通加入沿用明确所选分支、标题不是可执行节点、自然结束默认成功、异常/取消不被失败分支吞掉；解释单项表单与连续工作台的关闭策略。

首页不加显眼版本块，顶部下载和首页次要按钮保留；下载页连接更新记录和安装指南。API仍由既有200份契约自动生成，不维护另一份API清单。

新增[完整部署指南](../guides/documentation-deployment.md)，保留Linux Shell/本地PowerShell边界。Windows带透传参数明确用`npm.cmd`，避免本机`npm.ps1`吞掉`--`。生成的本批`DEPLOY.md`已填入压缩包身份，仅要求在服务器输入实际站点主机及复核出的旧版本，不写入服务器地址或凭据。

## 6 · 同一候选冻结

沿用前批验收的同一APK，不因纯文档修改重新构建：

```text
applicationId=com.zhigou.kaifaban
versionName=0.1.0
versionCode=1
minSdk=24
abis=arm64-v8a,x86_64
profile=debug/full
bytes=194688195
sha256=54ebff69c084e5c0efa1d0313475d9e0344bfaa78bfff54b8dfc4ba08ccbfc69
certificateSha256=8e48ea72d827f96ea57c6b7bf4d566bac7581fa23106324bf3296fa9e2024f6c
```

本批再次用Android build-tools 36.1.0的apksigner实际验签：一个Android Debug签名者、APK v2通过；不是正式生产签名。冻结末尾再次读取源APK摘要未变，不能将ZIP库/模型条目检查当成签名校验。

公开下载本地清单切换为`54eb…`，保留`72b3…`为一个真实历史构建。原`c226…`会使三份包超过448MiB预算，已显式归档APK、校验文件和原清单到忽略的`build/docs-site-download-archive/<完整摘要>`，未删除归档副本、服务器历史版本或用户数据。后续可用`npm.cmd run archive:download -- --sha256 ...`明确选择非当前历史包；不自动删除。

最终交付目录：

```text
build/docs-site-deploy/20261021T101620Z-54ebff69c084/
  zhigou-docs-20261021T101620Z-54ebff69c084.tar.gz
  zhigou-docs-20261021T101620Z-54ebff69c084.tar.gz.sha256
  deploy-static.py
  update-static.py
  delivery.json
  CHECKSUMS.sha256
  DEPLOY.md

archiveBytes=176432554
archiveSha256=0e4ff235f33f494dfa38a0a5dbde665c8c83cba168b8c9f8fd0a9d4c938c7144
```

`delivery.json`绑定完整下载元数据及两个工具摘要，`CHECKSUMS.sha256`覆盖压缩包、其校验文件、工具、清单和说明。部署工具放在归档外，不暴露到网站。该目录的工具摘要已与最终源码再次比对通过，不使用先前试打目录中缺少最后保护的更新器。

## 验证

- Node33项全部通过，无跳过；覆盖契约、生成/搜索、无包状态、下载篡改/历史预算/链接保护、显式归档和交付绑定。
- 本机Ubuntu/WSL实际运行部署7项及更新20项，合计27项全部通过；包含真实`flock`、符号链接原子切换/回滚、HTTP故障恢复、特殊锁拒绝及过期网站身份。没有启动/修改WSL系统服务。Windows复测同20项时7项Linux专属测试明确跳过，不能与Linux结果相加凑数。
- 最终`npm.cmd run package`的站点构建通过：200个API、21组、216个HTML页面、47,733处本地链接及资源检查通过。后续只更新归档外的交接工具，再从同一已构建静态目录生成最终交付。
- 浏览器检查下载页桌面/390px及分支教程320px布局，实际当前链接为`54eb…`、历史为`72b3…`；手机无页面横向溢出，安装指南入口和更新详情可达；临时视口已恢复。保留原深色偏好，不改系统或浏览器设置。
- 最终交付全部校验文件与工具摘要通过；使用生产更新器的真实归档解包和`verify_tree`/APK历史检查另行验证，不靠tar命令返回成功推断内容正确。
- 首次Node测试被沙箱的临时链接/原子重命名权限拒绝，提升权限后同一用例全部通过；首次沙箱预览不可达，改为仅127.0.0.1的本机预览后完成页面检查。这些环境失败不记为已发现Android回归。

原始本地产物：`build/closeout456-node-tests.log`、`build/closeout456-package.log`、`build/closeout456-delivery-final.log`、`build/closeout456-delivery-check.log`、`build/closeout456-real-archive-check.log`。Linux测试通过信息在本次工具执行记录，不虚构尚未保存的设备日志。

## 线上及范围边界

本批4–6的本地代码、文档、校验与交付已完成；**未执行这份新交付的服务器上传和激活，不能宣称线上已更新**。需要将最终目录全部文件上传到既有私有工作目录，按`DEPLOY.md`运行。首次部署及前次线上成功记录保留，不冒充本次更新证据。

本批不使用ARM真机、不新增独立ARM包、不安装MuMu新包、不变更默认full/V4或模型。前批Android1290项及MuMu169次执行的范围、失败/复测和清理继续由1–3记录承担，不重复算成本批新设备验收。公网、DNS/TLS、正式签名和已明确延期能力仍不在“本地网站交付完成”的结论内。
