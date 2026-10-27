# 文档站更新、检查与回滚

适用现有独立智构 Nginx 站点。首次部署工具 `deploy-static.py` 仍只用于新站，不能拿它覆盖已有网站。静态网站更新不修改 Android、项目数据、SSH、防火墙、域名或 TLS。

## 本地冻结

先用 Android `apksigner verify --verbose --print-certs` 核对选定 APK 签名、用 `Get-FileHash` 核对摘要，并确认包身份、实际版本及 ABI。不要因为文件名相同就把新包当作已经验收的旧包。

在仓库 `docs-site` 目录运行：

```powershell
npm.cmd run prepare:download -- --apk ../android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk --sha256 <已核对的完整摘要> --description "本次真实改动说明"
npm test
python scripts/deploy-static.test.py
python scripts/update-static.test.py
npm run package
```

文档构建独立于 Android，不为了网站改动重新生成不同的 APK。普通 CI 无安装包仍可 `npm run build`，显示未发布；发布交付必须有明确准备且校验通过的安装包。

Release 优化候选需显式 `--candidate`，只接受单架构、full、固定开发证书对应的候选，页面明确未设备验收。先准备 ARM64 主包，再以 `--candidate --alternative` 准备相同版本/配置的 x86_64；当前架构选项不是历史。Node 和服务器均校验主包、`alternatives` 和 `history`，预算合计仍为8份/448 MiB。不要因为网站更新而重新编译另一份同文件名 APK；签名验证仍需单独完成。

### 历史包超预算

不自动删旧包。显式选中一个非当前历史包，将其完整 APK、校验文件和原清单归档到忽略的 `build/docs-site-download-archive/<摘要>` 后，从公开历史移除：

```powershell
npm.cmd run archive:download -- --sha256 <选中的历史APK完整摘要>
```

归档不卸载 App，不删除已有服务器版本，不改用户工程。未知文件、已有归档目录、当前包和不匹配摘要均拒绝；归档失败保留源或可恢复副本，不应手工删除整个 downloads 目录绕过检查。

Windows PowerShell 的带参数命令明确使用 `npm.cmd`，避免某些 `npm.ps1` 入口吞掉 `--`。Linux/macOS 使用 `npm`。

## 交付目录

`npm run package` 生成 `build/docs-site-deploy/<时间>-<APK短摘要>/`，每次独立，不覆盖旧交付：

```text
zhigou-docs-<时间>-<APK短摘要>.tar.gz
zhigou-docs-<时间>-<APK短摘要>.tar.gz.sha256
deploy-static.py
update-static.py
delivery.json
CHECKSUMS.sha256
DEPLOY.md
```

`delivery.json` 绑定压缩包、APK/历史列表及两个工具摘要；`CHECKSUMS.sha256` 核对交付文件；`DEPLOY.md` 是已经填好本批压缩包摘要的完整命令。压缩包本身只含网站公开内容，部署工具及说明在压缩包外，不进入 Web 根目录。

建议在原上传目录下新建本批同名子目录，再上传**这一批目录中的全部文件**；进入该子目录执行 `DEPLOY.md`。若继续直接使用原上传目录，必须保证工具、压缩包和 `CHECKSUMS.sha256` 都来自同一交付，不能混用上次工具或旧清单。

## 服务器执行

将**同一交付目录内的全部文件**上传到私有工作目录。原有 `/home/admin/zhigou-deploy` 可以继续用，不影响 `/var/www/zhigou-docs/current` 的站点入口。上传目录不是网站根目录，不要直接把 tar.gz 解压覆盖 current。

根据本批 `DEPLOY.md` 执行以下流程：

1. `sha256sum -c CHECKSUMS.sha256`：校验上传文件。
2. `sudo python3 update-static.py --check --host <原站点server_name>`：只读检查现有版本、文件及本机 HTTP，记录完整旧 APK 摘要与 release ID。
3. `--archive ... --sha256 ... --expected-current <旧APK摘要> --expected-release <旧release ID> --host ...`：有界解压到新目录，验证后原子切换，失败恢复旧链接。同一APK的不同文档版本也不会被过期请求覆盖。
4. 从公网核对 `/download` 当前摘要、页面与下载响应；本机检查不替代公网、TLS 或完整下载摘要。

仅 `UPDATE_OK` 代表本机激活检查成功；`CHECK_OK` 不会发布候选。旧的 `current.next`、并发部署、旧摘要不符、坏清单、错误 HTTP 版本、空间不足或损坏 APK 均停止更新，不删除未知文件或旧 release。

## 回滚

使用成功更新时输出的 `PREVIOUS_RELEASE`，先确认当前摘要与release ID，再显式 `--rollback <旧release ID> --expected-current <当前APK摘要> --expected-release <当前release ID> --host ...`。工具复核完整目标并走同样的切换及 HTTP 检查；不能填任意路径。

旧站点通过 Nginx 的 `current` 符号链接读取，更新和回滚无需 reload/restart。失败候选也保留以供检查。`ACTIVATION_FAILED_RESTORED` 表示已恢复并复核旧版；`ACTIVATION_AND_RESTORE_FAILED` 表示必须人工检查，不得宣称更新或回滚成功。保留的版本何时清理是另一次明确操作，本工具不提供自动删除。

## 发布边界

地址和凭据不写入仓库，主机在服务器命令中明确传入。工具不建立 SSH、不处理密码，不修改其他站点，也不安装软件。APK 签名与本地验收、上传文件校验、服务器文件/HTTP及公网检查是四项不同证据。当前仍为 Debug 开发包；不扩展为 App 更新器、组件下载器或独立 ARM 发布。
