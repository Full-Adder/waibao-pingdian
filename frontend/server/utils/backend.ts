interface BackendEnvelope<T> {
  code: number
  message: string
  data: T
}

export interface BackendSitemapEntry {
  uscc: string
  name: string
  updatedAt: string | null
}

export function serverSiteUrl(event: Parameters<typeof useRuntimeConfig>[0]): string {
  const config = useRuntimeConfig(event)
  return String(config.public.siteUrl || 'http://localhost:3000').replace(/\/+$/, '')
}

export async function fetchBackend<T>(
  event: Parameters<typeof useRuntimeConfig>[0],
  path: string,
  query?: Record<string, string>
): Promise<T> {
  const config = useRuntimeConfig(event)
  const base = String(config.public.apiBase || 'http://localhost:8080').replace(/\/+$/, '')
  const response = await $fetch<BackendEnvelope<T>>(`${base}${path}`, { query })
  if (!response || response.code !== 0) {
    throw createError({ statusCode: 502, statusMessage: '后端接口返回异常' })
  }
  return response.data
}

export function escapeXml(value: unknown): string {
  return String(value ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;')
}

export function sitemapShardPath(city: string, industry: string): string {
  return `/sitemap/${encodeURIComponent(city)}/${encodeURIComponent(industry)}.xml`
}
