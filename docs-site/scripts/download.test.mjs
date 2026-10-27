import assert from 'node:assert/strict'
import { test } from 'node:test'
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, existsSync, rmSync, symlinkSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { apkAbis, prepareDownload, readPreparedDownload, releasesOf, sha256, maxReleases } from './prepare-download.mjs'
import { archiveDownload } from './archive-download.mjs'

// Only ZIP inventory test data; not an executable or signed Android package.
function fakeApk(path, { abi = 'arm64-v8a', full = true } = {}) {
  const names = ['AndroidManifest.xml', `lib/${abi}/libengine_jni.so`]
  if (full) names.push('assets/vision/en_PP-OCRv4_rec_mobile.onnx', 'assets/vision/en_PP-OCRv5_rec_mobile.onnx', ...['libopencv_java4.so', 'libonnxruntime.so', 'libonnxruntime4j_jni.so'].map(name => `lib/${abi}/${name}`))
  const records = names.map(name => {
    const header = Buffer.alloc(46)
    header.writeUInt32LE(0x02014b50); header.writeUInt16LE(Buffer.byteLength(name), 28)
    return Buffer.concat([header, Buffer.from(name)])
  })
  const central = Buffer.concat(records), end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50); end.writeUInt16LE(names.length, 8); end.writeUInt16LE(names.length, 10); end.writeUInt32LE(central.length, 12)
  writeFileSync(path, Buffer.concat([central, end]))
}

function isolated(action) {
  const root = mkdtempSync(join(tmpdir(), 'zhigou-download-test-')), apk = join(root, 'studio.apk'), metadata = join(root, 'output-metadata.json'), output = join(root, 'downloads')
  fakeApk(apk)
  writeFileSync(metadata, JSON.stringify({ applicationId: 'com.zhigou.kaifaban', variantName: 'debug', minSdkVersionForDexing: 24, elements: [{ outputFile: 'studio.apk', versionName: '0.1.0', versionCode: 1 }] }))
  try { action({ root, apk, metadata, output }) } finally { rmSync(root, { recursive: true, force: true }) }
}

test('site without a staged APK has no pretend download', () => isolated(({ output }) => {
  assert.deepEqual(readPreparedDownload(output), { available: false })
}))

test('preparation derives version, exact bytes, ABI, immutable URL and checksum', () => isolated(({ apk, metadata, output }) => {
  const release = prepareDownload(apk, metadata, sha256(apk), output)
  assert.equal(release.versionName, '0.1.0')
  assert.deepEqual(release.abis, ['arm64-v8a'])
  assert.match(release.url, /^\/downloads\/zhigou-0\.1\.0-dev-[a-f0-9]{12}\.apk$/)
  assert.equal(release.sha256, sha256(apk))
  assert.deepEqual(prepareDownload(apk, metadata, sha256(apk), output), release)
}))

test('optimized candidates require explicit opt-in and distinct current architecture choices', () => isolated(({ root, apk, metadata, output }) => {
  const old = prepareDownload(apk, metadata, sha256(apk), output)
  const value = JSON.parse(readFileSync(metadata, 'utf8')); value.variantName = 'release'
  value.elements[0].versionName = '0.1.1'; writeFileSync(metadata, JSON.stringify(value))
  assert.throws(() => prepareDownload(apk, metadata, sha256(apk), output), /explicitly opted-in/)
  const primary = prepareDownload(apk, metadata, sha256(apk), output, 'ARM candidate', { candidate: true })
  assert.equal(primary.variant, 'release')
  assert.equal(primary.status, 'optimized-candidate-unverified')
  assert.equal(primary.history[0].url, old.url)
  fakeApk(apk, { abi: 'x86_64' })
  const next = prepareDownload(apk, metadata, sha256(apk), output, 'Emulator candidate', { candidate: true, alternative: true })
  assert.equal(next.sha256, primary.sha256)
  assert.deepEqual(next.alternatives[0].abis, ['x86_64'])
  assert.deepEqual(next.history, primary.history)
  assert.equal(releasesOf(next).length, 3)
  assert.deepEqual(prepareDownload(apk, metadata, sha256(apk), output, undefined, { candidate: true, alternative: true }), next)
  fakeApk(apk)
  assert.deepEqual(prepareDownload(apk, metadata, sha256(apk), output, undefined, { candidate: true }), next)
  assert.throws(() => archiveDownload(output, next.alternatives[0].sha256, join(root, 'archive')), /historical/)
  writeFileSync(join(output, next.alternatives[0].url.split('/').at(-1)), 'tampered')
  assert.throws(() => readPreparedDownload(output), /digest mismatch/)
}))

