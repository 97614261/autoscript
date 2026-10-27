import { copyFileSync, existsSync, lstatSync, mkdirSync, readFileSync, unlinkSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve, sep } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { loadCatalog, renderApi, generatedMarker } from './catalog.mjs'
import { readPreparedDownload } from './prepare-download.mjs'

export const repository = resolve(dirname(fileURLToPath(import.meta.url)), '../..')

export function syncGenerated(outputDirectory, outputs) {
  const root = resolve(outputDirectory), manifest = join(root, '.owned-files.json')
  mkdirSync(root, { recursive: true })
  if (lstatSync(root).isSymbolicLink()) throw new Error('Generated output root must not be a link')
  if (lstatSync(manifest, { throwIfNoEntry: false })?.isSymbolicLink()) throw new Error('Generated manifest must not be a link')
  const safeFile = relative => {
    if (typeof relative !== 'string' || !/^[a-z0-9-]+\/[a-z0-9-]+\.md$/.test(relative)) throw new Error('Unsafe generated API path')
    const target = resolve(root, relative)
    if (!target.startsWith(root + sep)) throw new Error('Generated API escaped output root')
    for (const path of [dirname(target), target]) {
      if (lstatSync(path, { throwIfNoEntry: false })?.isSymbolicLink()) throw new Error('Generated API must not follow links')
    }
    return target
  }
  const previous = existsSync(manifest) ? JSON.parse(readFileSync(manifest, 'utf8')) : []
  if (!Array.isArray(previous)) throw new Error('Invalid generated API manifest')
  // Validate every owned path before writing or removing anything.
  for (const relative of [...previous, ...outputs.keys()]) safeFile(relative)
  for (const [relative, content] of outputs) {
    const target = safeFile(relative)
    if (existsSync(target) && !readFileSync(target, 'utf8').includes(generatedMarker)) throw new Error(`Refusing to overwrite unowned file: ${relative}`)
    mkdirSync(dirname(target), { recursive: true })
    writeFileSync(target, content, 'utf8')
  }
  for (const relative of previous) {
    const target = safeFile(relative)
    if (!outputs.has(relative) && existsSync(target)) {
      if (!readFileSync(target, 'utf8').includes(generatedMarker)) throw new Error('Refusing to remove unowned generated API file')
      unlinkSync(target)
    }
  }
  writeFileSync(manifest, JSON.stringify([...outputs.keys()].sort(), null, 2) + '\n', 'utf8')
}

export function generate(site = resolve(repository, 'docs-site')) {
  const catalog = loadCatalog(repository)
  const outputs = new Map(catalog.apis.map(api => [api.slug + '.md', renderApi(api)]))
  syncGenerated(join(site, 'src/api/generated'), outputs)
  const data = join(site, '.vitepress/data')
  mkdirSync(data, { recursive: true })
  writeFileSync(join(data, 'catalog.json'), JSON.stringify(catalog, null, 2) + '\n', 'utf8')
  writeFileSync(join(data, 'download.json'), JSON.stringify(readPreparedDownload(join(site, 'src/public/downloads')), null, 2) + '\n', 'utf8')
  const publicDirectory = join(site, 'src/public')
  mkdirSync(publicDirectory, { recursive: true })
  copyFileSync(join(repository, 'android/modules/brand-ui/src/main/res/drawable-nodpi/zhigou_brand_mark.png'), join(publicDirectory, 'zhigou-logo.png'))
  return catalog
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const catalog = generate()
  console.log(`Generated ${catalog.count} API pages in ${catalog.groups.length} groups; contract snapshot matches.`)
}
