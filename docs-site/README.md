# 智构文档站

独立的 VitePress + Vue 3 + `@vue/theme` 文档项目，使用智构现有 Logo 和紫粉配色。包含使用指南、按分类本地搜索及全部脚本 API 的独立详情页。提供只读 App 版本清单和 APK，不控制 App、Runner 或连接在线搜索服务。

## 本地启动

需要 Node.js 20.19+（推荐 Node 22）和 npm。在仓库根目录运行：

```powershell
cd docs-site
npm ci
npm run dev
```

打开终端打印的本地地址，通常为 `http://127.0.0.1:5173/`。仅绑定本机，不自动部署。

```powershell
npm test          # 契约覆盖、内容、搜索及生成文件安全检查
npm run build     # 同步契约、生成静态站、检查全部内部链接和 API 页面
npm run preview   # 预览上一步构建的站点，地址以终端输出为准
```

## APK 下载与部署

支持可选的独立[APK HTTP发布服务](../docs/guides/apk-http-publishing.md)：Python标准库、本机监听及Nginx显式代理，Windows通过HTTP/HTTPS双向HMAC签名直传，不需要SSH。私有密钥不随请求发送；HTTP不保密、不等同TLS。使用根目录`publish-apk.cmd`显式发布，`-CheckConnection`仅验证签名连接。下载页会实时读取清单，读取失败禁用旧快照下载链接；不需要为每次APK更新重建文档站。本地实际验签与服务端摘要检查分开，服务器配置由管理员完成，不随文档构建自动安装。

已安装签名发布服务时，运行`npm run package:publisher`生成小更新包，不携带APK或重建网站。上传后按包内`UPDATE.md`运行`update-publisher.py`，一次升级即可使用`archive-apk.cmd`预览、显式确认并签名归档选中历史，以后不必逐次上传归档脚本。备份旧源码，保留下载、历史和密钥，失败回退；不改Nginx/systemd、不重复初始化。旧Bearer服务按指南单独迁移。

首页和导航的「下载智构」进入下载页。普通文档构建不要求 Android SDK 或安装包；未准备安装包时，页面明确显示尚未发布，不提供失效下载按钮。

发布前先使用 Android 构建工具校验 APK 签名，并核对要公开的安装包。下载入口接收智构 Studio Debug 完整视觉包，以及明确选择的单架构 Release 优化候选；不发布 QA 包、Runner 模板、视觉组件或用户项目。候选仅为开发证书签名，尚未完成设备验收，不冒充正式发行。

在 `docs-site` 中运行：

```powershell
npm.cmd run prepare:download -- --apk ../android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk --sha256 <已核对的SHA256>
npm test
npm run package
```

已核对签名的单架构候选使用显式 `--candidate`；先准备主下载 ARM64，再用 `--alternative` 添加同版本、相同配置且架构不重叠的 x86_64 当前选项。候选仍保留 full 与固定 V4/V5；签名检查不由下列命令替代：

```powershell
npm.cmd run prepare:download -- --apk ../build/profiles/distribution/arm64/apps/studio-android/outputs/apk/release/studio-android-release.apk --sha256 <ARM64完整摘要> --candidate
npm.cmd run prepare:download -- --apk ../build/profiles/distribution/x86_64/apps/studio-android/outputs/apk/release/studio-android-release.apk --sha256 <x86_64完整摘要> --candidate --alternative
```

清单的可选 `alternatives` 是当前架构选项，`history` 才是历史构建；两个字段均禁止嵌套，全部文件参与字节预算、校验、归档打包与服务器 HTTP 验收。旧单包/Debug 清单兼容读取，Release 必须明确携带未设备验收候选状态和已核对的固定开发证书摘要。重复准备主包不将其架构选项降为历史。

App 0.1.2 / 3 起支持前台自动检查及用户确认后的 App 内下载，复用这份 `downloads/release.json`，无需另建后端或第二份版本数据。发布升级必须增加 Android `versionCode`；准备脚本拒绝版本代码退回，同代码开发构建不触发 App 升级。更早 App 须先手动安装一次。服务器验收额外检查清单 JSON MIME 与重新验证缓存头，不能重定向到登录页；APK 仍使用同源带摘要的不可变地址。详见[App 内更新](src/guide/app-update.md)。

准备脚本读取相邻 `output-metadata.json` 的版本和系统要求，从 APK 的 ZIP 目录读取实际引擎 ABI，并检查完整视觉库与固定模型条目。它复核源与副本 SHA-256，生成带摘要的文件名、校验文件和下载信息；这不替代 APK 签名校验。修改或混入未知文件会阻断构建，不扫描删除未知文件。`src/public/downloads` 和安装包均为忽略的本地产物，不提交 Git。

可加 `--description "本次构建的简短说明"`。首页仅保留次要下载按钮，顶部「下载」进入独立页面；版本、摘要和历史列表不在首页重复展示。准备新包时保留上一次真实 APK 作为历史构建，仍复核每个历史包，不虚构版本。相同版本号按构建日期和短摘要区分。最多8个构建、APK总计448 MiB；超出时拒绝发布，需要人工明确归档选中的旧构建，不自动删历史。当前约186 MiB完整包通常只能同时保留当前及一个历史构建。已有单包清单下次准备自动兼容升级。

