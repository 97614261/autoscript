import { constants, copyFileSync, existsSync, lstatSync, mkdirSync, readFileSync, renameSync, unlinkSync, writeFileSync } from 'node:fs'
import { basename, dirname, join, parse, resolve, sep } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { parseArgs } from 'node:util'
import { readPreparedDownload, sha256 } from './prepare-download.mjs'

const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

export function archiveDownload(directory, expectedSha, destination) {
  directory = resolve(directory); destination = resolve(destination)
  if (!/^[a-f0-9]{64}$/.test(expectedSha)) throw new Error('Explicit historical APK SHA-256 required')
  if (destination === directory || destination.startsWith(directory + sep)) throw new Error('Archive must be outside public downloads')
  for (let path = destination; path !== parse(path).root; path = dirname(path)) {
    if (lstatSync(path, { throwIfNoEntry: false })?.isSymbolicLink()) throw new Error('Archive must not follow links')
  }
  if (existsSync(destination)) throw new Error('Archive destination already exists; inspect it before retrying')
  const release = readPreparedDownload(directory)
  if (!release.available || release.sha256 === expectedSha) throw new Error('Cannot archive the current or an unavailable download')
  const selected = release.history.filter(item => item.sha256 === expectedSha)
  if (selected.length !== 1) throw new Error('Select exactly one historical APK digest')
  const item = selected[0], temporary = join(directory, 'release.json.tmp')
  const next = { ...release, history: release.history.filter(value => value !== item) }
  // Reserve the same transaction filename used by preparation before moving files.
  writeFileSync(temporary, JSON.stringify(next, null, 2) + '\n', { encoding: 'utf8', flag: 'wx' })
  try {
    mkdirSync(destination, { recursive: true })
    for (const url of [item.url, item.checksumUrl]) copyFileSync(join(directory, basename(url)), join(destination, basename(url)), constants.COPYFILE_EXCL)
    if (sha256(join(destination, basename(item.url))) !== expectedSha) throw new Error('Archived APK digest mismatch')
    writeFileSync(join(destination, 'release-before.json'), readFileSync(join(directory, 'release.json')), { flag: 'wx' })
    writeFileSync(join(destination, 'archived-release.json'), JSON.stringify(item, null, 2) + '\n', { flag: 'wx' })
    renameSync(temporary, join(directory, 'release.json'))
  } catch (error) {
    if (existsSync(temporary)) unlinkSync(temporary)
    // Failed copies remain recoverable outside the site; never delete the source APK.
    throw error
  }
  for (const url of [item.url, item.checksumUrl]) unlinkSync(join(directory, basename(url)))
  return readPreparedDownload(directory)
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const { values } = parseArgs({ options: { sha256: { type: 'string' } } })
  if (!values.sha256) throw new Error('Usage: npm run archive:download -- --sha256 <one historical APK digest>')
  const destination = join(repository, 'build/docs-site-download-archive', values.sha256)
  archiveDownload(join(repository, 'docs-site/src/public/downloads'), values.sha256, destination)
  console.log(`Archived selected historical APK without deleting its backup: ${destination}`)
}
