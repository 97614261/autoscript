# APK HTTP 发布服务

本地代码和测试完成不等于服务器已配置。本服务只管理Studio下载，不管理项目、账户或组件。
Python 3.9+标准库、Nginx、systemd；无数据库或Python第三方依赖。服务只监听127.0.0.1:8765。
Windows通过HTTP/HTTPS直接请求Nginx的`/api/publisher/v1/`，不需要SSH、域名或证书指纹配置。
每次操作使用一次性挑战及双向HMAC-SHA256签名，原私有Token仅用作本机签名密钥，不发送到网络；旧Bearer接口已停用。
**HTTP不加密**：APK、说明及通信可被观察、阻断或转发；签名不等同TLS。HTTPS仍使用系统正常证书验证，不跳过校验。
公网不开放8765，不代理旧`/api/admin/`。本指南取代此前SSH隧道说明，决策见[ADR0140](../adr/0140-signed-http-apk-publishing.md)。

## 发布流程

1. 本地正常打包；打包不会自动公开。
2. `publish-apk.cmd`冻结独立副本，校验实际APK v2单签名、固定开发证书、包名/版本/minSdk和完整视觉ABI清单。
3. 先验证服务器签名及当前修订/预算，再签名流式上传；服务端复核契约、摘要及ZIP库存，不冒充独立APK验签。
4. 先发布不可变APK和校验文件，最后原子更新`release.json`。版本倒退、并发冲突、未知文件或超预算明确拒绝。
5. 下载页打开时核对清单，10秒超时或无效响应会禁用构建快照下载链接并提供重试。App沿用同一清单，只有更高versionCode才升级。

保持八个构建/448MiB预算；不会自动删除历史、选择归档对象或清理未知文件。预算已满时先停止发布，明确选择历史并确认签名HTTP归档后才能继续，不能靠修改虚假大小绕过校验。
同一包重复上传不会重复历史；`-Alternative`用于同版本且架构不重叠的当前选项，新主包会把旧的主包及架构选项移到历史。

## 本次一次性升级：已有签名服务增加历史归档

2026-10-25新增，见[ADR0158](../adr/0158-signed-history-archive.md)。仅适用于已经能够
`publish-apk.cmd -CheckConnection`的签名服务。**归档接口必须在服务器升级成功后才可用**；
本地生成更新包不表示已部署。APK仍单独发布，本步骤不归档或发布任何版本。

1. 本机在`docs-site`运行`npm run package:publisher`，得到一个小`.tar.gz`及同名`.sha256`。
2. 把这两个文件上传到服务器`/home/admin/zhigou-deploy/`，不用上传APK、私有配置或密钥。
3. 在服务器执行以下命令，压缩包名称换成这次交付的实际名称；解压目录须是全新目录，已存在就停止，不覆盖：

```bash
set -eu
cd /home/admin/zhigou-deploy
archive=zhigou-publisher-实际交付ID.tar.gz
sha256sum -c "$archive.sha256"
unpack="${archive%.tar.gz}"
mkdir "$unpack"
tar -xzf "$archive" -C "$unpack" --no-same-owner --no-same-permissions
sudo python3 "$unpack/update-publisher.py"
```

升级工具核对完整交付清单及摘要、Python语法、已有非root服务和目录；备份两份旧Python源码到
`/opt/zhigou-publisher/before-archive-<随机后缀>/`，停止发布服务后更新，并以原服务用户校验
下载库存及锁。重启后用现有密钥在本机完成签名挑战与读取，确认归档能力及清单修订不变。
成功必须看到`PUBLISHER_UPDATE_OK`。普通失败自动恢复旧源码并尝试启动，打印
`UPDATE_ROLLED_BACK`；恢复也失败则保留备份并打印`UPDATE_REQUIRES_INSPECTION`，先检查服务状态，
不要反复初始化或清理文件。

