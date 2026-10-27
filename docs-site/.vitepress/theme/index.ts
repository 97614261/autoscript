import { h } from 'vue'
import { useRouter, withBase } from 'vitepress'
import { VPTheme } from '@vue/theme'
import HomePage from './HomePage.vue'
import ApiCatalog from './ApiCatalog.vue'
import DownloadPage from './DownloadPage.vue'
import './style.css'

export default {
  ...VPTheme,
  Layout() {
    const router = useRouter()
    return h(VPTheme.Layout, null, {
      'navbar-title': () => [
        h('img', { class: 'zhigou-nav-logo', src: withBase('/zhigou-logo.png'), alt: '', width: 32, height: 32 }),
        h('span', { class: 'zhigou-nav-name' }, '智构'),
        h('span', { class: 'zhigou-nav-label' }, '文档')
      ],
      'sidebar-top': () => h('a', { class: 'zhigou-sidebar-search', href: withBase('/api/'), onClick: (event: MouseEvent) => {
        if (!event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey && event.button === 0) {
          event.preventDefault()
          router.go(withBase('/api/'))
        }
      } }, '搜索 API  →')
    })
  },
  enhanceApp({ app }) {
    app.component('HomePage', HomePage)
    app.component('ApiCatalog', ApiCatalog)
    app.component('DownloadPage', DownloadPage)
  }
}
