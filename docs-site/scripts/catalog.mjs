import { readFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { isDeepStrictEqual } from 'node:util'

import { modules } from './modules.mjs'
export { modules } from './modules.mjs'

const execution = { async_task: '异步任务', rust_sync: 'Rust 同步计算', host_sync: '宿主调用' }
const cancellation = { cooperative: '协作式取消', none: '无独立取消过程', discard_result: '取消后丢弃结果' }
const effects = {
  network_io: '网络通信',
  none: '无', device_input: '设备输入或设备状态', input: '输入',
  file_write: '文件写入', file_read: '文件读取', log_write: '日志写入',
  memory_reserve: '申请内存资源', memory_release: '释放内存资源',
  ui_prompt: '界面或提示', screen_read: '读取屏幕',
  resource_create: '创建资源', private_file_write: '私有文件写入', task: '任务状态'
}
const ownership = {
  none: '无独立资源所有权', task_pointer_lease: '任务触点租约',
  task_input_transaction: '任务输入事务', input_transaction: '输入事务',
  borrowed_frame: '借用输入帧', borrowed_frame_and_dictionary: '借用帧与字库',
  task_dictionary_lease: '任务字库租约', owned_dictionary: '持有字库资源',
  task_frame_lease: '任务帧租约', task_frame_leases: '任务帧系列租约',
  capture_slot: '采集槽位', task_owned_frame: '任务独立帧',
  borrowed_frames: '借用帧系列', task: '任务所有'
}

export function apiSlug(name) {
  if (!/^[A-Z][A-Za-z0-9]*\.[a-zA-Z][a-zA-Z0-9]*$/.test(name)) throw new Error(`Invalid API name: ${name}`)
  return name.replace(/([a-z0-9])([A-Z])/g, '$1-$2').replace('.', '/').toLowerCase()
}

export function normalizeSpec(api) {
  return { ...api, timeoutMs: api.timeoutMs ?? null }
}

export function createCatalog(specs) {
  const names = new Set(), opcodes = new Set(), routes = new Set()
  const knownModules = new Set(modules.map(module => module.id))
  const apis = specs.map(api => {
    const slug = apiSlug(api.name), module = api.name.split('.')[0]
    if (!knownModules.has(module)) throw new Error(`Unclassified module: ${module}`)
    if (names.has(api.name) || opcodes.has(api.opcode) || routes.has(slug)) throw new Error(`Duplicate API name/opcode/route: ${api.name}`)
    if (!Number.isSafeInteger(api.opcode) || api.opcode < 0 || typeof api.summary !== 'string' || !api.summary.trim()) throw new Error(`Invalid API contract: ${api.name}`)
    if (!execution[api.execution] || !cancellation[api.cancelMode] || !ownership[api.resourceOwnership] || !effects[api.sideEffect]) throw new Error(`Unclassified execution policy: ${api.name}`)
    if (!Array.isArray(api.params) || !api.returns || !Array.isArray(api.legacyAliases)) throw new Error(`Incomplete API contract: ${api.name}`)
    const params = new Set()
    for (const param of api.params) {
      if (!/^[a-zA-Z][a-zA-Z0-9]*$/.test(param.name) || params.has(param.name) || typeof param.type !== 'string' || typeof param.summary !== 'string' || typeof param.required !== 'boolean') throw new Error(`Invalid parameter: ${api.name}`)
      params.add(param.name)
    }
    if (typeof api.returns.type !== 'string' || typeof api.returns.nullable !== 'boolean' || typeof api.returns.summary !== 'string') throw new Error(`Invalid return contract: ${api.name}`)
    names.add(api.name); opcodes.add(api.opcode); routes.add(slug)
    return { ...api, module, slug, link: `/api/generated/${slug}` }
  }).sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0)
  return {
    count: apis.length,
    groups: modules.map(module => ({ ...module, apis: apis.filter(api => api.module === module.id) })).filter(group => group.apis.length),
    apis
  }
}

