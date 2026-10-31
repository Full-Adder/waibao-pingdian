function createVisitorId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
    const random = (Math.random() * 16) | 0
    const value = char === 'x' ? random : (random & 0x3) | 0x8
    return value.toString(16)
  })
}

/**
 * 匿名访问埋点：仅上报本地生成的随机 visitorId、路径与 UA，不采集个人明文。
 */
export default defineNuxtPlugin(() => {
  const config = useRuntimeConfig()
  const route = useRoute()
  const apiBase = String(config.public.apiBase || '').replace(/\/+$/, '')

  let visitorId = ''
  try {
    visitorId = localStorage.getItem('waibao_visitor_id') || ''
  } catch {
    visitorId = ''
  }
  if (!visitorId) {
    visitorId = createVisitorId()
    try {
      localStorage.setItem('waibao_visitor_id', visitorId)
    } catch {
      // 隐私模式或禁用存储时只在本次会话内复用。
    }
  }

  let lastPath = ''
  const report = (path: string) => {
    if (!path || path.startsWith('/_') || path === lastPath) return
    lastPath = path
    // 使用原生 fetch + keepalive：页面切换/刷新时请求不会因导航被浏览器中止，
    // 避免控制台出现 net::ERR_ABORTED；失败仍静默处理。
    void fetch(`${apiBase}/api/track/visit`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({
        visitorId,
        path,
        userAgent: navigateUserAgent()
      }),
      keepalive: true
    }).catch(() => {
      // 埋点失败静默，不影响页面。
    })
  }

  watch(() => route.fullPath, report, { immediate: true })
})

function navigateUserAgent(): string {
  return typeof navigator === 'undefined' ? '' : navigator.userAgent.slice(0, 500)
}
