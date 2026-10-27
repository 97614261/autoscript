import { defineConfig } from 'vitepress'
import vueTheme from '@vue/theme/config'
import { loadCatalog } from '../scripts/catalog.mjs'
import { repository } from '../scripts/generate.mjs'

const catalog = loadCatalog(repository)
const guideItems = [
  { text: '介绍与准备', link: '/guide/' },
  { text: '第一个自动化项目', link: '/guide/quick-start' },
  { text: '插件、分支与连续编辑', link: '/guide/editor-workflow' },
  { text: '安装、升级与备份', link: '/guide/install-upgrade' },
  { text: '个人服务器项目备份', link: '/guide/server-backup' },
  { text: 'App 内更新', link: '/guide/app-update' },
  { text: '图像与文字识别', link: '/guide/vision' },
  { text: '滤色找图与字库取材', link: '/guide/image-filters' },
  { text: '蒙版、清理与稳定识别', link: '/guide/image-workbench' },
  { text: '能力与资源约定', link: '/guide/contracts' },
  { text: '调试与运行日志', link: '/guide/debugging' },
  { text: '制作基础字库', link: '/guide/glyph-dictionary' }
]

export default defineConfig({
  extends: { ...vueTheme, head: [] },
  lang: 'zh-CN',
  title: '智构',
  titleTemplate: ':title · 智构文档',
  description: '智构使用指南与完整脚本 API 参考。可视化编排，Root 自动化，本地优先。',
  srcDir: 'src',
  cleanUrls: true,
  lastUpdated: false,
  ignoreDeadLinks: false,
  head: [['link', { rel: 'icon', type: 'image/png', href: '/zhigou-logo.png' }]],
  vite: {
    // The theme and VitePress use different VueUse major versions. Keep each
    // import's own shared helpers in the SSR bundle instead of resolving a
    // hoisted external shared package from the generated bundle directory.
    ssr: { noExternal: ['@vueuse/shared'] }
  },
  themeConfig: {
    nav: [
      { text: '使用指南', link: '/guide/', activeMatch: '^/guide/' },
      { text: 'API 参考', link: '/api/', activeMatch: '^/api/' },
      { text: '下载', link: '/download', activeMatch: '^/download' }
    ],
    sidebar: {
      '/releases/': [{ text: '更新记录', items: [{ text: '全部记录', link: '/releases/' }, { text: '2026-10-22 点击与字库', link: '/releases/2026-10-22-input-and-glyph' }, { text: '2026-10-21 App 内更新', link: '/releases/2026-10-21-app-update' }, { text: '2026-10-21 单架构优化候选', link: '/releases/2026-10-21-optimized' }, { text: '2026-10-21 编辑与运行收尾', link: '/releases/2026-10-21' }, { text: '下载与历史版本', link: '/download' }] }],
      '/guide/': [{ text: '开始使用', items: guideItems }],
      '/api/': [
        { text: 'API 参考', items: [{ text: '搜索全部 API', link: '/api/' }, { text: '能力与资源约定', link: '/guide/contracts' }] },
        ...catalog.groups.map(group => ({ text: `${group.title} · ${group.apis.length}`, items: group.apis.map(api => ({ text: api.name, link: api.link })) }))
      ]
    },
    i18n: {
      search: '搜索', menu: '目录', toc: '本页内容', returnToTop: '回到顶部',
      appearance: '外观', previous: '上一页', next: '下一页', pageNotFound: '页面不存在',
      ariaDarkMode: '切换深浅色主题', ariaSkipToContent: '跳转到正文',
      ariaToC: '本页目录', ariaMainNav: '主导航', ariaMobileNav: '移动导航', ariaSidebarNav: '文档导航'
    },
    footer: { copyright: '智构 · 可视化自动化工具' }
  }
})
