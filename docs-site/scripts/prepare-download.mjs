import { createHash } from 'node:crypto'
import { constants, closeSync, copyFileSync, existsSync, lstatSync, mkdirSync, openSync, readFileSync, readSync, readdirSync, renameSync, statSync, unlinkSync, writeFileSync } from 'node:fs'
import { basename, dirname, join, parse, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'
import { applicationId, marker, developmentCertificate, maxDownloadBytes, validateRelease, releasesOf } from '../shared/download-contract.mjs'
export { maxReleases, maxDownloadBytes, releasesOf } from '../shared/download-contract.mjs'

const maxApkBytes = 1024 * 1024 * 1024
const siteRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

export function sha256(path) {
  const hash = createHash('sha256'), buffer = Buffer.alloc(1024 * 1024)
  const fd = openSync(path, 'r')
  try {
    let count
    while ((count = readSync(fd, buffer, 0, buffer.length, null)) > 0) hash.update(buffer.subarray(0, count))
    return hash.digest('hex')
  } finally { closeSync(fd) }
}

function noLinks(path) {
  let current = resolve(path)
  while (current !== parse(current).root) {
    if (lstatSync(current, { throwIfNoEntry: false })?.isSymbolicLink()) throw new Error('Download output must not follow links')
    current = dirname(current)
  }
}

// Read only the bounded ZIP central directory, not the APK contents or signing block.
export function apkAbis(path) {
  const size = statSync(path).size
  if (size < 22 || size > maxApkBytes) throw new Error('Invalid APK size')
  const fd = openSync(path, 'r')
  try {
    const tail = Buffer.alloc(Math.min(size, 65557))
    if (readSync(fd, tail, 0, tail.length, size - tail.length) !== tail.length) throw new Error('Truncated APK')
    let end = -1
    for (let pos = tail.length - 22; pos >= 0; pos--) {
      if (tail.readUInt32LE(pos) === 0x06054b50 && pos + 22 + tail.readUInt16LE(pos + 20) === tail.length) { end = pos; break }
    }
    if (end < 0 || tail.readUInt16LE(end + 4) !== 0 || tail.readUInt16LE(end + 6) !== 0) throw new Error('Unsupported APK ZIP')
    const entries = tail.readUInt16LE(end + 10), length = tail.readUInt32LE(end + 12), offset = tail.readUInt32LE(end + 16)
    if (!entries || entries === 65535 || entries !== tail.readUInt16LE(end + 8) || length > 16 * 1024 * 1024 || offset + length !== size - tail.length + end) throw new Error('Invalid APK directory')
    const central = Buffer.alloc(length)
    if (readSync(fd, central, 0, length, offset) !== length) throw new Error('Truncated APK directory')
    let pos = 0
    const abis = new Set(), names = new Set()
    for (let index = 0; index < entries; index++) {
      if (pos + 46 > length || central.readUInt32LE(pos) !== 0x02014b50) throw new Error('Invalid APK entry')
      const nameLength = central.readUInt16LE(pos + 28), extra = central.readUInt16LE(pos + 30), comment = central.readUInt16LE(pos + 32)
      const next = pos + 46 + nameLength + extra + comment
      if (next > length) throw new Error('Truncated APK entry')
      const name = central.toString('utf8', pos + 46, pos + 46 + nameLength)
      if (names.has(name)) throw new Error('Duplicate APK entry')
      names.add(name)
      const match = /^lib\/([^/]+)\/libengine_jni\.so$/.exec(name)
      if (match) abis.add(match[1])
      pos = next
    }
    if (pos !== length || !names.has('AndroidManifest.xml') || !abis.size || [...abis].some(abi => !['arm64-v8a', 'x86_64'].includes(abi))) throw new Error('Not a supported Studio APK')
    if (!names.has('assets/vision/en_PP-OCRv4_rec_mobile.onnx') || !names.has('assets/vision/en_PP-OCRv5_rec_mobile.onnx') || [...abis].some(abi => ['libopencv_java4.so', 'libonnxruntime.so', 'libonnxruntime4j_jni.so'].some(name => !names.has(`lib/${abi}/${name}`)))) throw new Error('Expected full visual profile APK')
    return [...abis].sort()
  } finally { closeSync(fd) }
}

export function readPreparedDownload(directory) {
  noLinks(directory)
  if (!existsSync(directory)) return { available: false }
  const manifest = join(directory, 'release.json')
  if (!existsSync(manifest)) throw new Error('Unowned download directory')
  noLinks(manifest)
  if (statSync(manifest).size > 65536) throw new Error('Download manifest too large')
  const value = JSON.parse(readFileSync(manifest, 'utf8'))
  const releases = releasesOf(value)
  const allowed = ['release.json', ...releases.flatMap(item => [basename(item.url), basename(item.checksumUrl)])]
  if (readdirSync(directory).some(name => !allowed.includes(name))) throw new Error('Unknown download output; inspect manually before publishing')
  for (const release of releases) {
  const apk = join(directory, basename(release.url)), checksum = join(directory, basename(release.checksumUrl))
  noLinks(apk); noLinks(checksum)
  if (statSync(apk).size !== release.bytes || sha256(apk) !== release.sha256) throw new Error('Prepared APK digest mismatch')
  if (JSON.stringify(apkAbis(apk)) !== JSON.stringify(release.abis)) throw new Error('Prepared APK ABI mismatch')
  if (statSync(checksum).size > 1024 || readFileSync(checksum, 'utf8') !== `${release.sha256}  ${basename(release.url)}\n`) throw new Error('APK checksum file mismatch')
  }
  return { ...releases[0], ...(value.alternatives === undefined ? {} : { alternatives: value.alternatives }), history: value.history ?? [] }
}

export function describeDownload(apk, metadataPath, expectedSha, description, { candidate = false } = {}) {
  apk = resolve(apk); metadataPath = resolve(metadataPath)
  if (!/^[a-f0-9]{64}$/.test(expectedSha)) throw new Error('Explicit verified SHA-256 is required')
  const metadata = JSON.parse(readFileSync(metadataPath, 'utf8'))
  if (metadata.applicationId !== applicationId || metadata.variantName !== (candidate ? 'release' : 'debug') || metadata.elements?.length !== 1) throw new Error('Expected Studio debug metadata, or explicitly opted-in Release candidate metadata')
  const element = metadata.elements[0]
  if (element.outputFile !== basename(apk) || dirname(apk) !== dirname(metadataPath)) throw new Error('APK must belong to adjacent Gradle metadata')
  if (sha256(apk) !== expectedSha) throw new Error('Source APK digest mismatch')
  return validateRelease({ owner: marker, available: true, applicationId, variant: metadata.variantName, profile: 'full',
    ...(candidate ? { status: 'optimized-candidate-unverified', certificateSha256: developmentCertificate } : {}),
    versionName: element.versionName, versionCode: element.versionCode, minSdk: metadata.minSdkVersionForDexing,
    abis: apkAbis(apk), bytes: statSync(apk).size, sha256: expectedSha, builtAt: statSync(apk).mtime.toISOString(),
    url: `/downloads/zhigou-${element.versionName}-dev-${expectedSha.slice(0, 12)}.apk`,
    checksumUrl: `/downloads/zhigou-${element.versionName}-dev-${expectedSha.slice(0, 12)}.apk.sha256`,
    ...(description === undefined ? {} : { description }) })
}

export function prepareDownload(apk, metadataPath, expectedSha, directory = join(siteRoot, 'src/public/downloads'), description, { candidate = false, alternative = false } = {}) {
  apk = resolve(apk)
  const release = describeDownload(apk, metadataPath, expectedSha, description, { candidate })
  const previous = readPreparedDownload(directory)
  if (previous.available && release.versionCode < previous.versionCode) throw new Error('Update versionCode must not decrease; use an explicit site rollback instead')
  const known = previous.available ? releasesOf(previous) : []
  const existing = known.find(item => item.url === release.url)
  const current = { ...release, builtAt: existing?.builtAt ?? release.builtAt,
    description: description ?? existing?.description ?? '智构开发测试构建，升级前请备份重要项目。' }
  if (alternative && !previous.available) throw new Error('An alternative requires an existing primary download')
  const { history: oldHistory, alternatives: oldAlternatives, ...oldCurrent } = previous
  const alternatives = alternative
    ? [...(oldAlternatives ?? []).filter(item => item.url !== release.url), current]
    : previous.url === current.url ? oldAlternatives ?? [] : []
  const primary = alternative ? oldCurrent : current
  const activeUrls = new Set([primary, ...alternatives].map(item => item.url))
  const history = known.filter(item => !activeUrls.has(item.url)).map(item => ({ ...item,
    description: item.description ?? '历史开发构建，保留用于版本回溯与兼容验证。' }))
  const manifestValue = { ...primary, ...(alternatives.length ? { alternatives } : {}), history }
  releasesOf(manifestValue) // Fail before writing or deleting anything when the history budget is full.
  mkdirSync(directory, { recursive: true })
  const target = join(directory, basename(release.url))
  const checksum = join(directory, basename(release.checksumUrl))
  const temporaryManifest = join(directory, 'release.json.tmp')
  const created = []
  try {
    if (!existing) { copyFileSync(apk, target, constants.COPYFILE_EXCL); created.push(target) }
    if (sha256(target) !== expectedSha) throw new Error('Copied APK digest mismatch')
    if (!existing) {
      writeFileSync(checksum, `${expectedSha}  ${basename(release.url)}\n`, { encoding: 'utf8', flag: 'wx' })
      created.push(checksum)
    }
    writeFileSync(temporaryManifest, JSON.stringify(manifestValue, null, 2) + '\n', { encoding: 'utf8', flag: 'wx' })
    created.push(temporaryManifest)
    renameSync(temporaryManifest, join(directory, 'release.json'))
  } catch (error) {
    // Only roll back files created by this invocation, never a previous build or unknown data.
    for (const path of created.reverse()) if (existsSync(path)) unlinkSync(path)
    throw error
  }
  return readPreparedDownload(directory)
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const { values } = parseArgs({ options: { apk: { type: 'string' }, metadata: { type: 'string' }, sha256: { type: 'string' }, description: { type: 'string' }, candidate: { type: 'boolean', default: false }, alternative: { type: 'boolean', default: false } } })
  if (!values.apk || !values.sha256) throw new Error('Usage: npm run prepare:download -- --apk <verified Studio APK> --sha256 <verified digest> [--candidate] [--alternative]')
  const release = prepareDownload(values.apk, values.metadata ?? join(dirname(resolve(values.apk)), 'output-metadata.json'), values.sha256, undefined, values.description, values)
  console.log(`Prepared ${releasesOf(release).length} verified download files; primary ${release.abis.join(', ')}; version ${release.versionName} / ${release.versionCode}; SHA-256 ${release.sha256}\nApp update source: /downloads/release.json (current + alternatives only; history is not an upgrade)\nOnly a greater numeric versionCode is an App upgrade; publish this package to activate the website manifest.`)
}
