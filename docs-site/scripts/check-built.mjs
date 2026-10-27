import assert from 'node:assert/strict'
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs'
import { resolve, join, sep, dirname } from 'node:path'
import { loadCatalog } from './catalog.mjs'
import { repository } from './generate.mjs'

const root = resolve(repository, 'docs-site/.vitepress/dist')
const catalog = loadCatalog(repository)
function walk(directory) {
  return readdirSync(directory).flatMap(name => {
    const path = join(directory, name)
    return statSync(path).isDirectory() ? walk(path) : [path]
  })
}
for (const api of catalog.apis) assert.ok(existsSync(join(root, api.link + '.html')), `Missing API page ${api.name}`)
const pages = walk(root).filter(path => path.endsWith('.html'))
let links = 0
for (const page of pages) {
  const html = readFileSync(page, 'utf8')
  for (const match of html.matchAll(/(?:href|src)="([^"<>]+)"/g)) {
    const url = match[1].replaceAll('&amp;', '&')
    if (/^(?:https?:|mailto:|data:|tel:)/.test(url)) continue
    const [pathAndQuery, fragment] = url.split('#')
    const pathname = decodeURIComponent(pathAndQuery.split('?')[0])
    let target = !pathname ? page : pathname.startsWith('/') ? resolve(root, '.' + pathname) : resolve(dirname(page), pathname)
    assert.ok(target === root || target.startsWith(root + sep), `Link escapes site root: ${url}`)
    if (existsSync(target) && statSync(target).isDirectory()) target = join(target, 'index.html')
    else if (!existsSync(target) && existsSync(target + '.html')) target += '.html'
    assert.ok(existsSync(target), `Broken built link: ${page} -> ${url}`)
    if (fragment && target.endsWith('.html')) {
      const body = target === page ? html : readFileSync(target, 'utf8')
      const id = decodeURIComponent(fragment)
      assert.ok(body.includes(`id="${id}"`), `Missing fragment ${url}`)
    }
    links++
  }
}
console.log(`Built documentation OK: ${catalog.count} API pages, ${pages.length} HTML pages, ${links} local links/assets.`)
