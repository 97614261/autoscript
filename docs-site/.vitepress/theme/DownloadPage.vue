<script setup>
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'
import { withBase } from 'vitepress'
import snapshot from '../data/download.json'
import { loadDownloadManifest } from '../../shared/download-client.mjs'

const copyState = ref({})
const release = ref(snapshot)
const state = ref('loading')
const history = computed(() => release.value.history ?? [])
const builds = computed(() => release.value.available ? [release.value, ...(release.value.alternatives ?? [])] : [])
let request
async function refresh() {
  request?.abort()
  const controller = new AbortController()
  request = controller
  state.value = 'loading'
  const timeout = setTimeout(() => controller.abort(), 10000)
  try {
    const latest = await loadDownloadManifest(withBase('/downloads/release.json'), { signal: controller.signal })
    if (request === controller) { release.value = latest; state.value = 'ready' }
  } catch {
    if (request === controller) { release.value = { available: false }; state.value = 'error' }
  } finally { clearTimeout(timeout) }
}
onMounted(refresh)
onBeforeUnmount(() => { request?.abort(); request = undefined })
const candidate = item => item.variant === 'release'
const device = item => item.abis.length > 1 ? '双架构兼容包' : item.abis[0] === 'arm64-v8a' ? 'ARM64 · 安卓手机' : 'x86_64 · MuMu 模拟器'
const date = value => value.slice(0, 16).replace('T', ' ') + ' UTC'
const size = bytes => (bytes / 1024 / 1024).toFixed(1) + ' MiB'
async function copyHash(item) {
  try {
    await navigator.clipboard.writeText(item.sha256)
    copyState.value[item.sha256] = '已复制'
  } catch {
    copyState.value[item.sha256] = '请选中下方校验值复制'
  }
}
</script>