工具**不改Nginx、systemd单元、原密钥、网站和APK清单，不安装软件**；原代理前缀已支持新路由。
不需要`daemon-reload`、Nginx重载、开放端口或重新取密钥。`--check-bundle`仅检查交付文件，
不执行升级，也不冒充服务器检查。首次安装或尚未迁移到HMAC的旧服务用后面的对应步骤。

## 日常归档：以后不用再上传脚本

以下是本机PowerShell命令，复用`publish-apk.local.json`；无需Java、Android SDK或SSH。

```powershell
# 默认只列出真实历史及当前修订，不改服务器。
.\archive-apk.cmd

# 选择完整摘要，可用逗号分隔同版本的两个架构；仍然只是预览。
.\archive-apk.cmd -Sha256 "第一个完整SHA256,第二个完整SHA256"

# 用户确认预览后，带上预览打印的64位修订摘要才真正归档。
.\archive-apk.cmd -Sha256 "第一个完整SHA256,第二个完整SHA256" -Revision "预览的64位修订摘要" -ConfirmArchive

# 仅签名读取已有私有归档回执；用于核查断线附近是否已完成。
.\archive-apk.cmd -ListArchives
```

没有`-ConfirmArchive`不会写入；确认时修订已变就拒绝，刷新后重新核对。主包及当前所有架构选项
禁止归档，未知/重复/部分摘要拒绝；不自动选择最旧包。成功输出`ARCHIVE_OK`及服务生成的归档ID，
随后沿原`publish-apk.cmd`发布新包。旧服务没有此能力会明确要求先升级，不回退到上传一次性脚本。

服务先把选中APK、校验文件和前后清单完整复制、验摘要并持久化到私有
`/var/lib/zhigou-publisher/archives/<归档ID>/`，再原子切换公开清单，最后精确移除已有可靠副本的
公开文件。因此归档**减少下载历史占用，不释放总磁盘空间**。最多32批/4GiB，另需临时空闲空间；
达到上限明确拒绝，不能自动删旧归档。之前的`archive-0.1.2-*`等手工目录不在此命名空间，不改动。
归档目录可人工恢复，不新增公网归档下载、删除或远程回滚接口。

中途I/O失败、进程退出或清理不完整会保留`staging/archive-*`及已生成的私有副本，阻断后续写入；
原文件或已经校验的私有副本不会被一起丢弃。签名回执的`complete=false`不是成功。
先保留现场核查前后清单和实际文件，不能自动重试、批量删除暂存或靠重启当作修复。
响应断线也不推断成功，先读取签名清单/归档回执再处理。

## 从旧服务迁移到HMAC（已经能签名连接的服务器跳过）

已经有`zhigou-publisher`服务、独立下载目录和密钥时，**不要重建用户、初始化目录或生成新Token**。
仅更新两个Python文件并增加发布代理；网站页面、APK、历史记录、密钥和systemd单元保留。

本地在`docs-site`运行`npm run package:publisher`，生成独立小包及摘要到`build/docs-site-publisher/<交付ID>/`。
通过已登录的阿里云Workbench上传该目录中的`.tar.gz`和`.tar.gz.sha256`到服务器私有子目录，例如`/home/admin/zhigou-deploy/publisher-update`，不用SSH。
先按包内交接信息核对压缩包摘要，解压到新的空目录，进入解压后的目录再执行以下命令。以下全部是**服务器Linux命令**，不是本地PowerShell命令。

### 1. 校验并备份原服务

```bash
set -eu
sha256sum -c CHECKSUMS.sha256
python3 --version
python3 -c 'import ast; from pathlib import Path; [ast.parse(Path(p).read_text(encoding="utf-8")) for p in ("publish-server.py", "deploy-static.py")]'
sudo test -f /opt/zhigou-publisher/publish-server.py
sudo test -f /opt/zhigou-publisher/deploy-static.py
sudo -u zhigou-publisher test -r /etc/zhigou-publisher/token
sudo test -f /var/lib/zhigou-publisher/downloads/release.json
sudo nginx -t

PUBLISH_BACKUP="/opt/zhigou-publisher/before-http-$(date -u +%Y%m%dT%H%M%SZ)"
sudo mkdir -m 0700 "$PUBLISH_BACKUP"
sudo cp /opt/zhigou-publisher/publish-server.py /opt/zhigou-publisher/deploy-static.py "$PUBLISH_BACKUP/"
sudo cp /etc/nginx/sites-available/zhigou-docs.conf "$PUBLISH_BACKUP/zhigou-docs.conf"
printf 'PUBLISH_BACKUP=%s\n' "$PUBLISH_BACKUP"
```

