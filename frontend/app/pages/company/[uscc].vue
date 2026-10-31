<script setup lang="ts">
import type {
  CompanyDetail,
  EnterpriseResponse,
  PageResult,
  ReviewItem
} from '~/types/api'

interface CompanyPageData {
  detail: CompanyDetail
  reviews: PageResult<ReviewItem>
  responses: EnterpriseResponse[]
}

const api = useApi()
const route = useRoute()
const config = useRuntimeConfig()

const uscc = computed(() => String(route.params.uscc ?? '').trim())
const requestedPage = computed(() => {
  const raw = Array.isArray(route.query.page) ? route.query.page[0] : route.query.page
  return Math.max(1, Number.parseInt(String(raw ?? '1'), 10) || 1)
})

const { data, pending, error } = await useAsyncData<CompanyPageData>(
  'company-detail',
  async () => {
    try {
      const detail = await api.get<CompanyDetail>(`/api/companies/${encodeURIComponent(uscc.value)}`)

      if (detail.redirectTo) {
        return {
          detail,
          reviews: { items: [], page: 1, size: 20, total: 0 },
          responses: []
        }
      }

      const [reviews, responses] = await Promise.all([
        api.get<PageResult<ReviewItem>>(`/api/companies/${encodeURIComponent(uscc.value)}/reviews`, {
          page: requestedPage.value,
          size: 20
        }),
        api
          .get<EnterpriseResponse[]>(`/api/companies/${encodeURIComponent(uscc.value)}/responses`)
          .catch(() => [] as EnterpriseResponse[])
      ])

      return { detail, reviews, responses }
    } catch (err: unknown) {
      const status = (err as { statusCode?: number; status?: number })?.statusCode
        ?? (err as { status?: number })?.status
      if (status === 404) {
        throw createError({ statusCode: 404, statusMessage: '公司档案不存在' })
      }
      throw createError({ statusCode: 502, statusMessage: '公司档案加载失败' })
    }
  },
  {
    watch: [() => route.params.uscc, () => route.query.page]
  }
)

const company = computed(() => data.value?.detail ?? null)
const reviews = computed(() => data.value?.reviews?.items ?? [])

if (data.value?.detail?.redirectTo) {
  await navigateTo(companyPath(data.value.detail.redirectTo), { redirectCode: 301 })
}

const reviewTotal = computed(() => data.value?.reviews?.total ?? 0)
const reviewSize = computed(() => data.value?.reviews?.size || 20)
const reviewTotalPages = computed(() => Math.max(1, Math.ceil(reviewTotal.value / reviewSize.value)))
const currentPage = computed(() => data.value?.reviews?.page ?? requestedPage.value)
const reviewPageNumbers = computed(() => {
  let start = Math.max(1, currentPage.value - 2)
  const end = Math.min(reviewTotalPages.value, start + 4)
  start = Math.max(1, end - 4)
  return Array.from({ length: Math.max(0, end - start + 1) }, (_, index) => start + index)
})

const responseMap = computed<Record<string, EnterpriseResponse>>(() => {
  const map: Record<string, EnterpriseResponse> = {}
  for (const response of data.value?.responses ?? []) {
    if (response.reviewId) map[response.reviewId] = response
  }
  return map
})

const dimensionRows = computed(() =>
  DIMENSION_ORDER.map((code) => {
    const found = company.value?.dimensions?.find((item) => item.code === code)
    return found ?? { code, average: null, sampleCount: 0, enoughSample: false }
  })
)

const dimensionsWithSample = computed(() => dimensionRows.value.filter((item) => item.enoughSample))
const canDrawRadar = computed(() => dimensionsWithSample.value.length >= 3)
const allDimensionEmpty = computed(() =>
  dimensionRows.value.every((item) => item.sampleCount === 0)
)

const companyName = computed(() => company.value?.name || '公司档案')
const pageTitle = computed(
  () => `${companyName.value} 怎么样_评价_拖欠工资_靠谱吗 · 外包点评`
)
const pageDescription = computed(() => {
  const detail = company.value
  if (!detail) return '外包公司档案与评价。'
  const location = [detail.city, detail.industry].filter(Boolean).join(' · ')
  const base = `${companyName.value}${location ? `（${location}）` : ''}的外包公司档案。`
  if (detail.scoreVisible && detail.avgScore !== null) {
    return `${base}综合分 ${formatScore(detail.avgScore)} / 5，基于 ${detail.reviewCount} 条已发布评价。评价内容经在职佐证核验后展示。`
  }
  return `${base}当前已发布评价 ${detail.reviewCount} 条，评价不足 3 条时暂不展示综合分。`
})

