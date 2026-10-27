import assert from 'node:assert/strict'
import { test } from 'node:test'
import { mkdtempSync, mkdirSync, readFileSync, writeFileSync, existsSync, rmSync, symlinkSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { createCatalog, loadCatalog, apiSlug, renderApi, generatedMarker } from './catalog.mjs'
import { filterApis } from './search.mjs'
import { repository, syncGenerated } from './generate.mjs'
import { learningPaths, recipes, commonModules, popularQueries, apiSearchLink, apiModuleLink } from '../.vitepress/theme/home-data.mjs'

const catalog = loadCatalog(repository)
const tap = catalog.apis.find(api => api.name === 'Input.tap')

test('homepage stays documentation-first and download navigation has a dedicated page', () => {
  const home = readFileSync(join(repository, 'docs-site/.vitepress/theme/HomePage.vue'), 'utf8')
  const config = readFileSync(join(repository, 'docs-site/.vitepress/config.mts'), 'utf8')
  assert.ok(home.includes('class="intro-secondary" :href="withBase(\'/download\')"'))
  assert.ok(!home.includes('download.json') && !home.includes('versionName') && !home.includes('sha256'))
  assert.ok(config.includes("text: '下载', link: '/download'"))
  assert.ok(existsSync(join(repository, 'docs-site/src/download.md')))
})

test('homepage learning paths and recipe guides resolve to real site pages', () => {
  for (const item of [...learningPaths, ...recipes]) {
    const path = item.link.endsWith('/') ? item.link + 'index.md' : item.link + '.md'
    assert.ok(existsSync(join(repository, 'docs-site/src', path)), item.link)
  }
  assert.equal(new Set(recipes.map(item => item.id)).size, recipes.length)
  for (const recipe of recipes) {
    assert.equal(recipe.steps.length, 4)
    for (const step of recipe.steps) {
      if (step.tag.includes('.')) assert.ok(catalog.apis.some(api => api.name === step.tag), step.tag)
    }
  }
})

test('homepage popular searches and module shortcuts have actual results', () => {
  for (const query of popularQueries) {
    const url = new URL(apiSearchLink(query), 'https://docs.example')
    assert.equal(url.pathname, '/api/')
    assert.ok(filterApis(catalog.apis, url.searchParams.get('q')).length, query)
  }
  for (const module of commonModules) {
    const url = new URL(apiModuleLink(module), 'https://docs.example')
    assert.ok(catalog.groups.some(group => group.id === url.searchParams.get('module')), module)
  }
})

test('homepage search safely encodes input, normalizes and bounds it', () => {
  assert.equal(apiSearchLink('  '), '/api/')
  assert.equal(apiSearchLink('Ｉｎｐｕｔ．ｔａｐ'), '/api/?q=Input.tap')
  const query = '找图 & <script> # / ?'
  const url = new URL(apiSearchLink(query), 'https://docs.example')
  assert.equal(url.origin, 'https://docs.example')
  assert.equal(url.searchParams.get('q'), query)
  assert.equal(url.hash, '')
  assert.equal(new URL(apiSearchLink('x'.repeat(300)), 'https://docs.example').searchParams.get('q').length, 200)
})

test('every contract appears exactly once in the catalog and sidebar groups', () => {
  const snapshot = JSON.parse(readFileSync(join(repository, 'schema/api-schema/snapshot/contract.json'), 'utf8'))
  assert.equal(catalog.count, snapshot.functions.length)
  assert.equal(new Set(catalog.apis.map(api => api.link)).size, catalog.count)
  assert.deepEqual(catalog.groups.flatMap(group => group.apis.map(api => api.name)).sort(), snapshot.functions.map(api => api.name).sort())
})

test('every detail contains ordered parameters, returns, policies and the exact raw contract', () => {
  for (const api of catalog.apis) {
    const page = renderApi(api)
    const raw = JSON.parse(page.split('```json\n')[1].split('\n```')[0])
    const { module, slug, link, ...expected } = api
    assert.deepEqual(raw, expected)
    for (const value of [api.name, api.capability, api.cancelMode, api.execution, api.resourceOwnership, `${api.opcode}`]) assert.ok(page.includes(value), `${api.name}: missing ${value}`)
    for (const param of api.params) assert.ok(page.includes(`| \`${param.name}\` |`))
    assert.ok(page.includes('## 返回值'))
    assert.ok(page.includes(generatedMarker))
  }
})

test('duplicate API names, opcodes and unclassified modules fail generation', () => {
  assert.throws(() => createCatalog([tap, { ...tap }]), /Duplicate/)
  assert.throws(() => createCatalog([tap, { ...tap, name: 'Input.other' }]), /Duplicate/)
  assert.throws(() => createCatalog([{ ...tap, name: 'Unknown.api' }]), /Unclassified/)
})

test('unsafe names and incomplete metadata are not silently documented', () => {
  for (const name of ['../../secret', 'Screen/<x>', 'Input.tap()']) assert.throws(() => apiSlug(name), /Invalid/)
  assert.throws(() => createCatalog([{ ...tap, execution: 'future_unknown' }]), /Unclassified/)
  assert.throws(() => createCatalog([{ ...tap, params: [...tap.params, tap.params[0]] }]), /Invalid parameter/)
  assert.equal(apiSlug('Screen.findImages'), 'screen/find-images')
  assert.equal(apiSlug('UI.getValue'), 'ui/get-value')
})

test('local search matches names, Chinese descriptions, aliases and parameter descriptions', () => {
  assert.ok(filterApis(catalog.apis, 'INPUT.TAP').some(api => api.name === 'Input.tap'))
  assert.ok(filterApis(catalog.apis, '截图').some(api => api.module === 'Screen'))
  assert.ok(filterApis(catalog.apis, 'Tap').some(api => api.name === 'Input.tap'))
  assert.ok(filterApis(catalog.apis, '设计坐标X').some(api => api.name === 'Input.tap'))
  assert.ok(filterApis(catalog.apis, 'screen.capture').length)
  assert.equal(filterApis(catalog.apis, '', 'Input').length, catalog.groups.find(group => group.id === 'Input').apis.length)
  assert.equal(filterApis(catalog.apis, 'definitely-no-api-xyz').length, 0)
})

test('empty search, Unicode normalization and whitespace use the same deterministic rules', () => {
  assert.equal(filterApis(catalog.apis, '  ').length, catalog.count)
  assert.ok(filterApis(catalog.apis, 'Ｉｎｐｕｔ．ｔａｐ').some(api => api.name === 'Input.tap'))
  assert.ok(filterApis(catalog.apis, 'Input  tap').some(api => api.name === 'Input.tap'))
  assert.equal(filterApis(catalog.apis, 'Input.tap', 'Ocr').length, 0)
})

test('contract text cannot inject Vue templates, HTML or additional table columns', () => {
  const malicious = { ...tap, summary: '<script>alert(1)</script> {{unsafe}} | column' }
  const rendered = renderApi(malicious)
  const beforeRaw = rendered.split('## 相关说明')[0]
  // Frontmatter is JSON quoted; rendered prose is escaped.
  assert.ok(beforeRaw.includes('&lt;script&gt;'))
  assert.ok(beforeRaw.includes('&#123;&#123;unsafe&#125;&#125;'))
  assert.ok(beforeRaw.includes('&#124; column'))
})

function isolated(action) {
  const directory = mkdtempSync(join(tmpdir(), 'zhigou-docs-test-'))
  try { action(directory) } finally { rmSync(directory, { recursive: true, force: true }) }
}

test('generation is deterministic and removes only explicitly owned obsolete pages', () => isolated(root => {
  const first = new Map([['input/tap.md', generatedMarker + '\nold']])
  syncGenerated(root, first)
  writeFileSync(join(root, 'handwritten.md'), 'keep me')
  const second = new Map([['screen/capture.md', generatedMarker + '\nnew']])
  syncGenerated(root, second)
  const manifest = readFileSync(join(root, '.owned-files.json'), 'utf8')
  syncGenerated(root, second)
  assert.equal(manifest, readFileSync(join(root, '.owned-files.json'), 'utf8'))
  assert.equal(readFileSync(join(root, 'handwritten.md'), 'utf8'), 'keep me')
  assert.equal(existsSync(join(root, 'input/tap.md')), false)
  assert.equal(readFileSync(join(root, 'screen/capture.md'), 'utf8'), generatedMarker + '\nnew')
}))

test('generation refuses overwriting handwritten pages', () => isolated(root => {
  mkdirSync(join(root, 'input'))
  writeFileSync(join(root, 'input/tap.md'), 'handwritten')
  assert.throws(() => syncGenerated(root, new Map([['input/tap.md', generatedMarker]])), /unowned/)
  assert.equal(readFileSync(join(root, 'input/tap.md'), 'utf8'), 'handwritten')
}))

test('malformed and escaping old manifests are rejected before mutations', () => isolated(root => {
  const outputs = new Map([['input/tap.md', generatedMarker]])
  for (const previous of [['../../outside.md'], { bad: true }, ['/outside.md']]) {
    writeFileSync(join(root, '.owned-files.json'), JSON.stringify(previous))
    assert.throws(() => syncGenerated(root, outputs), /Unsafe|Invalid/)
    assert.equal(existsSync(join(root, 'input/tap.md')), false)
  }
}))

test('generation refuses deleting a changed owned page that lost its marker', () => isolated(root => {
  syncGenerated(root, new Map([['input/tap.md', generatedMarker]]))
  writeFileSync(join(root, 'input/tap.md'), 'user edits')
  assert.throws(() => syncGenerated(root, new Map()), /unowned/)
  assert.equal(readFileSync(join(root, 'input/tap.md'), 'utf8'), 'user edits')
}))

test('generation does not follow linked output directories', () => isolated(root => {
  const output = join(root, 'output'), outside = join(root, 'outside')
  mkdirSync(output); mkdirSync(outside)
  symlinkSync(outside, join(output, 'input'), process.platform === 'win32' ? 'junction' : 'dir')
  assert.throws(() => syncGenerated(output, new Map([['input/tap.md', generatedMarker]])), /links/)
  assert.equal(existsSync(join(outside, 'tap.md')), false)
}))
