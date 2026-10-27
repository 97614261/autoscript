<script setup>
import { computed, onMounted, ref } from 'vue'
import { withBase } from 'vitepress'
import catalog from '../data/catalog.json'
import { filterApis } from '../../scripts/search.mjs'

const query = ref(''), selected = ref(''), search = ref(null)
const results = computed(() => filterApis(catalog.apis, query.value, selected.value))
onMounted(() => {
  const params = new URLSearchParams(window.location.search)
  query.value = (params.get('q') || '').slice(0, 200)
  const module = params.get('module')
  if (catalog.groups.some(group => group.id === module)) selected.value = module
})
function clear() { query.value = ''; selected.value = ''; search.value?.focus() }
</script>

<template>
  <section class="api-catalog" aria-label="API 搜索与分类">
    <label class="catalog-search">
      <span aria-hidden="true">⌕</span>
      <input ref="search" v-model="query" type="search" maxlength="200" aria-label="搜索 API" placeholder="搜索接口、中文说明、参数或能力名称" autocomplete="off">
      <span class="catalog-total">{{ catalog.count }} API</span>
    </label>
    <div class="catalog-filters" role="group" aria-label="API 分类">
      <button type="button" :aria-pressed="selected === ''" @click="selected = ''">全部</button>
      <button v-for="group in catalog.groups" :key="group.id" type="button" :aria-pressed="selected === group.id" @click="selected = group.id">{{ group.title }} <span>{{ group.apis.length }}</span></button>
    </div>
    <div class="catalog-results-heading"><span role="status" aria-live="polite">{{ results.length }} 个接口<span v-if="query"> · “{{ query }}”</span></span><button v-if="query || selected" type="button" @click="clear">清除筛选</button></div>
    <ul v-if="results.length" class="catalog-list">
      <li v-for="api in results" :key="api.name">
        <a :href="withBase(api.link)"><div class="catalog-name"><code>{{ api.name }}</code><span>{{ api.module }}</span></div><p>{{ api.summary }}</p><span class="catalog-arrow" aria-hidden="true">→</span></a>
      </li>
    </ul>
    <div v-else class="catalog-empty"><h2>没有找到对应接口</h2><p>试试“截图”“点击”或能力名，也可以清除当前分类。</p><button type="button" @click="clear">查看全部 API</button></div>
  </section>
</template>
