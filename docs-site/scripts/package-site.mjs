import { copyFileSync, constants, existsSync, lstatSync, mkdirSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, extname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import { readPreparedDownload, releasesOf, sha256 } from './prepare-download.mjs'
import { writeDelivery } from './delivery.mjs'

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const dist = join(repository, 'docs-site/.vitepress/dist')
const release = readPreparedDownload(join(dist, 'downloads'))
if (!release.available) throw new Error('Deployment package requires a verified APK; run prepare:download first')
for (const page of ['index.html', 'download.html', '404.html', 'api/index.html', 'guide/index.html']) {
  if (!existsSync(join(dist, page))) throw new Error(`Missing built site page: ${page}`)
}
function inspect(directory) {
  for (const name of readdirSync(directory)) {
    const path = join(directory, name), info = lstatSync(path)
    if (info.isSymbolicLink() || name.startsWith('.')) throw new Error('Deployment cannot include links or hidden files')
    if (info.isDirectory()) inspect(path)
    else if (!['.html', '.css', '.js', '.json', '.svg', '.png', '.jpg', '.webp', '.ico', '.woff', '.woff2', '.ttf', '.apk', '.sha256', '.txt'].includes(extname(name))) throw new Error(`Unexpected public file: ${name}`)
  }
}
inspect(dist)
const builtPage = readFileSync(join(dist, 'download.html'), 'utf8')
for (const item of releasesOf(release)) {
  if (!builtPage.includes(item.url) || !builtPage.includes(item.sha256)) throw new Error('Download page does not match the staged APK history')
}
const output = join(repository, 'build/docs-site-deploy')
if (lstatSync(output, { throwIfNoEntry: false })?.isSymbolicLink()) throw new Error('Package output must not be a link')
mkdirSync(output, { recursive: true })
const timestamp = new Date().toISOString().replaceAll(/[-:]/g, '').replace(/\.\d{3}Z$/, 'Z')
const id = `${timestamp}-${release.sha256.slice(0, 12)}`
const delivery = join(output, id)
mkdirSync(delivery, { recursive: false })
const filename = `zhigou-docs-${id}.tar.gz`
const archive = join(delivery, filename)
if (existsSync(archive)) throw new Error('Deployment archive already exists')
const result = spawnSync('tar', ['-czf', archive, '-C', dist, '.'], { stdio: 'inherit', timeout: 120000 })
if (result.error) throw result.error
if (result.status !== 0) throw new Error('Static site archive failed')
const digest = sha256(archive)
const tools = ['deploy-static.py', 'update-static.py', 'publish-server.py']
for (const name of tools) copyFileSync(join(repository, 'docs-site/scripts', name), join(delivery, name), constants.COPYFILE_EXCL)
for (const name of ['zhigou-publisher.service', 'nginx-downloads.conf', 'nginx-publish.conf']) {
  copyFileSync(join(repository, 'docs-site/publisher', name), join(delivery, name), constants.COPYFILE_EXCL)
  tools.push(name)
}
copyFileSync(join(repository, 'docs/guides/apk-http-publishing.md'), join(delivery, 'PUBLISHER.md'), constants.COPYFILE_EXCL)
tools.push('PUBLISHER.md')
writeDelivery(delivery, archive, release, tools)
console.log(`Deployable static site: ${archive}\nBytes: ${statSync(archive).size}\nSHA-256: ${digest}\nAPK: ${release.sha256}\nHandoff: ${join(delivery, 'DEPLOY.md')}\nLocal package only; remote publication has not been performed.`)