记下备份路径；目录已存在会停止，不覆盖。任何检查失败先排查，不重初始化下载目录。

### 2. 更新并启动服务

```bash
sudo systemctl stop zhigou-publisher
sudo install -m 0644 publish-server.py deploy-static.py /opt/zhigou-publisher/
sudo systemctl start zhigou-publisher
sudo systemctl status zhigou-publisher --no-pager
sudo journalctl -u zhigou-publisher -n 15 --no-pager
```

必须看到新的`PUBLISHER_READY: signed protocol v1; loopback only`。不用`daemon-reload`，因为单元未变。

### 3. 增加Nginx引用

```bash
sudo install -m 0644 nginx-publish.conf /etc/nginx/zhigou-publish.conf
sudo nano /etc/nginx/sites-available/zhigou-docs.conf
```

只在原来的`server {}`内、现有下载引用旁加**第二行**，不要粘贴新的`server {}`，不要include站点文件自身，也不要将两条include写在同一条指令里：

```nginx
include /etc/nginx/zhigou-downloads.conf;
include /etc/nginx/zhigou-publish.conf;
```

若发布include已经存在则不重复加入。保留其他站点配置，然后：

```bash
sudo nginx -t
sudo systemctl reload nginx
curl -i -H 'Host: server.example' http://127.0.0.1/api/publisher/v1/releases
curl -I -H 'Host: server.example' http://127.0.0.1/downloads/release.json
```

第一个请求未签名，**预期401**，不能把401当成配置失败；下载清单应为200及`application/json`、`no-store`。
这两个请求只验证代理/下载路径；真正签名连接用下文Windows的`-CheckConnection`验收。不需要开放8765或改SSH、防火墙。
未签名响应本身不能证明公网服务器身份，Windows只接受校验通过的签名响应。

### 4. 失败回退

只在需要回退时，将已记录的实际备份目录赋给`PUBLISH_BACKUP`，确认文件属于本次升级前版本，再运行：

```bash
sudo systemctl stop zhigou-publisher
sudo cp "$PUBLISH_BACKUP/publish-server.py" "$PUBLISH_BACKUP/deploy-static.py" /opt/zhigou-publisher/
sudo cp "$PUBLISH_BACKUP/zhigou-docs.conf" /etc/nginx/sites-available/zhigou-docs.conf
sudo nginx -t
sudo systemctl reload nginx
sudo systemctl start zhigou-publisher
```

回退只恢复旧代码与原配置，不删除新增片段、下载、历史或密钥。旧服务不支持新直传客户端，不能误记为新功能通过。

## 首次服务器配置（已装服务跳过）

先按原部署流程更新文档站，使下载页包含实时清单功能。备份原Nginx配置，确认旧站点及下载正常。
上传交付目录的全部文件到私有工作目录，`sha256sum -c CHECKSUMS.sha256`通过后才继续。
以下命令只创建独立服务，不更改SSH、防火墙或其他站点；由管理员人工执行，不在本地PowerShell执行。

检查环境：

```bash
python3 --version
nginx -t
systemctl --version
```

首次安装：若用户、目录、Token或服务单元已存在，先检查所属和内容，不覆盖或重新生成Token。不要重复执行创建步骤。

