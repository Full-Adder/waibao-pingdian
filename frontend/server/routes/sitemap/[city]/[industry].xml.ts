export default defineEventHandler(async (event) => {
  const segments = getRequestURL(event).pathname.split('/').filter(Boolean)
  const city = decodeURIComponent(segments[1] || '')
  const industry = decodeURIComponent(segments[2] || '').replace(/\.xml$/i, '')
  if (!city || !industry) {
    throw createError({ statusCode: 404, statusMessage: '缺少城市或行业' })
  }

  const site = serverSiteUrl(event)
  let entries: BackendSitemapEntry[] = []
  try {
    entries = await fetchBackend<BackendSitemapEntry[]>(event, '/api/companies/sitemap', {
      city,
      industry
    })
  } catch {
    throw createError({ statusCode: 502, statusMessage: '公司档案清单暂时不可用' })
  }

  const urls = entries
    .filter((entry) => entry.uscc)
    .map((entry) => {
      const lastmod = entry.updatedAt ? `<lastmod>${escapeXml(entry.updatedAt)}</lastmod>` : ''
      return `  <url><loc>${escapeXml(`${site}/company/${encodeURIComponent(entry.uscc)}`)}</loc>${lastmod}</url>`
    })

  setHeader(event, 'Content-Type', 'application/xml; charset=utf-8')
  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    ...urls,
    '</urlset>'
  ].join('\n')
})