test('alternatives cannot duplicate architecture, mismatch version or contain nested downloads', () => isolated(({ apk, metadata, output }) => {
  assert.throws(() => prepareDownload(apk, metadata, sha256(apk), output, undefined, { alternative: true }), /existing primary/)
  const primary = prepareDownload(apk, metadata, sha256(apk), output)
  const { history, ...leaf } = primary
  for (const item of [{ ...leaf }, { ...leaf, abis: ['x86_64'], versionCode: 2 }, { ...leaf, alternatives: [] }]) {
    assert.throws(() => releasesOf({ ...primary, alternatives: [item] }), /distinct ABI|Nested/)
  }
  assert.throws(() => releasesOf({ ...primary, alternatives: 'bad' }), /budget/)
  assert.throws(() => releasesOf({ ...primary, alternatives: Array(maxReleases).fill(leaf) }), /budget/)
  assert.throws(() => releasesOf({ ...primary, variant: 'release' }), /candidate identity/)
  assert.deepEqual(readPreparedDownload(output), primary)
}))

test('incorrect digest and mismatched Gradle metadata fail before creating outputs', () => isolated(({ apk, metadata, output }) => {
  assert.throws(() => prepareDownload(apk, metadata, '0'.repeat(64), output), /digest mismatch/)
  const value = JSON.parse(readFileSync(metadata, 'utf8')); value.applicationId = 'other.package'
  writeFileSync(metadata, JSON.stringify(value))
  assert.throws(() => prepareDownload(apk, metadata, sha256(apk), output), /Studio debug/)
  assert.equal(existsSync(output), false)
}))

test('update publication refuses numeric downgrade without changing the current manifest', () => isolated(({ apk, metadata, output }) => {
  const value = JSON.parse(readFileSync(metadata, 'utf8'))
  value.elements[0].versionName = '0.1.2'; value.elements[0].versionCode = 3
  writeFileSync(metadata, JSON.stringify(value))
  const current = prepareDownload(apk, metadata, sha256(apk), output)
  value.elements[0].versionName = '0.1.1'; value.elements[0].versionCode = 2
  writeFileSync(metadata, JSON.stringify(value))
  assert.throws(() => prepareDownload(apk, metadata, sha256(apk), output), /versionCode must not decrease/)
  assert.deepEqual(readPreparedDownload(output), current)
  assert.throws(() => releasesOf({ ...current, versionCode: 2147483648 }), /version/)
}))

test('APK inventory rejects unsupported ABIs, partial profiles and truncated input', () => isolated(({ apk }) => {
  fakeApk(apk, { abi: 'armeabi-v7a' }); assert.throws(() => apkAbis(apk), /supported Studio/)
  fakeApk(apk, { full: false }); assert.throws(() => apkAbis(apk), /full visual/)
  writeFileSync(apk, Buffer.alloc(30)); assert.throws(() => apkAbis(apk), /ZIP/)
}))

test('tampered prepared APK cannot be published', () => isolated(({ apk, metadata, output }) => {
  const release = prepareDownload(apk, metadata, sha256(apk), output)
  writeFileSync(join(output, release.url.split('/').at(-1)), 'tampered')
  assert.throws(() => readPreparedDownload(output), /digest mismatch/)
}))

test('publishing preserves real previous APKs, descriptions and checksums', () => isolated(({ apk, metadata, output }) => {
  const previous = prepareDownload(apk, metadata, sha256(apk), output, '首次公开开发构建。')
  const value = JSON.parse(readFileSync(metadata, 'utf8')); value.elements[0].versionName = '0.1.1'
  writeFileSync(metadata, JSON.stringify(value))
  const next = prepareDownload(apk, metadata, sha256(apk), output)
  assert.equal(next.versionName, '0.1.1')
  assert.equal(existsSync(join(output, previous.url.split('/').at(-1))), true)
  assert.equal(existsSync(join(output, previous.checksumUrl.split('/').at(-1))), true)
  assert.equal(next.history.length, 1)
  assert.equal(next.history[0].description, '首次公开开发构建。')
  assert.equal(next.history[0].url, previous.url)
  assert.deepEqual(prepareDownload(apk, metadata, sha256(apk), output), next)
  assert.deepEqual(readPreparedDownload(output), next)
}))

test('history budget rejects new releases without deleting old downloads', () => isolated(({ apk, metadata, output }) => {
  const value = JSON.parse(readFileSync(metadata, 'utf8'))
  for (let index = 0; index < maxReleases; index++) {
    value.elements[0].versionName = `0.1.${index}`; writeFileSync(metadata, JSON.stringify(value))
    prepareDownload(apk, metadata, sha256(apk), output)
  }
  const before = readPreparedDownload(output)
  value.elements[0].versionName = '0.2.0'; writeFileSync(metadata, JSON.stringify(value))
  assert.throws(() => prepareDownload(apk, metadata, sha256(apk), output), /history exceeds budget/)
  assert.deepEqual(readPreparedDownload(output), before)
}))