```bash
sudo useradd --system --user-group --home-dir /var/lib/zhigou-publisher --shell /usr/sbin/nologin zhigou-publisher
sudo install -d -m 0755 /opt/zhigou-publisher
sudo install -m 0644 publish-server.py deploy-static.py /opt/zhigou-publisher/
sudo install -d -o zhigou-publisher -g zhigou-publisher -m 0755 /var/lib/zhigou-publisher
sudo install -d -o root -g zhigou-publisher -m 0750 /etc/zhigou-publisher
sudo python3 -c 'import os,secrets; p="/etc/zhigou-publisher/token"; fd=os.open(p,os.O_WRONLY|os.O_CREAT|os.O_EXCL,0o640); os.write(fd,(secrets.token_hex(32)+"\n").encode()); os.close(fd)'
sudo chown root:zhigou-publisher /etc/zhigou-publisher/token
```

从当前已核对站点复制下载资源，旧静态目录不删除；空间需容纳独立副本。

```bash
SOURCE_RELEASE="$(readlink -f /var/www/zhigou-docs/current)"
sudo -u zhigou-publisher python3 /opt/zhigou-publisher/publish-server.py \
  --data-dir /var/lib/zhigou-publisher --initialize-from "$SOURCE_RELEASE"
sudo install -m 0644 zhigou-publisher.service /etc/systemd/system/zhigou-publisher.service
sudo systemctl daemon-reload
sudo systemctl enable --now zhigou-publisher
sudo systemctl status zhigou-publisher --no-pager
```

必须看到`PUBLISHER_INITIALIZED`和`PUBLISHER_READY: signed protocol v1; loopback only`，否则停止排查，不能以重建Token或删除目录解决。
密钥只通过可信私密方式传到本机文件，不能贴入聊天、仓库或终端日志。服务不提供取回密钥的HTTP接口。

将交付的`nginx-downloads.conf`及`nginx-publish.conf`分别安装到`/etc/nginx/`，在**原智构站点**的`server {}`内部引用这两份片段，格式见升级步骤，不另建重复server_name。
确认没有现存同名location；保留其他配置不动。下载指向持久目录，只有版本化发布路径显式反代本机服务。

```bash
sudo nginx -t
sudo systemctl reload nginx
```

本次单次reload用于启用新的下载映射；之后上传APK不需要reload或重启Nginx。
复查下载页、清单JSON的`no-store`缓存头、APK的MIME/大小/范围及实际SHA-256，确认公网8765不可访问。
配置失败恢复备份的原站点配置，`nginx -t`后reload；旧静态下载和独立目录都保留，不删数据。

## 本机配置和命令

无需SSH。先通过可信的阿里云Workbench文件下载把**现有**密钥保存到电脑私有文件，不重新生成，也不从公网HTTP下载密钥。
若文件管理器不能读取root目录，可在已登录的服务器终端创建仅admin可读的临时副本：

```bash
test ! -e /home/admin/zhigou-publish-token.txt
test ! -L /home/admin/zhigou-publish-token.txt
sudo install -o admin -m 0600 /etc/zhigou-publisher/token /home/admin/zhigou-publish-token.txt
```

通过Workbench私密下载这个文件，保存到电脑私有目录（不是网站或仓库目录）；本机连接确认后可删除**这个临时副本**，不要删除原`/etc/zhigou-publisher/token`。
不要把密钥粘贴给助手或放到Web根目录。拥有该密钥即可授权发布，应限制本机文件读取权限。

在仓库根目录创建已忽略的`publish-apk.local.json`。地址使用当前服务器；`tokenFile`改成电脑上的实际私有路径：

```json
{
  "endpoint": "http://server.example/api/publisher/v1/releases",
  "tokenFile": "C:\\private\\zhigou-publish-token.txt"
}
```

配置和密钥文件仅给当前用户读取。实际发布使用现有Node、Java和Android Build Tools，不自动安装工具。
仅检测连接不需要APK、Java或Android SDK。兼容Windows PowerShell 5和新版PowerShell；保留系统HTTP代理，不改Clash模式。
密钥必须是服务器生成的64位小写十六进制字符串；不作为命令行参数或环境输出。旧SSH配置会明确拒绝。

