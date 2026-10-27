<script setup>
import { computed, ref } from 'vue'
import { useRouter, withBase } from 'vitepress'
import catalog from '../data/catalog.json'
import { learningPaths, recipes, commonModules, popularQueries, apiSearchLink, apiModuleLink } from './home-data.mjs'

const query = ref('')
const selectedRecipe = ref(0)
const recipe = computed(() => recipes[selectedRecipe.value])
const groups = commonModules.map(id => catalog.groups.find(group => group.id === id)).filter(Boolean)
const router = useRouter()
function search() { router.go(withBase(apiSearchLink(query.value))) }
</script>

<template>
  <div class="docs-home">
    <section class="intro" aria-labelledby="home-title">
      <div class="intro-copy">
        <p class="intro-kicker"><span aria-hidden="true" /> 智构文档 <span class="intro-divider">/</span> BUILD WITH CLARITY</p>
        <h1 id="home-title">自动化，<br><span>从这里开始。</span></h1>
        <p class="intro-description">从第一块积木，到完整的脚本流程。<br>你需要的使用指南与 API 参考，都在这里。</p>
        <form class="home-search" :action="withBase('/api/')" method="get" role="search" @submit.prevent="search">
          <svg width="19" height="19" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" aria-hidden="true"><circle cx="10.5" cy="10.5" r="6.5" /><path d="m16 16 4 4" /></svg>
          <input v-model="query" name="q" type="search" maxlength="200" aria-label="搜索 API" placeholder="搜索 API 名称、功能或参数" autocomplete="off">
          <button type="submit">搜索 <span aria-hidden="true">↵</span></button>
        </form>
        <div class="popular-queries"><span>试试</span><a v-for="term in popularQueries" :key="term" :href="withBase(apiSearchLink(term))">{{ term }}</a></div>
        <div class="intro-actions">
          <a class="intro-primary" :href="withBase('/guide/quick-start')">快速入门 <span aria-hidden="true">→</span></a>
          <a class="intro-secondary" :href="withBase('/download')">下载智构 <span aria-hidden="true">↓</span></a>
        </div>
        <p class="intro-meta"><span>可视化编排</span><span>Root 执行</span><span>本地优先</span></p>
      </div>

      <div class="recipe-area">
        <div class="recipe-halo" aria-hidden="true" />
        <section class="recipe-card" aria-label="自动化流程示例">
          <div class="recipe-topline"><div class="recipe-brand"><img :src="withBase('/zhigou-logo.png')" alt="" width="32" height="32"><span>思路，连接成流程。</span></div><span class="recipe-readonly">只读示意</span></div>
          <div class="recipe-tabs" role="group" aria-label="选择流程示例"><button v-for="(item, index) in recipes" :key="item.id" type="button" :aria-pressed="selectedRecipe === index" @click="selectedRecipe = index">{{ item.label }}</button></div>
          <div class="recipe-body" aria-live="polite" aria-atomic="true">
            <div class="recipe-heading"><h2>{{ recipe.title }}</h2><p>{{ recipe.description }}</p></div>
            <ol class="recipe-steps">
              <li v-for="(step, index) in recipe.steps" :key="recipe.id + index" :class="'step-' + step.tone">
                <span class="step-number" aria-hidden="true">{{ String(index + 1).padStart(2, '0') }}</span>
                <div class="step-copy"><strong>{{ step.title }}</strong><span>{{ step.detail }}</span></div>
                <span class="step-tag">{{ step.tag }}</span>
              </li>
            </ol>
          </div>
          <a class="recipe-link" :href="withBase(recipe.link)"><span>{{ recipe.action }}</span><span aria-hidden="true">→</span></a>
        </section>
        <div class="recipe-caption"><span aria-hidden="true">◇</span> 先理解每一步，再组合你的自动化。</div>
      </div>
    </section>

    <section class="learning-section" aria-labelledby="learning-title">
      <div class="section-heading"><div><p class="section-kicker">找到你的起点</p><h2 id="learning-title">从了解，到上手。</h2></div><a :href="withBase('/guide/')">浏览使用指南 <span aria-hidden="true">↗</span></a></div>
      <div class="learning-grid">
        <a v-for="path in learningPaths" :key="path.number" :href="withBase(path.link)" class="learning-card" :class="{ recommended: path.number === '01' }">
          <div class="learning-card-top"><span class="path-icon"><svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path :d="path.icon" /></svg></span><span class="path-label">{{ path.label }}</span><span class="path-number">{{ path.number }}</span></div>
          <h3>{{ path.title }}</h3><p>{{ path.description }}</p><span class="path-action">{{ path.action }} <span aria-hidden="true">→</span></span>
        </a>
      </div>
    </section>

    <section class="modules-section" aria-labelledby="modules-title">
      <div class="section-heading"><div><p class="section-kicker">不绕路，直接查</p><h2 id="modules-title">常用能力，一步直达。</h2></div><a :href="withBase('/api/')">全部 {{ catalog.count }} 个 API <span aria-hidden="true">↗</span></a></div>
      <div class="modules-grid"><a v-for="group in groups" :key="group.id" :href="withBase(apiModuleLink(group.id))" class="module-link"><div><code>{{ group.id }}<span>.*</span></code><strong>{{ group.title }}</strong></div><span class="module-count">{{ group.apis.length }} <span aria-hidden="true">↗</span></span></a></div>
    </section>

    <aside class="home-reminder"><div class="reminder-symbol" aria-hidden="true">i</div><div><strong>编写脚本前，先读懂执行约定。</strong><p>能力声明、坐标与资源释放，会影响每一次正确执行。</p></div><a :href="withBase('/guide/contracts')">查看约定 <span aria-hidden="true">→</span></a></aside>
  </div>