test('invalid or nested history and duplicate URLs cannot publish', () => isolated(({ apk, metadata, output }) => {
  const release = prepareDownload(apk, metadata, sha256(apk), output)
  for (const history of ['bad', [{ ...release, history: [] }], [((({ history, ...leaf }) => leaf)(release))]]) {
    writeFileSync(join(output, 'release.json'), JSON.stringify({ ...release, history }))
    assert.throws(() => readPreparedDownload(output), /history|Nested|Duplicate/)
  }
}))

test('tampered historical APKs are rejected as well as the current one', () => isolated(({ apk, metadata, output }) => {
  const old = prepareDownload(apk, metadata, sha256(apk), output)
  const value = JSON.parse(readFileSync(metadata, 'utf8')); value.elements[0].versionName = '0.1.1'; writeFileSync(metadata, JSON.stringify(value))
  prepareDownload(apk, metadata, sha256(apk), output)
  writeFileSync(join(output, old.url.split('/').at(-1)), 'tampered')
  assert.throws(() => readPreparedDownload(output), /digest mismatch/)
}))

test('unknown files and manifests are preserved and block publishing', () => isolated(({ apk, metadata, output }) => {
  mkdirSync(output); writeFileSync(join(output, 'keep.txt'), 'user data')
  assert.throws(() => prepareDownload(apk, metadata, sha256(apk), output), /Unowned/)
  assert.equal(readFileSync(join(output, 'keep.txt'), 'utf8'), 'user data')
}))

test('manifest URL cannot escape its owned download directory', () => isolated(({ apk, metadata, output }) => {
  const release = prepareDownload(apk, metadata, sha256(apk), output)
  writeFileSync(join(output, 'release.json'), JSON.stringify({ ...release, url: '/downloads/../../secret.apk' }))
  assert.throws(() => readPreparedDownload(output), /download path/)
}))

test('download staging rejects linked output directories', () => isolated(({ root, apk, metadata, output }) => {
  const outside = join(root, 'outside'); mkdirSync(outside)
  symlinkSync(outside, output, process.platform === 'win32' ? 'junction' : 'dir')
  assert.throws(() => prepareDownload(apk, metadata, sha256(apk), output), /links/)
  assert.equal(existsSync(join(outside, 'release.json')), false)
}))

test('explicit archival preserves the selected APK outside the public site', () => isolated(({ root, apk, metadata, output }) => {
  const old = prepareDownload(apk, metadata, sha256(apk), output)
  fakeApk(apk, { abi: 'x86_64' })
  const next = prepareDownload(apk, metadata, sha256(apk), output)
  const destination = join(root, 'archive')
  const result = archiveDownload(output, old.sha256, destination)
  assert.equal(result.sha256, next.sha256)
  assert.equal(result.history.length, 0)
  assert.equal(existsSync(join(output, old.url.split('/').at(-1))), false)
  assert.equal(sha256(join(destination, old.url.split('/').at(-1))), old.sha256)
  assert.equal(JSON.parse(readFileSync(join(destination, 'release-before.json'))).history[0].sha256, old.sha256)
}))

test('archival rejects current, unknown and public destinations without mutation', () => isolated(({ root, apk, metadata, output }) => {
  const current = prepareDownload(apk, metadata, sha256(apk), output)
  assert.throws(() => archiveDownload(output, current.sha256, join(root, 'archive')), /current/)
  assert.throws(() => archiveDownload(output, '0'.repeat(64), join(root, 'archive')), /exactly one/)
  assert.throws(() => archiveDownload(output, '0'.repeat(64), join(output, 'archive')), /outside/)
  assert.deepEqual(readPreparedDownload(output), current)
}))

test('archival refuses an existing destination and preserves a busy transaction', () => isolated(({ root, apk, metadata, output }) => {
  const old = prepareDownload(apk, metadata, sha256(apk), output)
  fakeApk(apk, { abi: 'x86_64' })
  const next = prepareDownload(apk, metadata, sha256(apk), output)
  const destination = join(root, 'archive'); mkdirSync(destination)
  assert.throws(() => archiveDownload(output, old.sha256, destination), /already exists/)
  writeFileSync(join(output, 'release.json.tmp'), 'busy')
  assert.throws(() => archiveDownload(output, old.sha256, join(root, 'other')), /Unknown download/)
  assert.equal(readFileSync(join(output, 'release.json.tmp'), 'utf8'), 'busy')
  assert.equal(existsSync(join(output, next.url.split('/').at(-1))), true)
}))