useSeoMeta({
  title: pageTitle,
  description: pageDescription,
  ogTitle: pageTitle,
  ogDescription: pageDescription
})

const siteUrl = String(config.public.siteUrl || '').replace(/\/+$/, '')
const canonicalUrl = computed(() => `${siteUrl}${companyPath(uscc.value)}`)

const jsonLd = computed(() => {
  const detail = company.value
  if (!detail || !detail.name) return null

  const organization: Record<string, unknown> = {
    '@type': 'Organization',
    '@id': canonicalUrl.value,
    name: detail.name,
    url: canonicalUrl.value,
    identifier: detail.uscc
  }
  if (detail.shortName) organization.alternativeName = detail.shortName
  if (detail.city) {
    organization.address = {
      '@type': 'PostalAddress',
      addressLocality: detail.city
    }
  }
  if (detail.scoreVisible && detail.avgScore !== null && detail.reviewCount >= 3) {
    organization.aggregateRating = {
      '@type': 'AggregateRating',
      ratingValue: detail.avgScore,
      reviewCount: detail.reviewCount,
      bestRating: 5,
      worstRating: 1
    }
  }

  const reviewNodes = reviews.value
    .filter((review) => review.status === 'PUBLISHED')
    .map((review) => ({
      '@type': 'Review',
      itemReviewed: {
        '@type': 'Organization',
        name: detail.name,
        identifier: detail.uscc
      },
      reviewRating: {
        '@type': 'Rating',
        ratingValue: review.totalScore,
        bestRating: 5,
        worstRating: 1
      },
      author: {
        '@type': 'Person',
        name: review.identityAlias || '匿名用户'
      },
      ...(review.publishedAt ? { datePublished: review.publishedAt } : {}),
      reviewBody: review.content
    }))

  return {
    '@context': 'https://schema.org',
    '@graph': [organization, ...reviewNodes]
  }
})

useHead(
  computed(() => ({
    link: [{ rel: 'canonical', href: canonicalUrl.value }],
    script: jsonLd.value
      ? [
          {
            key: 'company-jsonld',
            type: 'application/ld+json',
            innerHTML: jsonLdSafe(jsonLd.value)
          }
        ]
      : []
  }))
)

function pageQuery(targetPage: number): Record<string, string> {
  return targetPage > 1 ? { page: String(targetPage) } : {}
}

const reviewLink = computed(() => ({
  path: '/review/new',
  query: { uscc: uscc.value }
}))

const reportLink = computed(() => ({
  path: '/report',
  query: { target: `company:${uscc.value}` }
}))

const responseTicketId = ref('')
const responseContent = ref('')
const responseSubmitting = ref(false)
const responseError = ref('')
const responseSuccess = ref('')

async function submitEnterpriseResponse() {
  responseError.value = ''
  responseSuccess.value = ''
  if (!/^[0-9a-fA-F-]{36}$/.test(responseTicketId.value.trim())) {
    responseError.value = '请填写投诉工单号（36 位 UUID）'
    return
  }
  const content = responseContent.value.trim()
  if (!content) {
    responseError.value = '请填写企业回应内容'
    return
  }
  if ([...content].length > 500) {
    responseError.value = '企业回应不能超过 500 字'
    return
  }
  if (/1[3-9]\d{9}|微信|weixin|wechat|QQ号|qq号|加我|私聊|联系我|扫码/i.test(content)) {
    responseError.value = '企业回应不得包含手机号、微信、QQ 等联系方式'
    return
  }

  responseSubmitting.value = true
  try {
    await api.post(`/api/companies/${encodeURIComponent(uscc.value)}/responses`, {
      ticketId: responseTicketId.value.trim(),
      content
    })
    responseSuccess.value = '企业回应已提交。审核通过后展示在对应评价下方，标注「企业回应」，不计分、不改变评价状态。'
    responseTicketId.value = ''
    responseContent.value = ''
  } catch (error) {
    responseError.value = (error as { message?: string }).message || '企业回应提交失败，请稍后再试'
  } finally {
    responseSubmitting.value = false
  }
}
</script>

