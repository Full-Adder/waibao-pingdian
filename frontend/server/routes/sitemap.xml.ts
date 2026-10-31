const CITIES = ['北京', '上海', '深圳', '杭州', '广州', '成都']
const INDUSTRIES = ['IT人力外包', 'IT项目外包']

export default defineEventHandler((event) => {
  setHeader(event, 'Content-Type', 'application/xml; charset=utf-8')
  const site = serverSiteUrl(event)

  const shards = CITIES.flatMap((city) =>
    INDUSTRIES.map(
      (industry) =>
        `  <sitemap><loc>${escapeXml(`${site}${sitemapShardPath(city, industry)}`)}</loc></sitemap>`
    )
  )

  return [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<sitemapindex xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">',
    ...shards,
    '</sitemapindex>'
  ].join('\n')
})