`npm run package` 构建并校验所有站内链接，在仓库 `build/docs-site-deploy/<时间>-<APK短摘要>/` 生成独立交付目录：静态压缩包及摘要、两个部署工具、`delivery.json`、`CHECKSUMS.sha256` 和已经填入本次摘要的 `DEPLOY.md`。压缩包只含公开静态页面、资源和明确选定的 APK；工具、说明不进入 Web 根目录，不包含服务器凭据或设备数据。

历史超预算时可明确归档**一个非当前历史 APK**，保留文件及旧清单到忽略的 `build/docs-site-download-archive/<摘要>`，不静默删除：

```powershell
npm.cmd run archive:download -- --sha256 <选中的历史APK完整摘要>
```

SSH 部署前先检查目标服务器的 Web 服务、站点根目录和可用空间。上传压缩包到独立发布目录，核对摘要并解压；保留旧版本，通过原子切换站点目录发布。已有站点或域名不能直接覆盖。服务器地址、用户名和凭据仅在本次连接时输入，不存入仓库；首次 SSH 主机密钥须核对，已有密钥变化必须停止。

首次部署也可将 `scripts/deploy-static.py` 和压缩包上传到同一个服务器私有目录，然后在 Linux Shell 中运行：

```bash
sudo python3 deploy-static.py --archive /绝对路径/站点压缩包.tar.gz --sha256 已核对的压缩包摘要 --host docs.example.com --install-nginx
```

该脚本只引导全新的独立站点，若已有智构配置、当前版本或同名 Nginx 主机则停止，不作为覆盖更新器。它完整校验归档路径、预算、APK 与页面一致性后才安装服务或写配置；默认不安装软件，必须显式指定 `--install-nginx` 才使用现有系统 APT 软件源安装 Nginx，不升级系统。配置先通过 `nginx -t`，再检查本机页面、404、APK MIME/大小和字节范围。激活失败将本次新增配置移到私有失败记录目录并恢复可用配置，不删除历史站点或释放版本；已安装的软件保留。公网、防火墙、DNS 与 HTTPS 不会自动改动，仍须单独验收。只有确实看到 `DEPLOY_OK` 才表示本机服务检查通过。

已有站点更新请使用 `scripts/update-static.py`，不要重跑首次部署。支持只读 `--check`、摘要绑定更新与显式 `--rollback`；保留 `/home/admin/zhigou-deploy` 上传路径，发布到 `/var/www/zhigou-docs/releases/`，原子切换 `current`，不重写配置、不 reload/restart、不自动删除版本。上传同一交付目录全部文件，按本批 `DEPLOY.md` 操作。完整流程见[文档站部署指南](../docs/guides/documentation-deployment.md)。本地交付不等于线上已更新。

部署脚本安全测试：`python3 scripts/deploy-static.test.py` 与 `python3 scripts/update-static.test.py`。它们使用标准库，不引入 Python 第三方依赖；真实符号链接/文件锁测试在 Linux 执行，Windows 明确跳过这些项，普通 API 文档生成仍仅需要 Node。

静态服务器需支持 VitePress 无后缀路由，例如 Nginx 的 `try_files $uri $uri.html $uri/ =404`，并将 `/404.html` 用作错误页；不要把缺失资源回退为首页。APK 使用 `application/vnd.android.package-archive` 和附件下载响应，保留字节范围请求，关闭目录列表。HTML 和 `release.json` 使用短缓存或重新验证；含摘要的 APK 和静态资源可用长期缓存。站点与下载采用同源地址，部署后检查首页、API、下载页、404、下载大小和远端 SHA-256。

## 精简目录

- `src/`：首页、使用指南和 API 目录入口。
- `.vitepress/`：站点配置、官方主题扩展和小型 Vue 组件。
- `scripts/`：从仓库契约生成 API 文档、搜索过滤及回归检查；不引入额外生成框架。
- `.vitepress/dist/`：构建产物，可交给静态服务器托管，不进入源码仓库。

仅三个直接开发依赖。VitePress 1.6.4 与官方主题 2.4.0 的声明兼容；Vite 固定为 6.4.4，处理旧传递依赖的开发服务器安全告警。锁文件固定实际依赖，不通过 `npm audit fix --force` 任意换主版本。

## API 的唯一来源

真实来源为 `schema/api-schema/functions/*.json`，生成前与 `schema/api-schema/snapshot/contract.json` 完整对照。页面包括原文摘要、调用顺序、每个参数、返回与可空语义、能力、执行方式、取消、超时、副作用、所有权、兼容名称和原始契约。新增 API 自动进入目录与侧栏；未知分类、重复名称/Opcode 或陈旧快照会明确失败。

`src/api/generated/`、`.vitepress/data/` 及品牌资源副本由 `npm run generate` 产生并忽略，不手工维护重复的 API 页面。生成器只清理清单中明确属于自己的旧页面，拒绝改写手写页面或跟随符号链接。不要将私人脚本、设备日志或反编译资料加入网站。

## 保留旧契约出口

`generated/api.html` 仍由 Rust `api-codegen` 生成并签入，`glyph-dictionary.html` 保留原离线入口；不因换文档主题删除契约快照。更新原始 API 后，在仓库根目录执行既有流程，再构建网站：

```powershell
Push-Location .\rust
cargo run -p api-codegen -- generate ..
cargo run -p api-codegen -- check ..
Pop-Location
```

具体决策见 [ADR0127](../docs/adr/0127-generated-vitepress-documentation.md)。文档 CI 独立执行 Node 检查，不需要编译 Android 或 Rust。
