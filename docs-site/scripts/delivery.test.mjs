import assert from 'node:assert/strict'
import { test } from 'node:test'
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { writeDelivery, deploymentInstructions } from './delivery.mjs'
import { sha256 } from './prepare-download.mjs'

test('handoff binds exact archive, APK and uploaded tool digests without a host or credential', () => {
  const root = mkdtempSync(join(tmpdir(), 'zhigou-delivery-test-'))
  try {
    const archive = join(root, 'site.tar.gz')
    writeFileSync(archive, 'public test archive'); writeFileSync(join(root, 'update-static.py'), 'public test updater')
    const release = { versionName: '0.1.0', sha256: 'a'.repeat(64), history: [] }
    const manifest = writeDelivery(root, archive, release, ['update-static.py'])
    assert.equal(manifest.archive.sha256, sha256(archive))
    assert.equal(manifest.download.sha256, release.sha256)
    assert.equal(manifest.tools[0].sha256, sha256(join(root, 'update-static.py')))
    assert.equal(manifest.publication, 'local-verified-not-remotely-deployed')
    assert.equal(manifest.appUpdate.manifest, '/downloads/release.json')
    assert.equal(manifest.appUpdate.selection, 'current-and-alternatives-only')
    assert.equal(manifest.appUpdate.installation, 'android-system-consent')
    const checksums = readFileSync(join(root, 'CHECKSUMS.sha256'), 'utf8')
    for (const line of checksums.trim().split('\n')) {
      const [hash, file] = line.split('  ')
      assert.equal(hash, sha256(join(root, file)))
    }
    assert.throws(() => writeDelivery(root, archive, release, ['update-static.py']), /EEXIST/)
  } finally { rmSync(root, { recursive: true, force: true }) }
})

test('instructions retain the existing upload directory and bind update/rollback identities', () => {
  const text = deploymentInstructions('site.tar.gz', 'b'.repeat(64), { versionName: '0.1.0', sha256: 'a'.repeat(64) })
  assert.ok(text.includes('/home/admin/zhigou-deploy'))
  assert.ok(text.includes('--check') && text.includes('--expected-current "$OLD_APK_SHA"'))
  assert.ok(text.includes('--expected-current ' + 'a'.repeat(64)))
  assert.ok(text.includes('--sha256 ' + 'b'.repeat(64)))
  assert.ok(!text.includes('--install-nginx') && !text.includes('systemctl restart'))
  assert.ok(text.includes('/downloads/release.json') && text.includes('先手动安装一次'))
})
