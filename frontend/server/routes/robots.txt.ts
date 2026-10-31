export default defineEventHandler((event) => {
  setHeader(event, 'Content-Type', 'text/plain; charset=utf-8')
  const site = serverSiteUrl(event)
  return ['User-agent: *', 'Allow: /', `Sitemap: ${site}/sitemap.xml`, ''].join('\n')
})
