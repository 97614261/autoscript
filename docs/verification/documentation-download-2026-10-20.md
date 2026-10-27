# 文档站 APK 下载与部署验收

日期：2026-10-20

## 本地结果

首页主操作及顶部导航增加「下载智构」，下载页沿用紫粉主题，包含开发包信息、实际系统要求、实际引擎 ABI、文件大小、构建时间、安装步骤和可展开的 SHA-256 校验区。复制失败时提示手动选择，不伪报复制成功。

本次使用已构建的 Studio Debug 完整视觉包，不重新构建独立 ARM 候选。包名 `com.zhigou.kaifaban`，版本 `0.1.0`，版本代码 `1`，minSdk `24`，包含 `arm64-v8a` 与 `x86_64`。文件大小为 `194688195` 字节，SHA-256 为：

```text
c226ecc876e497223a02ea822a2c0e5a540abe77e8bb05356295d2d15f4e71f1
```

Android apksigner 验签成功，使用 APK v2 和 Android Debug 证书；不称为正式发布签名。下载准备脚本额外复核 APK 目录中的实际引擎 ABI、OpenCV/ONNX 库和固定 V4/V5 模型条目，这些条目检查不代替验签。

- 24 项 Node 测试通过，无跳过；包含契约覆盖、搜索、下载摘要、输出身份、旧版本替换、篡改拒绝、路径逃逸及 Windows junction 保护。
- VitePress 生产构建通过：200 个 API、212 个 HTML 页面、47577 处本地链接及资源检查通过。
- 浏览器检查下载页桌面与 390px 手机宽度，深浅色均正常；320px 及 390px 下无横向溢出，折叠校验区显示正确包名、摘要和下载链接。
- 本机 HTTP 首页、API、下载页与 APK HEAD 均为 200；APK 长度与实际文件一致。范围请求 `bytes=0-15` 返回 206 和正确的 `Content-Range`。
- 本机完整 HTTP APK 下载流为 `194688195` 字节，SHA-256 与源包一致，不额外保存下载副本。
- 本机 VitePress 预览未为 APK 返回 MIME 类型，正式部署必须由静态服务器配置 Android APK MIME；本地下载响应不计服务器验收。

静态部署压缩包只包含构建目录和显式选定的 APK，不含源码、凭据、设备日志或私人脚本：

```text
build/docs-site-deploy/zhigou-docs-20261020T132740Z-c226ecc876e4.tar.gz
bytes=90326441
sha256=cca74ed8eb6f0b52cf5484399cc4876a61ea4d2e6a8e667e6674b15a9db45da4
```

## 服务器状态

服务器部署及公网访问检查通过。SSH 连接未能建立，改由用户在已登录的 Workbench Shell 上传产物并执行 `docs-site/scripts/deploy-static.py`。用户回传的执行结果包含 Nginx 配置检查成功、`DEPLOY_OK`，以及服务器本机页面、APK MIME/大小/范围请求和404检查通过。

部署前已通过 Workbench 核对目标 Ubuntu 24.04 实例，admin 免密 sudo 可用，磁盘剩余约35GiB，未发现已有 Web 服务。部署按用户授权从 Ubuntu 官方软件源安装 Nginx，创建独立站点及版本目录，不升级系统、不重启服务器、不修改 SSH 或防火墙、不覆盖其他站点。安装输出中的服务重启已延后提示不代表部署失败。

部署脚本的6项安全测试通过，实际压缩包686个条目通过同一归档检查；启用站点前复核压缩包摘要、解包后 APK 摘要与大小、校验文件及下载页一致性。失败保留可恢复文件，地址及凭据不保存到仓库。

随后从本机直接访问用户指定公网地址，进程内禁用代理，独立验证：

- 首页、`/download`、`/api/`、`/guide/quick-start` 返回200及 HTML 类型。
- `/downloads/release.json` 返回200，其 APK 大小与 SHA-256 和本次选定安装包一致。
- 不存在的随机路径返回404，不回退到首页。
- APK HEAD 返回200，类型为 `application/vnd.android.package-archive`，大小为 `194688195` 字节，并包含 attachment、immutable 缓存、nosniff 和 Accept-Ranges 响应头。
- APK 范围请求 `bytes=0-15` 返回206、16字节及 `Content-Range: bytes 0-15/194688195`。

公网检查覆盖页面、发布元数据及 APK 下载响应，未再次从公网完整下载 APK；完整 HTTP 下载摘要已在本机验证，服务器启用前由部署脚本复核文件摘要。当前提供 HTTP，尚未配置域名或 HTTPS；公开下载仍明确标注为开发测试包。
