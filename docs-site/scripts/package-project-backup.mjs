import { constants, copyFileSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const sources = new Map([
  ['project-backup-server.py', 'docs-site/scripts/project-backup-server.py'],
  ['publish-server.py', 'docs-site/scripts/publish-server.py'],
  ['deploy-static.py', 'docs-site/scripts/deploy-static.py'],
  ['zhigou-project-backup.service', 'docs-site/backup/zhigou-project-backup.service'],
  ['nginx-project-backup.conf', 'docs-site/backup/nginx-project-backup.conf'],
  ['README.md', 'docs/guides/personal-server-project-backups.md'],
])
const hash = bytes => createHash('sha256').update(bytes).digest('hex')
const output = join(repository, 'build/deliverables')
if (lstatSync(output, { throwIfNoEntry: false })?.isSymbolicLink()) throw new Error('Unsafe package output')
mkdirSync(output, { recursive: true })
const directory = mkdtempSync(join(output, 'project-backup-'))
const checks = []
for (const [name, source] of sources) {
  const path = join(repository, source), info = lstatSync(path)
  if (!info.isFile() || info.isSymbolicLink() || info.size > 1024 * 1024) throw new Error('Unsafe package source')
  const digest = hash(readFileSync(path))
  copyFileSync(path, join(directory, name), constants.COPYFILE_EXCL)
  if (hash(readFileSync(join(directory, name))) !== digest) throw new Error('Source changed while packaging')
  checks.push(`${digest}  ${name}\n`)
}
writeFileSync(join(directory, 'CHECKSUMS.sha256'), checks.join(''), { flag: 'wx' })
const archive = join(directory, 'zhigou-project-backup-server.tar.gz')
// Explicit six-file allowlist: no private config, APK, key or user project data.
const result = spawnSync('tar', ['-czf', archive, '-C', directory, ...sources.keys(), 'CHECKSUMS.sha256'], { encoding: 'utf8', timeout: 30000 })
if (result.status !== 0 || result.error) throw new Error('Backup service archive failed')
const bytes = readFileSync(archive)
if (bytes.length > 1024 * 1024) throw new Error('Deployment bundle too large')
writeFileSync(archive + '.sha256', `${hash(bytes)}  zhigou-project-backup-server.tar.gz\n`, { flag: 'wx' })
console.log(`BACKUP_SERVICE_BUNDLE: ${archive}\nBytes: ${bytes.length}\nNo remote deployment, APK or secrets.`)
