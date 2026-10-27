// Shared by the build tools and browser; no Node-only dependencies.
export const applicationId = 'com.zhigou.kaifaban'
export const marker = 'zhigou-docs-download-v1'
export const developmentCertificate = '8e48ea72d827f96ea57c6b7bf4d566bac7581fa23106324bf3296fa9e2024f6c'
export const maxReleases = 8
export const maxDownloadBytes = 448 * 1024 * 1024

export function validateRelease(release) {
  if (release?.owner !== marker || release.available !== true || release.applicationId !== applicationId || !['debug', 'release'].includes(release.variant) || release.profile !== 'full') throw new Error('Invalid download identity')
  if (release.variant === 'release' && (release.status !== 'optimized-candidate-unverified' || release.certificateSha256 !== developmentCertificate || release.abis?.length !== 1)) throw new Error('Invalid optimized candidate identity')
  if (release.variant === 'debug' && (release.status !== undefined || release.certificateSha256 !== undefined)) throw new Error('Invalid debug download status')
  if (!/^[0-9A-Za-z][0-9A-Za-z.+-]{0,39}$/.test(release.versionName) || !Number.isSafeInteger(release.versionCode) || release.versionCode < 1 || release.versionCode > 2147483647 || release.minSdk !== 24) throw new Error('Invalid download version')
  if (!/^[a-f0-9]{64}$/.test(release.sha256) || !Number.isSafeInteger(release.bytes) || release.bytes < 22 || release.bytes > 1024 * 1024 * 1024 || !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/.test(release.builtAt)) throw new Error('Invalid download metadata')
  if (!Array.isArray(release.abis) || !release.abis.length || new Set(release.abis).size !== release.abis.length || release.abis.some(abi => !['arm64-v8a', 'x86_64'].includes(abi))) throw new Error('Invalid download ABI')
  if (release.url !== `/downloads/zhigou-${release.versionName}-dev-${release.sha256.slice(0, 12)}.apk` || release.checksumUrl !== release.url + '.sha256') throw new Error('Invalid download path')
  if (release.description !== undefined && (typeof release.description !== 'string' || !release.description.trim() || release.description.length > 240 || /[\u0000-\u001f]/.test(release.description))) throw new Error('Invalid release description')
  if (release.history !== undefined || release.alternatives !== undefined) throw new Error('Nested release history or alternatives are not allowed')
  return release
}

export function releasesOf(value) {
  const { history = [], alternatives = [], ...current } = value
  if (!Array.isArray(history) || !Array.isArray(alternatives) || history.length + alternatives.length >= maxReleases) throw new Error('Release history exceeds budget')
  const releases = [current, ...alternatives, ...history].map(validateRelease)
  const abis = new Set(current.abis)
  for (const item of alternatives) {
    if (['versionName', 'versionCode', 'variant', 'profile', 'minSdk', 'status', 'certificateSha256'].some(key => item[key] !== current[key]) || item.abis.some(abi => abis.has(abi))) throw new Error('Alternative must be a matching build for a distinct ABI')
    item.abis.forEach(abi => abis.add(abi))
  }
  if (new Set(releases.map(item => item.url)).size !== releases.length) throw new Error('Duplicate release history')
  if (releases.reduce((sum, item) => sum + item.bytes, 0) > maxDownloadBytes) throw new Error('Download history exceeds byte budget; explicitly archive a selected old build first')
  return releases
}
