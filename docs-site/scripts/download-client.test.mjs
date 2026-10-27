import assert from 'node:assert/strict'
import { test } from 'node:test'
import { readFileSync } from 'node:fs'
import { loadDownloadManifest } from '../shared/download-client.mjs'

const item = { owner: 'zhigou-docs-download-v1', available: true, applicationId: 'com.zhigou.kaifaban', variant: 'debug', profile: 'full', versionName: '0.1.3', versionCode: 4, minSdk: 24, abis: ['arm64-v8a'], bytes: 100, sha256: 'a'.repeat(64), builtAt: '2026-10-21T12:00:00.000Z', url: '/downloads/zhigou-0.1.3-dev-aaaaaaaaaaaa.apk', checksumUrl: '/downloads/zhigou-0.1.3-dev-aaaaaaaaaaaa.apk.sha256', history: [] }
const response = value => new Response(JSON.stringify(value), { headers: { 'content-type': 'application/json; charset=utf-8' } })

test('runtime manifest is revalidated without redirects or cached snapshot fallback', async () => {
  const value = await loadDownloadManifest('/downloads/release.json', { fetcher: async (url, options) => {
    assert.equal(url, '/downloads/release.json'); assert.equal(options.cache, 'no-store'); assert.equal(options.redirect, 'error')
    return response(item)
  } })
  assert.deepEqual(value, item)
})

test('runtime rejects bad MIME, unavailable network and oversized body', async () => {
  for (const fetcher of [async () => new Response('<html>login</html>'), async () => { throw new Error('offline') }, async () => response('x'.repeat(65536))]) {
    await assert.rejects(loadDownloadManifest('/downloads/release.json', { fetcher }))
  }
})

test('runtime rejects foreign URLs and mismatched alternatives, accepts explicit unpublished state', async () => {
  for (const value of [{ ...item, url: 'https://untrusted.invalid/a.apk' }, { ...item, alternatives: [item] }, { available: false, url: '/bad.apk' }]) {
    await assert.rejects(loadDownloadManifest('/downloads/release.json', { fetcher: async () => response(value) }))
  }
  assert.deepEqual(await loadDownloadManifest('/downloads/release.json', { fetcher: async () => response({ available: false }) }), { available: false })
})

test('download page only enables links after refresh, retains snapshot markers and explicit failure', () => {
  const component = readFileSync(new URL('../.vitepress/theme/DownloadPage.vue', import.meta.url), 'utf8')
  assert.match(component, /data-live-downloads="v1"/)
  assert.match(component, /state === 'ready' \? withBase\(item.url\) : undefined/)
  assert.match(component, /state.value = 'error'/)
  assert.match(component, /onBeforeUnmount/)
})