```powershell
# 仅检测双向签名连接，不上传、发布或安装。
.\publish-apk.cmd -CheckConnection

# 仅本地校验，无连接、上传或发布。
.\publish-apk.cmd -Apk "android/apps/studio-android/build/outputs/apk/debug/studio-android-debug.apk" -CheckOnly

# 明确发布主包。
.\publish-apk.cmd -Apk "安装包路径" -Description "本次更新说明"

# Release优化候选必须显式标记，仍为开发签名、未设备验收。
.\publish-apk.cmd -Apk "ARM64优化包路径" -Candidate -Description "手机候选更新"
.\publish-apk.cmd -Apk "x86_64优化包路径" -Candidate -Alternative -Description "同版本模拟器候选"
```

检测成功显示`CONNECT_OK`；上传成功后脚本再次签名读取清单，确认该包仍是当前选项，显示`PUBLISH_OK`。全程不建立SSH隧道。
它不代表公网Nginx配置或完整下载验收；首次仍需浏览器复查。同代码开发包可以替换网站下载，但不触发App升级。
若连接在提交附近中断，不能保证服务端未提交：先查看下载页/清单，再决定重试；不会自动重试或重复升版。

## 后续更新文档站

持久目录由Nginx单独映射，普通网站部署不会覆盖它。为了防止更新检查和发布并发，先停**发布服务**，不停止Nginx，已发布APK仍可下载。
新版`update-static.py`及同目录`publish-server.py`必须一起上传。

```bash
sudo systemctl stop zhigou-publisher
read -r -p '现有站点server_name: ' SITE_HOST
sudo python3 update-static.py --check --host "$SITE_HOST" \
  --downloads-root /var/lib/zhigou-publisher/downloads
```

记录`CHECK_OK`的当前站点ID和线上APK摘要；随后按交付`DEPLOY.md`执行更新命令，**额外添加**：

```bash
--downloads-root /var/lib/zhigou-publisher/downloads
```

更新或回滚完成并复核后，恢复发布服务：

```bash
sudo systemctl start zhigou-publisher
```

此模式的`expected-current`是持久清单中的真实主APK，不是新网站构建快照。静态站回滚不回滚APK；缺少实时下载页标识的老站点会被拒绝，不能绕过检查强切。
服务仍运行时，跨进程锁会拒绝静态更新，不无限等待。未知文件或残留事务保留现场并阻断启动/发布，不自动扫描清理。

## 故障与边界

- 服务日志只输出固定状态，不记录Token、请求头或原始异常；用`journalctl -u zhigou-publisher`检查。
- `REVISION_CONFLICT`表示别人已发布，刷新核对后再决定；`PUBLISH_BUSY`表示当前上传占用，不排队无限等待。
- `HISTORY_BUDGET_EXCEEDED`不会删除任何旧包；`UNKNOWN_DOWNLOAD_FILES`或中断目录要求管理员检查，不能批量删除未知文件。
- HTTP请求体最长600秒，空闲读取15秒；最多四个连接，一次发布。Python不直接暴露公网，只由指定Nginx片段代理，不是通用文件服务器。
- 一次挑战120秒内使用；进入上传后沿用600秒正文期限，不要求大包在120秒内传完。最多64份挑战，过期/重放/重启后旧票据拒绝，不依赖电脑时钟同步。
- `PUBLISH_RESPONSE_SIGNATURE_INVALID`表示响应不可信（含代理篡改/错误页/旧服务），不是发布成功。不要自动重试；先复核下载页与清单，提交附近断线可能已生效。
- 公开下载仍最多8包/448MiB，具体余量按本次签名读取的真实清单计算；不要把历史交付时的包数与大小当作当前容量。超预算时明确选择历史归档，客户端上传前和服务端都复核，不自动删包。
- 清单切换前错误回滚本请求新文件；进程突然退出或提交后I/O失败可能留下完整新版本或未引用文件，必须先复核，不宣称所有失败都未改变版本。
- 没有新增App安装、强制升级、账号后台、组件/模型下载或任意文件上传；默认full/V4及安装验签不变。