</template>

<style scoped>
.docs-home { max-width: 1184px; padding: 0 40px 32px; margin: 0 auto; line-height: 1.6; }
.intro { display: grid; grid-template-columns: 1.08fr 1fr; align-items: center; gap: 76px; padding: 38px 0 36px; }
.intro-copy { min-width: 0; }
.intro-kicker { display: flex; align-items: center; gap: 8px; font-size: 10px; font-weight: 550; letter-spacing: 1px; color: var(--zg-muted); }
.intro-kicker > span:first-child { width: 6px; height: 6px; border-radius: 2px; background: var(--vt-c-brand); }
.intro-divider { opacity: .35; margin: 0 2px; }
.intro h1 { font-size: clamp(36px, 4vw, 56px); line-height: 1.2; font-weight: 760; letter-spacing: -1.9px; margin: 22px 0 18px; }
.intro h1 > span { background: var(--zg-gradient); background-clip: text; -webkit-background-clip: text; color: transparent; }
.intro-description { color: var(--zg-muted); font-size: 14px; line-height: 1.9; }
.home-search { display: flex; align-items: center; gap: 10px; border: 1px solid var(--zg-border); border-radius: 10px; padding: 7px 7px 7px 14px; margin-top: 25px; background: var(--vt-c-bg); box-shadow: 0 3px 12px #21193604; }
.home-search:focus-within { border-color: var(--vt-c-brand); box-shadow: 0 0 0 3px #936bda12; }
.home-search > svg { color: var(--zg-muted); flex-shrink: 0; }
.home-search input { border: 0; padding: 5px 0; min-width: 0; width: 100%; background: transparent; color: inherit; font: inherit; font-size: 12px; }
.home-search input:focus-visible { outline: 0; }
.home-search button { display: flex; align-items: center; gap: 10px; flex-shrink: 0; padding: 7px 11px; border-radius: 6px; background: var(--zg-tint); color: var(--vt-c-brand); font-size: 11px; cursor: pointer; }
.home-search button > span { opacity: .6; font-size: 13px; }
.popular-queries { display: flex; flex-wrap: wrap; gap: 6px 13px; align-items: center; margin-top: 11px; font-size: 10px; color: var(--zg-muted); }
.popular-queries > span { opacity: .7; }
.popular-queries a { text-decoration: underline; text-underline-offset: 3px; text-decoration-color: var(--zg-border); }
.popular-queries a:hover { color: var(--vt-c-brand); }
.intro-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 22px; margin-top: 25px; font-size: 12px; font-weight: 600; }
.intro-primary { display: flex; align-items: center; gap: 24px; padding: 10px 17px; border-radius: 7px; color: #fff; background: #7650cc; }
.intro-primary:hover { background: #6642bb; }
.intro-secondary { display: flex; gap: 8px; align-items: center; }
.intro-secondary:hover { color: var(--vt-c-brand); }
.intro-meta { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-top: 18px; font-size: 10px; color: var(--zg-muted); }
.intro-meta span + span::before { content: '/'; margin-right: 10px; opacity: .3; }
.recipe-area { position: relative; min-width: 0; }
.recipe-halo { position: absolute; inset: -35px -40px -15px; background: radial-gradient(ellipse at 70% 30%, #cf9bec20, transparent 66%), radial-gradient(ellipse at 15% 80%, #aa9fe71b, transparent 60%); pointer-events: none; }
.recipe-card { position: relative; border: 1px solid var(--zg-border); border-radius: 16px; background: var(--vt-c-bg); box-shadow: 0 12px 45px #30204408, 0 2px 5px #30204403; overflow: hidden; }
.recipe-topline { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 13px 18px; border-bottom: 1px solid var(--zg-border); }
.recipe-brand { display: flex; align-items: center; gap: 7px; font-size: 11px; font-weight: 550; }
.recipe-brand img { object-fit: contain; }
.recipe-readonly { font-size: 9px; color: var(--zg-muted); white-space: nowrap; }
.recipe-tabs { display: flex; gap: 4px; margin: 15px 18px 0; padding: 4px; border-radius: 7px; background: var(--zg-tint); }
.recipe-tabs button { flex: 1; border: 1px solid transparent; padding: 5px 4px; border-radius: 5px; color: var(--zg-muted); font-size: 10px; cursor: pointer; }
.recipe-tabs button[aria-pressed=true] { background: var(--vt-c-bg); border-color: var(--zg-border); color: var(--vt-c-brand); box-shadow: 0 1px 3px #27153706; font-weight: 600; }
.recipe-body { padding: 16px 22px 18px; }
.recipe-heading h2 { font-size: 15px; font-weight: 650; letter-spacing: -.2px; }
.recipe-heading p { font-size: 10px; color: var(--zg-muted); line-height: 1.7; margin-top: 5px; }
.recipe-steps { list-style: none; padding: 0; margin: 17px 0 0; }
.recipe-steps li { position: relative; display: flex; align-items: center; gap: 11px; min-height: 49px; }
.recipe-steps li:not(:last-child)::after { content: ''; position: absolute; left: 13px; top: 39px; bottom: -10px; width: 1px; background: var(--zg-border); }
.step-number { flex-shrink: 0; display: grid; place-items: center; width: 28px; height: 28px; border-radius: 8px; background: var(--zg-tint); color: var(--vt-c-brand); font: 10px ui-monospace, monospace; border: 1px solid var(--zg-border); }
.step-copy { display: grid; gap: 4px; min-width: 0; }
.step-copy strong { font-size: 12px; font-weight: 550; }
.step-copy > span { font-size: 10px; color: var(--zg-muted); line-height: 1.5; }
.step-tag { margin-left: auto; padding: 3px 5px; border-radius: 4px; color: var(--zg-muted); font: 9px ui-monospace, monospace; background: var(--zg-tint); white-space: nowrap; }
.step-logic .step-number { color: #b07748; }
.step-input .step-number { color: #5c8d80; }
.recipe-link { display: flex; align-items: center; justify-content: space-between; border-top: 1px solid var(--zg-border); padding: 12px 22px; color: var(--vt-c-brand); font-size: 10px; font-weight: 550; }
.recipe-link:hover { background: var(--zg-tint); }
.recipe-caption { position: relative; display: flex; align-items: center; justify-content: center; gap: 6px; margin-top: 13px; font-size: 9px; color: var(--zg-muted); }
.recipe-caption > span { color: var(--vt-c-brand); }
.section-heading { display: flex; justify-content: space-between; align-items: end; gap: 16px; margin-bottom: 19px; }
.section-kicker { color: var(--zg-muted); font-size: 10px; margin-bottom: 5px; }
.section-heading h2 { font-size: 22px; line-height: 1.4; font-weight: 650; letter-spacing: -.6px; }
.section-heading > a { color: var(--zg-muted); font-size: 10px; flex-shrink: 0; }
.section-heading > a:hover { color: var(--vt-c-brand); }
.learning-section { padding: 26px 0 30px; border-top: 1px solid var(--zg-border); }
.learning-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 15px; }
.learning-card { display: block; padding: 20px 22px 16px; border: 1px solid var(--zg-border); border-radius: 11px; transition: transform .2s, border-color .2s; }
.learning-card.recommended { background: linear-gradient(125deg, var(--zg-tint), var(--vt-c-bg)); }
.learning-card:hover { border-color: var(--vt-c-brand); transform: translateY(-2px); }
.learning-card-top { display: flex; align-items: center; gap: 9px; }
.path-icon { display: grid; place-items: center; width: 29px; height: 29px; border-radius: 7px; color: var(--vt-c-brand); background: var(--zg-tint); }
.path-label { color: var(--zg-muted); font-size: 9px; }
.path-number { margin-left: auto; font: 10px ui-monospace, monospace; color: var(--zg-muted); opacity: .5; }
.learning-card h3 { font-weight: 650; font-size: 16px; margin-top: 15px; }
.learning-card > p { color: var(--zg-muted); font-size: 11px; line-height: 1.9; margin-top: 7px; }
.path-action { display: flex; justify-content: space-between; margin-top: 16px; font-size: 10px; font-weight: 550; color: var(--vt-c-brand); }
.modules-section { padding: 13px 0 30px; }
.modules-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); border: 1px solid var(--zg-border); border-radius: 10px; overflow: hidden; }
.module-link { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 13px 17px; }
.module-link:nth-child(-n+3) { border-bottom: 1px solid var(--zg-border); }
.module-link:not(:nth-child(3n)) { border-right: 1px solid var(--zg-border); }
.module-link:hover { background: var(--zg-tint); }
.module-link > div { display: flex; flex-wrap: wrap; gap: 5px 11px; align-items: center; }
.module-link code { font-size: 11px; color: var(--vt-c-brand); }
.module-link code span { opacity: .4; }
.module-link strong { font-size: 10px; font-weight: 400; color: var(--zg-muted); }
.module-count { font: 10px ui-monospace, monospace; color: var(--zg-muted); white-space: nowrap; }
.module-count > span { margin-left: 9px; }
.home-reminder { display: flex; align-items: center; gap: 14px; padding: 18px 0 4px; border-top: 1px solid var(--zg-border); }
.reminder-symbol { display: grid; place-items: center; width: 23px; height: 23px; flex-shrink: 0; font: italic 14px Georgia, serif; border: 1px solid var(--zg-border); border-radius: 50%; color: var(--zg-muted); }
.home-reminder strong { font-size: 11px; font-weight: 550; }
.home-reminder p { color: var(--zg-muted); font-size: 10px; margin-top: 4px; line-height: 1.7; }
.home-reminder a { margin-left: auto; flex-shrink: 0; font-size: 10px; color: var(--vt-c-brand); }
@media (max-width: 1050px) {
  .intro { gap: 36px; }
  .step-tag { display: none; }
}
@media (max-width: 767px) {
  .docs-home { padding: 0 24px 25px; }
  .intro { grid-template-columns: minmax(0, 1fr); padding: 35px 0 32px; gap: 30px; }
  .intro h1 { font-size: 44px; margin-top: 19px; letter-spacing: -1.5px; }
  .intro-description { font-size: 13px; }
  .intro-kicker { font-size: 9px; }
  .recipe-area { max-width: 500px; width: 100%; margin: 0 auto; }
  .recipe-halo { inset: -15px; }
  .recipe-body { padding: 15px 20px; }
  .step-tag { display: block; font-size: 8px; }
  .learning-grid { grid-template-columns: minmax(0, 1fr); gap: 10px; }
  .learning-card { padding: 16px 19px; }
  .learning-card h3 { margin-top: 10px; }
  .learning-card > p { margin-top: 5px; }
  .path-action { margin-top: 11px; }
  .section-heading { gap: 10px; }
  .section-heading h2 { font-size: 19px; }
  .section-heading > a { font-size: 9px; }
  .modules-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .module-link { padding: 13px; border: 0; }
  .module-link:not(:nth-child(3n)) { border-right: 0; }
  .module-link:nth-child(-n+4) { border-bottom: 1px solid var(--zg-border); }
  .module-link:nth-child(odd) { border-right: 1px solid var(--zg-border); }
  .module-link > div { flex-direction: column; align-items: start; }
  .module-count > span { margin-left: 3px; }
  .home-reminder { flex-wrap: wrap; gap: 10px; }
  .home-reminder > div:nth-child(2) { flex: 1; min-width: 170px; }
}
@media (max-width: 359px) {
  .docs-home { padding-inline: 17px; }
  .intro h1 { font-size: 38px; }
  .home-search { gap: 7px; padding-left: 10px; }
  .home-search input { font-size: 11px; }
  .home-search button { padding-inline: 8px; }
  .home-search button > span, .step-tag { display: none; }
  .intro-kicker { letter-spacing: .3px; }
}
</style>