<template>
  <main class="wrap page">
    <nav class="breadcrumb" aria-label="面包屑">
      <NuxtLink to="/">首页</NuxtLink> /
      <NuxtLink to="/search">搜索</NuxtLink> /
      <span>{{ company?.name || uscc }}</span>
    </nav>

    <div v-if="pending" class="skeleton-list">
      <div class="skeleton skeleton-card" style="height: 180px" />
      <div class="skeleton skeleton-card" style="height: 240px" />
      <div class="skeleton skeleton-card" />
    </div>

    <div v-else-if="error" class="empty-state">
      <h2>公司档案暂时无法打开</h2>
      <p>请稍后再试，或返回搜索页换一家公司。</p>
      <NuxtLink class="button primary" to="/search">返回搜索</NuxtLink>
    </div>

    <div v-else-if="company" class="profile-grid">
      <div class="profile-main">
        <section class="paper-card profile-card">
          <div class="mono">COMPANY ARCHIVE · 公司档案</div>
          <h1>{{ company.name }}</h1>
          <div v-if="company.shortName" class="profile-sub">简称：{{ company.shortName }}</div>

          <div v-if="company.aliases && company.aliases.length" class="profile-meta">
            <span>别名 <b>{{ company.aliases.join('、') }}</b></span>
          </div>

          <div class="profile-meta">
            <span>USCC <b>{{ company.uscc }}</b></span>
            <span v-if="company.city">城市 <b>{{ company.city }}</b></span>
            <span v-if="company.industry">行业 <b>{{ company.industry }}</b></span>
            <span v-if="company.sourceLabel">信息来源 <b>{{ company.sourceLabel }}</b></span>
          </div>

          <div class="score-panel">
            <div v-if="company.scoreVisible && company.avgScore !== null" class="score-main">
              <div class="score-big">{{ formatScore(company.avgScore) }}</div>
              <div class="score-stars">
                <StarRating :score="company.avgScore" :show-number="false" />
                <span class="score-desc">综合分 · 5 分制 · 基于 {{ company.reviewCount }} 条已发布评价</span>
              </div>
            </div>
            <div v-else class="score-empty">
              {{ company.scoreNote || '评价不足，暂不评分' }}
              <span>已发布评价 {{ company.reviewCount }} 条；有效评价满 3 条后才展示综合分。</span>
            </div>
            <div v-if="company.scoreVisible" class="score-desc">
              争议中与已折叠评价不计入综合分，排序按核验强度与时间衰减加权。
            </div>
          </div>
        </section>

        <section class="paper-card profile-card" style="margin-top: 18px">
          <div class="section-head">
            <h2>六维均分</h2>
            <span class="mono">DIMENSIONS · 样本门槛 3 条</span>
          </div>

          <div v-if="allDimensionEmpty" class="empty-state">
            <h2>暂无维度评分</h2>
            <p>维度分只统计已发布评价，且每个维度需要至少 3 条样本才会展示均分。</p>
          </div>

          <div v-else class="dimension-panel">
            <div class="radar-wrap">
              <DimensionRadar v-if="canDrawRadar" :dimensions="company.dimensions" />
              <div v-else class="metric-note" style="margin: 0">
                至少 3 个维度有足够样本时才绘制雷达图；当前 {{ dimensionsWithSample.length }} 个维度达到门槛。
                未达门槛的维度显示「样本不足」，不参与图表填充。
              </div>
            </div>

            <div class="dimension-list">
              <div v-for="row in dimensionRows" :key="row.code" class="dimension-row">
                <span class="dim-name">{{ dimensionLabel(row.code) }}</span>
                <div class="dim-bar" role="img" :aria-label="`${dimensionLabel(row.code)}得分`">
                  <span
                    :style="{ width: row.enoughSample ? `${(Number(row.average ?? 0) / 5) * 100}%` : '0%' }"
                  />
                </div>
                <span class="dim-value" :class="{ insufficient: !row.enoughSample }">
                  <template v-if="row.enoughSample">
                    {{ formatScore(row.average) }} 分 · {{ row.sampleCount }} 条
                  </template>
                  <template v-else>样本不足 · {{ row.sampleCount }} 条</template>
                </span>
              </div>
            </div>
          </div>
        </section>

        <section class="reviews-section">
          <div class="section-head">
            <h2>评价（{{ reviewTotal }}）</h2>
            <span class="mono">按核验强度与时间加权排序</span>
          </div>

          <div v-if="reviews.length" class="review-list">
            <ReviewCard
              v-for="review in reviews"
              :key="review.id"
              :review="review"
              :enterprise-response="responseMap[review.id] || null"
            />
          </div>

          <div v-else class="empty-state">
            <h2>暂无评价，如果你在这家公司待过，可以写第一条</h2>
            <p>发布评价前需完成实名与在职佐证核验。材料只用于核验，前台不展示原始材料。</p>
            <NuxtLink class="button primary" :to="reviewLink">去写第一条评价</NuxtLink>
          </div>

          <nav v-if="reviewTotalPages > 1" class="pagination" aria-label="评价分页">
            <NuxtLink
              v-if="currentPage > 1"
              :to="{ path: companyPath(company.uscc), query: pageQuery(currentPage - 1) }"
              aria-label="上一页"
            >
              上一页
            </NuxtLink>
            <span v-else class="disabled" aria-disabled="true">上一页</span>

            <NuxtLink
              v-for="number in reviewPageNumbers"
              :key="number"
              :to="{ path: companyPath(company.uscc), query: pageQuery(number) }"
              :class="{ current: number === currentPage }"
              :aria-current="number === currentPage ? 'page' : undefined"
            >
              {{ number }}
            </NuxtLink>

            <NuxtLink
              v-if="currentPage < reviewTotalPages"
              :to="{ path: companyPath(company.uscc), query: pageQuery(currentPage + 1) }"
              aria-label="下一页"
            >
              下一页
            </NuxtLink>
            <span v-else class="disabled" aria-disabled="true">下一页</span>
          </nav>
        </section>

        <section id="enterprise-response" class="paper-card profile-card enterprise-response-card">
          <div class="section-head">
            <h2>企业回应提交</h2>
            <span class="mono">RESPONSE · 凭投诉工单</span>
          </div>
          <p class="hint" style="margin-top: 0">
            经主体核验的企业可凭投诉工单号提交不超过 500 字的回应。回应会过滤手机号、微信、QQ、邮箱与网址；
            审核通过后展示在对应评价下方并标注「企业回应」，不计分、不改变评价状态，企业不可自行删除。
          </p>

          <form class="form-grid" @submit.prevent="submitEnterpriseResponse">
            <div class="field form-span">
              <label for="response-ticket">投诉工单号</label>
              <input
                id="response-ticket"
                v-model="responseTicketId"
                class="input"
                type="text"
                autocomplete="off"
                placeholder="企业申诉成功后获得的工单 UUID"
              />
            </div>
            <div class="field form-span">
              <label for="response-content">回应内容（≤500 字）</label>
              <textarea
                id="response-content"
                v-model="responseContent"
                class="input textarea"
                rows="4"
                maxlength="500"
                placeholder="请针对评价中提到的具体事实作出回应，不要填写联系方式或外部链接。"
              />
            </div>
            <div class="field form-actions">
              <button class="button primary" type="submit" :disabled="responseSubmitting">
                {{ responseSubmitting ? '提交中…' : '提交企业回应' }}
              </button>
            </div>
          </form>
          <p v-if="responseError" class="form-error" role="alert">{{ responseError }}</p>
          <p v-if="responseSuccess" class="form-success">{{ responseSuccess }}</p>
          <p class="hint" style="margin-bottom: 0">
            尚未提交企业申诉？请使用 <NuxtLink to="/appeal">企业申诉公开表单</NuxtLink>。
          </p>
        </section>
      </div>

      <aside>
        <div class="paper-card side-card">
          <h2>参与这家公司的档案</h2>
          <p>登录并完成在职佐证核验后，可以发布一条基于真实工作经历的评价。</p>
          <div class="side-actions">
            <NuxtLink class="button primary" :to="reviewLink">我要评价</NuxtLink>
            <NuxtLink class="button" :to="reportLink">举报公司信息</NuxtLink>
            <NuxtLink class="button ghost" to="/appeal">企业申诉入口</NuxtLink>
          </div>
          <p style="margin-top: 12px; margin-bottom: 0; font-size: 12px">
            举报与申诉需登录后提交。企业申诉需核对统一社会信用代码与主体材料。
          </p>
        </div>

        <div class="paper-card side-card">
          <h2>企业回应</h2>
          <p>
            经核验的企业可凭投诉工单号提交不超过 500 字的回应。审核通过后展示在对应评价下方，
            标注「企业回应」，不计分、不改变评价状态。
          </p>
          <NuxtLink class="button ghost" to="/appeal">查看回应提交说明</NuxtLink>
        </div>

        <div class="paper-card side-card">
          <h2>档案说明</h2>
          <div class="side-links">
            <span class="muted">USCC 是公司档案的稳定标识，改名不换链接。</span>
            <span class="muted">综合分只统计已发布评价；争议中、折叠、编辑中均不计入。</span>
            <NuxtLink to="/rankings">查看城市与行业双榜</NuxtLink>
            <NuxtLink to="/terms">评价规则与用户协议</NuxtLink>
          </div>
        </div>
      </aside>
    </div>
  </main>
</template>