<template>
  <main class="download-page" data-live-downloads="v1">
    <header class="download-heading">
      <p class="download-kicker">ANDROID · LOCAL FIRST</p>
      <h1>下载<span>智构。</span></h1>
      <p>选对架构，轻装开始。<a class="download-guide" :href="withBase('/releases/')">更新记录 ↗</a></p>
    </header>

    <p v-if="state === 'loading'" class="download-status" role="status">正在核对最新版本；下方若有内容，仅为构建时快照，核对后开放下载。</p>
    <p v-else-if="state === 'error'" class="download-status" role="alert">暂时无法获取最新下载信息，未使用旧版本冒充最新版本。<button type="button" @click="refresh">重试</button></p>

    <p v-if="builds.some(candidate)" class="candidate-warning">优化候选版：开发证书签名，尚未完成设备验收，不是正式发行版。视觉能力与 V4 / V5 模型均保留；升级前请备份重要项目。</p>
    <aside v-if="release.available" class="download-update" aria-labelledby="app-update-title">
      <div><strong id="app-update-title">App 内更新</strong><span>当前 {{ release.versionName }} · 版本代码 {{ release.versionCode }}</span></div>
      <p>智构 0.1.2 起，打开 App 自动检查，确认后在 App 内下载，由系统确认安装。更早版本先手动安装一次；相同版本代码不重复提醒，历史包不参与升级。</p>
      <a class="download-guide" :href="withBase('/guide/app-update')">更新方式与问题排查 →</a>
    </aside>
    <section v-for="(item, index) in builds" :key="item.url" class="download-card" :aria-labelledby="`download-title-${index}`">
      <div class="download-brand"><img :src="withBase('/zhigou-logo.png')" alt="" width="76" height="76"><div><span class="download-badge">{{ candidate(item) ? 'Release 优化候选' : 'Debug 开发测试版' }}</span><h2 :id="`download-title-${index}`">{{ device(item) }} <small>{{ item.versionName }}</small></h2><p>完整视觉配置 · 本地编排 · Root 执行</p></div></div>
        <p class="release-description">{{ item.description ?? '智构开发测试构建，升级前请备份重要项目。' }}</p>
        <dl class="download-facts">
          <div><dt>适用系统</dt><dd>Android 7.0+ <span>API {{ item.minSdk }}+</span></dd></div>
          <div><dt>支持架构</dt><dd>{{ item.abis.join(' / ') }}</dd></div>
          <div><dt>App 更新版本</dt><dd>{{ item.versionName }} <span>versionCode {{ item.versionCode }}</span></dd></div>
          <div><dt>安装包大小</dt><dd>{{ size(item.bytes) }}</dd></div>
          <div><dt>构建时间</dt><dd>{{ date(item.builtAt) }} <code>· {{ item.sha256.slice(0, 8) }}</code></dd></div>
        </dl>
        <div class="download-actions"><a class="download-button" :href="state === 'ready' ? withBase(item.url) : undefined" :data-snapshot-url="item.url" :aria-disabled="state !== 'ready'" download :aria-label="`下载智构 ${device(item)} ${candidate(item) ? '优化候选' : '开发版'}`">下载 {{ item.abis.length === 1 ? item.abis[0] === 'arm64-v8a' ? 'ARM64' : 'x86_64' : '双架构' }} APK <span aria-hidden="true">↓</span></a><a class="download-guide" :href="withBase('/guide/install-upgrade')">安装与升级 <span aria-hidden="true">→</span></a></div>
        <p class="download-note">{{ candidate(item) ? '单架构优化包，不删减识图、OCR 或其他脚本能力。R8 与原生优化已通过主机测试，实际设备行为仍待验证。' : '开发签名测试包，不是正式商店发行版。' }}</p>
    <details class="download-integrity">
      <summary>文件校验与安装信息</summary>
      <p>包名 <code>{{ item.applicationId }}</code> · 版本代码 {{ item.versionCode }}</p>
      <div class="checksum-heading"><strong>SHA-256</strong><button type="button" @click="copyHash(item)">{{ copyState[item.sha256] ?? '复制校验值' }}</button></div>
      <code class="download-hash">{{ item.sha256 }}</code>
      <a :href="state === 'ready' ? withBase(item.checksumUrl) : undefined" :aria-disabled="state !== 'ready'" download>下载校验文件 ↗</a>
      <p v-if="item.certificateSha256">开发签名证书 SHA-256：</p><code v-if="item.certificateSha256" class="download-hash">{{ item.certificateSha256 }}</code>
      <p>Windows 可运行 <code>Get-FileHash -Algorithm SHA256 文件路径</code> 核对。不同签名的旧安装不能直接覆盖，请先导出备份，不要为安装而直接清除数据。</p>
    </details>
    </section>
    <p v-if="state === 'ready' && !release.available" class="download-unavailable" role="status">安装包暂未发布。你可以先阅读使用指南；有经过校验的安装包后，这里会开放下载。</p>

    <section class="release-history" aria-labelledby="history-title">
      <div class="history-heading"><h2 id="history-title">历史版本</h2><span>{{ history.length }} 个可下载构建</span></div>
      <p class="history-note">历史包不参与 App 自动更新。相同版本号可能包含不同开发构建，请用日期和构建摘要区分。旧版仅用于回溯，降级前先备份，不保证新数据向下兼容。</p>
      <ul v-if="history.length" class="history-list">
        <li v-for="item in history" :key="item.url">
          <div class="history-version"><h3>智构 {{ item.versionName }} <span>{{ candidate(item) ? '优化候选 · 未设备验收' : 'Debug 开发版' }}</span></h3><a :href="state === 'ready' ? withBase(item.url) : undefined" :data-snapshot-url="item.url" :aria-disabled="state !== 'ready'" download :aria-label="`下载智构 ${item.versionName} 构建 ${item.sha256.slice(0, 8)}`">下载 APK <span aria-hidden="true">↓</span></a></div>
          <p class="history-meta"><time :datetime="item.builtAt">{{ date(item.builtAt) }}</time><code>{{ item.sha256.slice(0, 8) }}</code><span>{{ size(item.bytes) }}</span></p>
          <p class="history-description">{{ item.description ?? '历史开发构建，保留用于版本回溯与兼容验证。' }}</p>
          <details class="history-details"><summary>架构与文件校验</summary><p>Android API {{ item.minSdk }}+ · {{ item.abis.join(' / ') }} · {{ candidate(item) ? 'Release 优化候选，尚未设备验收' : 'Debug' }} · 开发签名 · 完整视觉配置</p><code class="download-hash">{{ item.sha256 }}</code><a :href="state === 'ready' ? withBase(item.checksumUrl) : undefined" :aria-disabled="state !== 'ready'" download>下载校验文件 ↗</a></details>
        </li>
      </ul>
      <p v-else class="history-note">暂无可下载的历史构建。后续发布会保留真实旧包与简短说明。</p>
    </section>

    <section class="download-install" aria-labelledby="install-title">
      <div><p class="download-kicker">开始之前</p><h2 id="install-title">三步，准备就绪。</h2></div>
      <ol><li><span>01</span><div><strong>下载安装</strong><p>下载 APK 后打开。仅在信任此来源时，允许浏览器或文件管理器安装未知应用。</p></div></li><li><span>02</span><div><strong>确认运行环境</strong><p>实际自动化需要 Root。安装后到「我的」检查权限与运行环境，按需授权悬浮窗。</p></div></li><li><span>03</span><div><strong>从第一个项目开始</strong><p>新建项目，加入积木，再运行验证。升级前建议先备份重要项目。</p></div></li></ol>
    </section>
  </main>
