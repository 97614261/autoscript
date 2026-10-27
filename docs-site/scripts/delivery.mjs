import { statSync, writeFileSync } from 'node:fs'
import { basename, join } from 'node:path'
import { sha256 } from './prepare-download.mjs'

export function deploymentInstructions(archive, digest, release) {
  return `# 智构文档站更新交接

当前 APK：${release.versionName} 开发测试版，构建 ${release.sha256.slice(0, 12)}。
APK SHA-256：${release.sha256}
App 更新版本：${release.versionName} / versionCode ${release.versionCode ?? '以清单为准'}。
${[release, ...(release.alternatives ?? [])].map(item => `- ${item.abis?.join(' / ') ?? 'APK'}: ${item.sha256}${item.variant === 'release' ? ' (Release 优化候选，开发签名，未设备验收)' : ''}`).join('\n')}
站点压缩包：${archive}
压缩包 SHA-256：${digest}

这是本地冻结产物，不表示服务器已更新。仅用于现有独立智构站点。
不要把网站压缩包当作用户项目备份；不要改 Nginx/SSH/防火墙。

若已启用独立APK发布服务，请改用同目录PUBLISHER.md的“后续更新文档站”流程。
下面默认命令适用于静态下载；它不会覆盖独立下载目录，但会因线上清单不同而拒绝更新。
发布服务启用后，静态站回滚不回滚APK，也不应把本次构建快照摘要当作线上最新APK。

## 1. 上传与校验

把本目录的全部文件上传到服务器私有工作目录。
已有 /home/admin/zhigou-deploy 可继续使用，不需要换目录。
建议上传到该目录下独立的本批子目录，然后 cd 进入实际上传目录；下面命令在该目录执行，不再切回父目录，也不要混入旧交付文件。
下面操作需在服务器 Linux Shell 执行，不是在本地 PowerShell。

\`\`\`bash
sha256sum -c CHECKSUMS.sha256
read -r -p '现有 Nginx 站点的 server_name（IP 或域名，不含 http://）: ' SITE_HOST
sudo python3 update-static.py --check --host "$SITE_HOST"
\`\`\`

必须看到 CHECK_OK。记录 current= 后的旧 release ID 和 apk= 后的完整摘要。
若检查失败，先排查，不能改用首次部署脚本覆盖站点。

## 2. 更新

\`\`\`bash
read -r -p 'CHECK_OK 中的旧 APK 完整 SHA-256: ' OLD_APK_SHA
read -r -p 'CHECK_OK 中的旧 current release ID: ' OLD_SITE_RELEASE
sudo python3 update-static.py --archive ${archive} \\
  --sha256 ${digest} \\
  --expected-current "$OLD_APK_SHA" --expected-release "$OLD_SITE_RELEASE" --host "$SITE_HOST"
\`\`\`

UPDATE_OK 才表示服务器本机的清单、页面、全部下载包 MIME/大小/范围和404通过。
保存 PREVIOUS_RELEASE= 后的旧目录名。脚本原子切换 current，不删除旧目录，不重启服务。
失败自动恢复原链接并复核；若显示 ACTIVATION_AND_RESTORE_FAILED，保留现场，检查 current 和 Nginx，不反复执行。

## 3. 公网复查

从浏览器打开实际站点的 /download、/guide/editor-workflow、/releases/。
下载页当前摘要必须为 ${release.sha256.slice(0, 12)} 开头。
复查 /downloads/release.json：当前 versionCode 必须为 ${release.versionCode ?? '本批实际值'}，架构候选在 alternatives，历史构建不参与 App 更新。
清单 Content-Type 必须是 application/json，Cache-Control 必须允许重新验证，不重定向到登录页或其他站点。
App 0.1.2 / 3 起支持前台自动检查及确认后的 App 内下载；更早版本须先手动安装一次。
自动检查每六小时一次，验收时可用“我的 → 关于智构 → 检查更新”立即检查。
相同版本代码不是新版本；回滚网站不会降级已安装 App，也不会强制用户更新。
服务器本机检查不能替代公网连通、DNS、TLS 和完整公网下载校验。

## 4. 显式回滚

仅选 PREVIOUS_RELEASE 指向的已保留完整目录，不填路径或 current。

\`\`\`bash
read -r -p 'PREVIOUS_RELEASE 的旧 release ID: ' PREVIOUS_RELEASE
read -r -p 'UPDATE_OK 中的新 current release ID: ' CURRENT_RELEASE
sudo python3 update-static.py --rollback "$PREVIOUS_RELEASE" \\
  --expected-current ${release.sha256} --expected-release "$CURRENT_RELEASE" --host "$SITE_HOST"
\`\`\`

ROLLBACK_OK 才表示旧版本机检查通过。回滚仅切换网站和下载包，不降级已安装 App 或改项目。
若网站已再次更新，旧的 expected-current 会拒绝；重新 --check 后人工确认。
首次全新服务器才使用 deploy-static.py，不能用它更新已有站点。
`
}

export function writeDelivery(directory, archive, release, tools) {
  const digest = sha256(archive), archiveName = basename(archive)
  writeFileSync(archive + '.sha256', `${digest}  ${archiveName}\n`, { flag: 'wx' })
  const manifest = {
    formatVersion: 1, owner: 'zhigou-docs-delivery-v1', createdAt: new Date().toISOString(),
    publication: 'local-verified-not-remotely-deployed',
    archive: { filename: archiveName, bytes: statSync(archive).size, sha256: digest },
    download: release,
    appUpdate: { manifest: '/downloads/release.json', comparison: 'numeric-versionCode',
      selection: 'current-and-alternatives-only', bootstrap: '0.1.2 / 3',
      download: 'in-app-after-confirmation', installation: 'android-system-consent' },
    tools: tools.map(name => ({ filename: name, sha256: sha256(join(directory, name)) })),
    signing: 'Development certificate; APK signature checked separately; not a production release',
    boundaries: ['Optimized Release candidates are not device-accepted', 'No silent installation or component downloader', 'Remote HTTP/HTTPS acceptance is separate']
  }
  writeFileSync(join(directory, 'delivery.json'), JSON.stringify(manifest, null, 2) + '\n', { flag: 'wx' })
  writeFileSync(join(directory, 'DEPLOY.md'), deploymentInstructions(archiveName, digest, release), { flag: 'wx' })
  const names = [archiveName, archiveName + '.sha256', ...tools, 'delivery.json', 'DEPLOY.md']
  writeFileSync(join(directory, 'CHECKSUMS.sha256'), names.map(name => `${sha256(join(directory, name))}  ${name}\n`).join(''), { flag: 'wx' })
  return manifest
}