export function loadCatalog(repository) {
  const directory = join(repository, 'schema/api-schema/functions')
  const specs = readdirSync(directory).filter(file => file.endsWith('.json')).sort()
    .map(file => JSON.parse(readFileSync(join(directory, file), 'utf8')))
  const snapshot = JSON.parse(readFileSync(join(repository, 'schema/api-schema/snapshot/contract.json'), 'utf8'))
  const byOpcode = values => values.map(normalizeSpec).sort((a, b) => a.opcode - b.opcode)
  if (!isDeepStrictEqual(byOpcode(specs), byOpcode(snapshot.functions))) throw new Error('API snapshot is stale; run the existing Rust api-codegen generate/check workflow first')
  return createCatalog(specs)
}

const md = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
  .replaceAll('{', '&#123;').replaceAll('}', '&#125;').replaceAll('|', '&#124;')
  .replaceAll('*', '\\*').replaceAll('_', '\\_').replace(/\r?\n/g, '<br>')

export const generatedMarker = '<!-- zhigou-generated-api: do not edit -->'

export function renderApi(api) {
  const { module, slug, link, ...spec } = api
  const label = modules.find(item => item.id === module)?.title ?? module
  const lines = [
    '---', `title: ${JSON.stringify(api.name)}`, `description: ${JSON.stringify(api.summary)}`, '---', '', generatedMarker, '',
    `# ${api.name}`, '', `<p class="api-eyebrow">${md(label)} · API ${md(api.since)}</p>`, '', md(api.summary), '',
    '## 调用形式', '', '以下参数名用于说明调用顺序，并非可以直接执行的业务脚本。可选项见参数表。', '', '```lua',
    `${api.returns.type === 'void' ? '' : 'local result = '}${api.name}(${api.params.map(param => param.name).join(', ')})`, '```', '',
    '## 参数', ''
  ]
  if (api.params.length) {
    lines.push('| 名称 | 类型 | 必填 | 说明 |', '| --- | --- | --- | --- |')
    for (const param of api.params) lines.push(`| \`${param.name}\` | \`${md(param.type)}\` | ${param.required ? '是' : '否'} | ${md(param.summary)} |`)
  } else lines.push('此 API 无参数。')
  lines.push('', '## 返回值', '', `**类型：** \`${md(api.returns.type)}\``, '', md(api.returns.summary), '')
  if (api.returns.nullable) lines.push('允许返回 `nil`。请先判断是否存在结果，再读取结果字段。', '')
  else if (api.returns.type === 'void') lines.push('不返回业务值。调用结束的含义以接口说明为准。', '')
  lines.push('## 执行约定', '', '| 项目 | 约定 |', '| --- | --- |',
    `| 能力声明 | \`${md(api.capability)}\` |`,
    `| 执行方式 | ${execution[api.execution]}（\`${api.execution}\`） |`,
    `| 超时 | ${api.timeoutMs == null ? '契约未声明独立超时' : `${api.timeoutMs} ms`} |`,
    `| 取消 | ${cancellation[api.cancelMode]}（\`${api.cancelMode}\`） |`,
    `| 副作用 | ${effects[api.sideEffect]}（\`${api.sideEffect}\`） |`,
    `| 资源所有权 | ${ownership[api.resourceOwnership]}（\`${api.resourceOwnership}\`） |`,
    `| Opcode | \`${api.opcode}\` |`, '')
  if (api.resourceOwnership.startsWith('borrowed')) lines.push('输入资源由原持有者管理；本次借用不转移所有权，不要重复释放。', '')
  if (api.cancelMode !== 'none') lines.push('取消和超时不代表所有宿主或 SDK 调用都能被硬实时抢占；停止后不得继续使用旧任务结果。', '')
  lines.push('## 兼容名称', '', api.legacyAliases.length ? api.legacyAliases.map(alias => `- \`${md(alias)}\``).join('\n') : '无。请使用上面的正式 API 名称。', '')
  if (api.module === 'Legacy') lines.push('::: warning 历史工程兼容\n此接口保留既有工程语义。新项目优先使用可视化原生积木，不把兼容参数当成新算法。\n:::', '')
  lines.push('## 相关说明', '', '[能力与资源约定](/guide/contracts) · [全部 API](/api/)', '',
    '<details>', '<summary>查看原始契约</summary>', '', '```json', JSON.stringify(spec, null, 2), '```', '', '</details>', '')
  return lines.join('\n')
}