</template>

<style scoped>
.download-page { max-width: 960px; margin: 0 auto; padding: 44px 32px 64px; line-height: 1.7; }
.download-status { margin-bottom: 16px; font-size: 12px; color: var(--zg-muted); }
.download-status button { margin-left: 10px; color: var(--vt-c-brand); text-decoration: underline; }
a[aria-disabled="true"] { opacity: .55; cursor: wait; }
.download-kicker { color: var(--vt-c-brand); font-size: 10px; letter-spacing: 1.8px; font-weight: 600; }
.download-heading { margin-bottom: 29px; }
.download-heading h1 { font-size: clamp(30px, 4vw, 43px); line-height: 1.35; letter-spacing: -1.2px; margin: 11px 0; font-weight: 720; }
.download-heading h1 span { background: var(--zg-gradient); background-clip: text; -webkit-background-clip: text; color: transparent; }
.download-heading > p:last-child { font-size: 13px; color: var(--zg-muted); }
.download-card { padding: 28px 32px 22px; border: 1px solid var(--zg-border); border-radius: 16px; background: linear-gradient(130deg, var(--zg-tint), var(--vt-c-bg) 70%); box-shadow: 0 12px 40px #30204406; }
.download-card + .download-card { margin-top: 18px; }
.candidate-warning { border-left: 3px solid var(--vt-c-brand); padding: 10px 14px; margin-bottom: 18px; background: var(--zg-tint); color: var(--vt-c-text-2); font-size: 12px; }
.download-update { margin-bottom: 18px; padding: 14px 18px; border: 1px solid var(--zg-border); border-radius: 10px; font-size: 12px; }
.download-update > div { display: flex; flex-wrap: wrap; gap: 5px 14px; align-items: baseline; }
.download-update span, .download-update p { color: var(--zg-muted); font-size: 11px; }
.download-update p { margin: 6px 0; }
.download-brand { display: flex; align-items: center; gap: 18px; }
.download-brand img { flex-shrink: 0; object-fit: contain; }
.download-badge { color: var(--vt-c-brand); font-size: 10px; padding: 3px 7px; border-radius: 4px; background: var(--zg-tint); border: 1px solid var(--zg-border); }
.download-brand h2 { font-size: clamp(17px, 3vw, 23px); font-weight: 680; margin-top: 5px; overflow-wrap: anywhere; }
.download-brand h2 small { font: 12px ui-monospace, monospace; color: var(--zg-muted); margin-left: 8px; }
.download-brand p { font-size: 11px; color: var(--zg-muted); margin-top: 3px; }
.download-facts { display: grid; grid-template-columns: 1fr 1fr; gap: 16px 24px; margin: 24px 0; padding: 19px 0; border-block: 1px solid var(--zg-border); }
.download-facts dt { font-size: 10px; color: var(--zg-muted); margin-bottom: 3px; }
.download-facts dd { font-size: 12px; }
.download-facts dd span { color: var(--zg-muted); margin-left: 6px; font-size: 10px; }
.download-actions { display: flex; align-items: center; gap: 24px; flex-wrap: wrap; }
.download-button { display: flex; align-items: center; gap: 32px; border-radius: 7px; padding: 11px 18px; color: #fff; background: #7650cc; font-size: 13px; font-weight: 600; }
.download-button:hover { background: #6540b6; }
.download-guide { font-size: 11px; color: var(--vt-c-brand); }
.download-note, .download-unavailable { color: var(--zg-muted); font-size: 11px; margin-top: 18px; }
.release-description { font-size: 12px; margin-top: 20px; color: var(--vt-c-text-2); }
.download-facts code { display: inline-block; font-size: 10px; color: var(--zg-muted); }
.release-history { margin-top: 34px; border-top: 1px solid var(--zg-border); padding-top: 24px; }
.history-heading, .history-version { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.history-heading h2 { font-size: 21px; font-weight: 650; }
.history-heading > span, .history-note { font-size: 11px; color: var(--zg-muted); }
.history-note { margin-top: 8px; }
.history-list { list-style: none; padding: 0; margin: 16px 0 0; }
.history-list > li { padding: 18px 0; border-bottom: 1px solid var(--zg-border); }
.history-version h3 { font-size: 14px; font-weight: 600; }
.history-version h3 span { font-size: 10px; font-weight: 400; color: var(--zg-muted); margin-left: 6px; }
.history-version a { border: 1px solid var(--zg-border); border-radius: 6px; padding: 5px 11px; color: var(--vt-c-brand); font-size: 11px; white-space: nowrap; }
.history-version a:hover { background: var(--zg-tint); }
.history-meta { display: flex; flex-wrap: wrap; gap: 5px 14px; color: var(--zg-muted); font-size: 10px; margin-top: 7px; }
.history-description { font-size: 12px; margin-top: 8px; }
.history-details { font-size: 10px; color: var(--zg-muted); margin-top: 10px; }
.history-details summary { cursor: pointer; }
.history-details a { color: var(--vt-c-brand); }
.download-install { margin-top: 33px; display: grid; grid-template-columns: 190px 1fr; gap: 28px; }
.download-install h2 { font-size: 21px; font-weight: 650; margin-top: 8px; }
.download-install ol { list-style: none; margin: 0; padding: 0; display: grid; gap: 18px; }
.download-install li { display: flex; align-items: baseline; gap: 15px; }
.download-install li > span { flex-shrink: 0; font: 11px ui-monospace, monospace; color: var(--vt-c-brand); }
.download-install strong { font-size: 12px; font-weight: 600; }
.download-install li p { font-size: 11px; color: var(--zg-muted); margin-top: 3px; }
.download-integrity { border-top: 1px solid var(--zg-border); margin-top: 30px; padding-top: 17px; font-size: 11px; color: var(--zg-muted); }
.download-integrity summary { cursor: pointer; font-size: 12px; color: var(--vt-c-text-1); }
.download-integrity p { margin-top: 12px; }
.checksum-heading { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 10px; margin-top: 17px; }
.checksum-heading button, .download-integrity a { color: var(--vt-c-brand); cursor: pointer; }
.download-hash { display: block; user-select: all; overflow-wrap: anywhere; padding: 12px; border: 1px solid var(--zg-border); border-radius: 6px; margin: 8px 0; background: var(--zg-tint); font-size: 11px; }
@media (max-width: 600px) {
  .download-page { padding: 28px 22px 40px; }
  .download-card { padding: 22px 20px 18px; }
  .download-brand { gap: 12px; }
  .download-brand img { width: 60px; height: 60px; }
  .download-brand p { font-size: 10px; }
  .download-facts { gap: 15px; }
  .download-install { grid-template-columns: 1fr; gap: 17px; }
  .download-button { padding-inline: 15px; gap: 18px; }
}
@media (max-width: 360px) { .download-facts { grid-template-columns: 1fr; } }
</style>
