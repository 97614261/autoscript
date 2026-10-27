import { constants, copyFileSync, lstatSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { createHash } from 'node:crypto'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const sources = new Map([
  ['publish-server.py', 'docs-site/scripts/publish-server.py'],
  ['deploy-static.py', 'docs-site/scripts/deploy-static.py'],
  ['update-publisher.py', 'docs-site/scripts/update-publisher.py'],
  ['nginx-publish.conf', 'docs-site/publisher/nginx-publish.conf'],
  ['nginx-downloads.conf', 'docs-site/publisher/nginx-downloads.conf'],
  ['zhigou-publisher.service', 'docs-site/publisher/zhigou-publisher.service'],
  ['UPDATE.md', 'docs/guides/apk-http-publishing.md'],
])
const digest = bytes => createHash('sha256').update(bytes).digest('hex')

export function packagePublisher(output, createdAt = new Date().toISOString()) {
  if (new Date(createdAt).toISOString() !== createdAt) throw new Error('Invalid package timestamp')
  const inventory = [...sources].map(([filename, source]) => {
    const path = join(repository, source), info = lstatSync(path)
    if (!info.isFile() || info.isSymbolicLink() || info.size > 1024 * 1024) throw new Error('Unsafe publisher source')
    const bytes = readFileSync(path)
    return { filename, source, bytes: bytes.length, sha256: digest(bytes) }
  })
  const identity = digest(JSON.stringify(inventory)).slice(0, 12)
  const id = createdAt.replaceAll(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z') + '-' + identity
  const root = resolve(output)
  if (lstatSync(root, { throwIfNoEntry: false })?.isSymbolicLink()) throw new Error('Package output must not be a link')
  mkdirSync(root, { recursive: true })
  const directory = join(root, id)
  mkdirSync(directory)
  for (const item of inventory) {
    copyFileSync(join(repository, item.source), join(directory, item.filename), constants.COPYFILE_EXCL)
    if (digest(readFileSync(join(directory, item.filename))) !== item.sha256) throw new Error('Publisher source changed during packaging')
  }
  const manifest = {
    formatVersion: 1, owner: 'zhigou-publisher-update-v1', createdAt,
    publication: 'local-verified-not-remotely-deployed', protocol: 'signed-http-v1',
    migration: 'preserve-existing-downloads-key-user-unit-and-site', files: inventory,
    features: ['archive-history-v1'],
  }
  const metadata = JSON.stringify(manifest, null, 2) + '\n'
  writeFileSync(join(directory, 'delivery.json'), metadata, { flag: 'wx' })
  const files = [...inventory, { filename: 'delivery.json', sha256: digest(metadata) }]
  writeFileSync(join(directory, 'CHECKSUMS.sha256'), files.map(item => `${item.sha256}  ${item.filename}\n`).join(''), { flag: 'wx' })
  // Explicit inventory only: never archive the source tree, local config, APKs or keys.
  const archive = join(directory, `zhigou-publisher-${id}.tar.gz`)
  const result = spawnSync('tar', ['-czf', archive, '-C', directory, ...files.map(item => item.filename), 'CHECKSUMS.sha256'], { timeout: 30000, encoding: 'utf8' })
  if (result.error || result.status !== 0) throw new Error('Publisher archive failed')
  if (statSync(archive).size > 1024 * 1024) throw new Error('Publisher-only archive exceeds budget')
  const sha256 = digest(readFileSync(archive))
  writeFileSync(archive + '.sha256', `${sha256}  ${basename(archive)}\n`, { flag: 'wx' })
  return { directory, archive, sha256, bytes: statSync(archive).size }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const result = packagePublisher(join(repository, 'build/docs-site-publisher'))
  console.log(`Publisher-only bundle: ${result.archive}\nBytes: ${result.bytes}\nSHA-256: ${result.sha256}\nInstructions: ${join(result.directory, 'UPDATE.md')}\nNo APKs or secrets; no remote deployment performed.`)
}
