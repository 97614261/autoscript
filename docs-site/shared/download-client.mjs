import { releasesOf } from './download-contract.mjs'

export async function loadDownloadManifest(url, { fetcher = fetch, signal } = {}) {
  const response = await fetcher(url, { cache: 'no-store', credentials: 'same-origin', redirect: 'error', signal })
  if (!response.ok || response.headers.get('content-type')?.split(';')[0].trim() !== 'application/json') throw new Error('MANIFEST_RESPONSE_INVALID')
  if (!response.body) throw new Error('MANIFEST_RESPONSE_INVALID')
  const reader = response.body.getReader()
  let bytes = 0
  const chunks = []
  try {
    while (true) {
      const { value, done } = await reader.read()
      if (done) break
      bytes += value.byteLength
      if (bytes > 65536) throw new Error('MANIFEST_TOO_LARGE')
      chunks.push(value)
    }
  } catch (error) { await reader.cancel().catch(() => {}); throw error }
  finally { reader.releaseLock() }
  const body = new Uint8Array(bytes)
  let offset = 0
  for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.byteLength }
  const manifest = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(body))
  if (manifest?.available === false && Object.keys(manifest).length === 1) return manifest
  releasesOf(manifest)
  return manifest
}
