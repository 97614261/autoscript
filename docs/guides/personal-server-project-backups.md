# 个人服务器项目备份：一次性部署

本功能是手动快照，不是自动同步。App 入口为「备份管理 → 服务器」；恢复总是创建新项目。
App 入口要求智构 **0.1.16 或更高版本**；服务器需按本指南独立部署一次，仅更新网页不会增加 App 入口。

2026-10-26 用户已提供服务监听及经 Nginx 返回 `401 SIGNATURE_REQUIRED` 的部署证据，
并已从公网复核相同保护响应。这只确认入口及未签名请求拒绝正常，完整上传和恢复仍需在 App 配置后验证。

仅上传已经保存的项目脚本、界面和声明资源，不上传编辑草稿、运行日志和运行数据。
每份 ZIP 最大 128 MiB；服务器最多 32 份、合计 2 GiB，不自动删旧备份。
App 先用 AES-256-GCM 分块加密项目和名称，再通过独立的双向 HMAC 签名接口传输。
服务器持有同一备份密钥，属于可信端；此设计不能防御持有密钥的服务器管理员。
HTTP 仍暴露流量大小/时间，并可能被阻断，不等于 HTTPS。

## 1. 上传小部署包

在项目根运行 `node docs-site/scripts/package-project-backup.mjs` 生成独立部署包。
将输出目录内的 `zhigou-project-backup-server.tar.gz` 和同名 `.sha256` 文件上传到服务器的
`/home/admin/zhigou-deploy/`，在服务器终端执行：

```bash
set -eu
cd /home/admin/zhigou-deploy
sha256sum -c zhigou-project-backup-server.tar.gz.sha256
work="$(mktemp -d /home/admin/zhigou-deploy/backup-service.XXXXXXXX)"
tar -xzf zhigou-project-backup-server.tar.gz -C "$work" --no-same-owner --no-same-permissions
cd "$work"
```

这是独立的新服务，不替换 APK 发布服务，不改网站下载目录、发布密钥或旧工程。
包内有 `publish-server.py` 和 `deploy-static.py`，仅为复用签名协议的私有依赖，不启动另一套 APK 发布器。
代码有变化时才需要重新部署服务；日常备份和恢复不再上传脚本。

## 2. 安装独立服务

服务器已有 Python 3.9+、Nginx，无第三方 Python 依赖：

```bash
id zhigou-backup >/dev/null 2>&1 || sudo useradd --system --user-group \
  --home-dir /var/lib/zhigou-project-backup --shell /usr/sbin/nologin zhigou-backup
sudo install -d -m 0755 /opt/zhigou-project-backup
sudo install -m 0644 project-backup-server.py publish-server.py deploy-static.py /opt/zhigou-project-backup/
sudo install -d -o zhigou-backup -g zhigou-backup -m 0700 /var/lib/zhigou-project-backup
sudo install -d -o root -g zhigou-backup -m 0750 /etc/zhigou-project-backup
```

仅首次生成**新的独立备份密钥**，不覆盖已存在的密钥：

```bash
sudo python3 - <<'PY'
import os, secrets
p = '/etc/zhigou-project-backup/key'
if not os.path.lexists(p):
    fd = os.open(p, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o640)
    with os.fdopen(fd, 'w', encoding='ascii') as out:
        out.write(secrets.token_hex(32) + '\n')
print('BACKUP_KEY_READY: existing key preserved')
PY
sudo chown root:zhigou-backup /etc/zhigou-project-backup/key
sudo chmod 0640 /etc/zhigou-project-backup/key
sudo install -m 0644 zhigou-project-backup.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now zhigou-project-backup
sudo systemctl status zhigou-project-backup --no-pager
```

日志应出现 `PROJECT_BACKUP_READY`。不需要对外开放 8766，也不要把数据目录配置成静态下载目录。
后续更新同一服务代码时，保留密钥和数据，执行 `sudo systemctl restart zhigou-project-backup`。

## 3. Nginx 加一个入口

```bash
sudo install -m 0644 nginx-project-backup.conf /etc/nginx/snippets/zhigou-project-backup.conf
sudoedit /etc/nginx/sites-available/zhigou-docs.conf
```

在现有网站的 **`server { ... }` 内**添加以下一行；不要再套 `server`、不要放到其他 `location` 内：

```nginx
include /etc/nginx/snippets/zhigou-project-backup.conf;
```

保存后：

```bash
sudo nginx -t && sudo systemctl reload nginx
sudo ss -ltnp 'sport = :8766'
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1/api/project-backup/v1/backups -H 'Host: server.example'
```

监听应为 `127.0.0.1:8766`；未签名的 curl 返回 `401` 是正常保护，不代表完整备份已验证。
若 `502`，检查 `sudo journalctl -u zhigou-project-backup -n 30 --no-pager`；配置检查失败时不要 reload。

## 4. App 配置与使用

在自己的服务器终端私下查看备份密钥：

```bash
sudo cat /etc/zhigou-project-backup/key
```

不要把输出发到聊天、工单、日志或网站，不要填 APK 发布密钥。把这份备份密钥另存到可信位置。
App 清数据或卸载后，重新填写同一密钥才可恢复；**不要重新生成密钥来修复连接问题**。

安装包含本功能的新 App 后：

1. 「备份管理 → 服务器 → 设置」填网站根地址（例如 `http://server.example`）和上述独立密钥，保存。
2. 点「刷新列表」验证签名连接。选择本机项目，关闭编辑/配置窗口并停止脚本后，确认上传。
3. 点某份快照的「恢复为新项目」并确认；验证完成后才交给原有严格导入流程，不覆盖原项目、不运行脚本。
4. 删除服务器快照需单独确认，永久删除对应快照，不删本机项目。容量满时由你选择删除，不自动清理。

网络取消时如果服务端已经提交，刷新列表确认，避免反复上传相同内容。
恢复最后的本机导入阶段不可取消，其他网络阶段可取消；离开页面取消网络。
不明文件或崩溃残留会阻断新上传，请人工检查，不要直接递归清理整个目录。

备份服务器自身的数据时，需要同时保管 `/var/lib/zhigou-project-backup/snapshots` 和独立备份密钥。
