import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { spawnSync } from 'node:child_process'
import { test } from 'node:test'
import { packagePublisher } from './package-publisher.mjs'

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

test('publisher-only delivery binds exact sources and excludes credentials and APKs', () => {
  const temporary = mkdtempSync(join(tmpdir(), 'zhigou-publisher-package-'))
  try {
    const result = packagePublisher(temporary, '2026-10-22T00:00:00.000Z')
    const manifest = JSON.parse(readFileSync(join(result.directory, 'delivery.json'), 'utf8'))
    assert.equal(manifest.publication, 'local-verified-not-remotely-deployed')
    assert.equal(manifest.files.length, 7)
    assert.deepEqual(manifest.features, ['archive-history-v1'])
    const checks = readFileSync(join(result.directory, 'CHECKSUMS.sha256'), 'utf8').trim().split('\n')
    for (const line of checks) {
      const [hash, name] = line.split('  ')
      assert.equal(createHash('sha256').update(readFileSync(join(result.directory, name))).digest('hex'), hash)
    }
    for (const item of manifest.files) {
      assert.deepEqual(readFileSync(join(result.directory, item.filename)), readFileSync(join(repository, item.source)))
    }
    const listing = spawnSync('tar', ['-tzf', result.archive], { encoding: 'utf8' })
    assert.equal(listing.status, 0)
    assert.deepEqual(listing.stdout.trim().split(/\r?\n/).sort(), [...manifest.files.map(item => item.filename), 'delivery.json', 'CHECKSUMS.sha256'].sort())
    assert.ok(result.bytes < 1024 * 1024)
    assert.equal(readFileSync(result.archive + '.sha256', 'utf8'), `${result.sha256}  ${basename(result.archive)}\n`)
    assert.equal(readdirSync(result.directory).length, 11)
    const checked = spawnSync(process.platform === 'win32' ? 'python' : 'python3', [join(result.directory, 'update-publisher.py'), '--check-bundle'], { encoding: 'utf8', timeout: 10000 })
    assert.equal(checked.status, 0, checked.stdout + checked.stderr)
    assert.match(checked.stdout, /PUBLISHER_BUNDLE_OK/)
    assert.throws(() => packagePublisher(temporary, '2026-10-22T00:00:00.000Z'), /exist/i)
  } finally { rmSync(temporary, { recursive: true, force: true }) }
})

test('publisher package refuses invalid timestamps', () => {
  assert.throws(() => packagePublisher('unused', 'not-a-date'))
  assert.throws(() => packagePublisher('unused', '2026-10-22'))
})
