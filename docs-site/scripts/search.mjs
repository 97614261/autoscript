import { modules } from './modules.mjs'

/** Browser-safe, deterministic search. No Node, network, analytics or project access. */
export function filterApis(apis, query = '', module = '') {
  const terms = query.normalize('NFKC').toLowerCase().trim().slice(0, 200).split(/\s+/).filter(Boolean)
  return apis.filter(api => {
    if (module && api.module !== module) return false
    const category = modules.find(item => item.id === api.module)
    const text = [api.name, api.summary, api.capability, category?.title, category?.description, ...api.legacyAliases,
      ...api.params.flatMap(param => [param.name, param.summary])].join(' ').normalize('NFKC').toLowerCase()
    return terms.every(term => text.includes(term))
  })
}
