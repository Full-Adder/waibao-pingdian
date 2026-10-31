<script setup lang="ts">
import type { CompanySummary, PageResult } from '~/types/api'

const api = useApi()
const route = useRoute()

function singleValue(value: unknown): string {
  if (Array.isArray(value)) return String(value[0] ?? '')
  return value === null || value === undefined ? '' : String(value)
}

const filters = computed(() => ({
  keyword: singleValue(route.query.keyword).trim(),
  city: singleValue(route.query.city).trim(),
  industry: singleValue(route.query.industry).trim(),
  page: Math.max(1, Number.parseInt(singleValue(route.query.page) || '1', 10) || 1)
}))

const form = reactive({
  keyword: filters.value.keyword,
  city: filters.value.city,
  industry: filters.value.industry
})

watch(
  filters,
  (next) => {
    form.keyword = next.keyword
    form.city = next.city
    form.industry = next.industry
  },
  { deep: true }
)

const { data, pending, error } = await useAsyncData<PageResult<CompanySummary>>(
  'company-search',
  () =>
    api.get<PageResult<CompanySummary>>('/api/companies/search', {
      keyword: filters.value.keyword || undefined,
      city: filters.value.city || undefined,
      industry: filters.value.industry || undefined,
      page: filters.value.page,
      size: 20
    }),
  {
    watch: [
      () => route.query.keyword,
      () => route.query.city,
      () => route.query.industry,
      () => route.query.page
    ]
  }
)

const items = computed(() => data.value?.items ?? [])
const total = computed(() => data.value?.total ?? 0)
const page = computed(() => data.value?.page ?? filters.value.page)
const totalPages = computed(() => Math.max(1, Math.ceil(total.value / (data.value?.size || 20))))

const pageNumbers = computed(() => {
  const pages = totalPages.value
  let start = Math.max(1, page.value - 2)
  const end = Math.min(pages, start + 4)
  start = Math.max(1, end - 4)
  return Array.from({ length: Math.max(0, end - start + 1) }, (_, index) => start + index)
})

const hasFilters = computed(() => Boolean(filters.value.keyword || filters.value.city || filters.value.industry))

function queryFor(targetPage: number): Record<string, string> {
  const query: Record<string, string> = {}
  if (filters.value.keyword) query.keyword = filters.value.keyword
  if (filters.value.city) query.city = filters.value.city
  if (filters.value.industry) query.industry = filters.value.industry
  if (targetPage > 1) query.page = String(targetPage)
  return query
}

function applyFilters() {
  const query: Record<string, string> = {}
  if (form.keyword.trim()) query.keyword = form.keyword.trim()
  if (form.city) query.city = form.city
  if (form.industry) query.industry = form.industry
  navigateTo({ path: '/search', query })
}

const title = computed(() => {
  const keyword = filters.value.keyword
  const city = filters.value.city
  const industry = filters.value.industry
  if (keyword) return `${keyword} 外包公司搜索 · 外包点评`
  if (city || industry) return `${[city, industry].filter(Boolean).join(' · ')} 外包公司 · 外包点评`
  return '搜索外包公司 · 外包点评'
})

useSeoMeta({
  title,
  description:
    '按公司名称、简称、城市与行业检索外包公司。未评价公司同样出现在结果中，并显示首评引导，而不是没有信息量的 0 分。'
})
</script>

<template>
  <main class="wrap page">
    <div class="mono">SEARCH · 公司检索</div>
    <h1>搜索外包公司</h1>
    <p class="meta">输入名称或简称，或按城市、行业筛选。没有评价的公司也会进入结果。</p>

    <form class="paper-card search-panel" @submit.prevent="applyFilters">
      <div class="search-fields">
        <div class="field keyword-field">
          <label for="search-keyword">公司名称 / 简称</label>
          <input
            id="search-keyword"
            v-model="form.keyword"
            class="input"
            type="search"
            placeholder="例如「极光软件」"
            autocomplete="off"
          />
        </div>
        <div class="field">
          <label for="search-city">城市</label>
          <select id="search-city" v-model="form.city" class="select">
            <option value="">全部城市</option>
            <option v-for="city in CITIES" :key="city" :value="city">{{ city }}</option>
          </select>
        </div>
        <div class="field">
          <label for="search-industry">行业</label>
          <select id="search-industry" v-model="form.industry" class="select">
            <option value="">全部行业</option>
            <option v-for="industry in INDUSTRIES" :key="industry" :value="industry">{{ industry }}</option>
          </select>
        </div>
        <button class="button primary" type="submit">筛选</button>
      </div>
    </form>

    <div v-if="hasFilters" class="chip-set" style="margin-bottom: 18px">
      <span v-if="filters.keyword" class="chip active">关键字：{{ filters.keyword }}</span>
      <span v-if="filters.city" class="chip active">城市：{{ filters.city }}</span>
      <span v-if="filters.industry" class="chip active">行业：{{ filters.industry }}</span>
      <NuxtLink class="chip" to="/search">清除筛选</NuxtLink>
    </div>

    <div v-if="pending" class="skeleton-list">
      <div class="skeleton skeleton-card" />
      <div class="skeleton skeleton-card" />
      <div class="skeleton skeleton-card" />
    </div>

    <div v-else-if="error" class="empty-state">
      <h2>搜索服务暂时不可用</h2>
      <p>请稍后再试。你也可以先浏览榜单，或稍后重新搜索。</p>
      <NuxtLink class="button ghost" to="/rankings">去看榜单</NuxtLink>
    </div>

    <template v-else>
      <div class="result-summary">
        <span>共 {{ total }} 家公司 · 第 {{ page }} / {{ totalPages }} 页</span>
        <span v-if="filters.keyword">按名称、简称与别名匹配</span>
      </div>

      <div v-if="items.length" class="company-list">
        <article v-for="company in items" :key="company.uscc" class="company-card">
          <div>
            <h2 class="company-name">
              <NuxtLink :to="companyPath(company.uscc)">{{ company.name }}</NuxtLink>
            </h2>
            <div v-if="company.shortName" class="muted" style="font-size: 13px; margin-bottom: 4px">
              简称：{{ company.shortName }}
            </div>
            <div class="company-meta">
              <span>USCC {{ company.uscc }}</span>
              <span v-if="company.city">{{ company.city }}</span>
              <span v-if="company.industry">{{ company.industry }}</span>
              <span v-if="company.source">来源：{{ sourceLabel(company.source) }}</span>
              <span>评价 {{ company.reviewCount }} 条</span>
            </div>
          </div>
          <div class="company-score">
            <template v-if="company.scoreVisible && company.avgScore !== null">
              <div class="score-value">{{ formatScore(company.avgScore) }}</div>
              <div class="score-label">综合分 · 5 分制</div>
            </template>
            <template v-else>
              <div class="score-label" style="font-size: 13px; color: var(--ink)">评价不足</div>
              <div class="score-label">暂不评分</div>
            </template>
          </div>
        </article>
      </div>

      <div v-else class="empty-state">
        <h2>没有找到匹配的公司</h2>
        <p>
          可以换一个名称或简称再试，或者取消城市与行业筛选。
          如果这家公司确实没有档案，可先了解公司上报流程。
        </p>
        <div class="button-row" style="justify-content: center">
          <NuxtLink class="button ghost" to="/search">查看全部公司</NuxtLink>
          <NuxtLink class="button" to="/report-company">上报公司</NuxtLink>
        </div>
        <p style="margin-top: 16px; font-size: 12.5px">
          如果这家公司确实没有档案，可以
          <NuxtLink to="/report-company">提交公司上报</NuxtLink>
          ：系统会先匹配工商库，未命中只进入待核验池。
        </p>
      </div>

      <nav v-if="totalPages > 1" class="pagination" aria-label="搜索结果分页">
        <NuxtLink
          v-if="page > 1"
          :to="{ path: '/search', query: queryFor(page - 1) }"
          aria-label="上一页"
        >
          上一页
        </NuxtLink>
        <span v-else class="disabled" aria-disabled="true">上一页</span>

        <NuxtLink
          v-for="number in pageNumbers"
          :key="number"
          :to="{ path: '/search', query: queryFor(number) }"
          :class="{ current: number === page }"
          :aria-current="number === page ? 'page' : undefined"
        >
          {{ number }}
        </NuxtLink>

        <NuxtLink
          v-if="page < totalPages"
          :to="{ path: '/search', query: queryFor(page + 1) }"
          aria-label="下一页"
        >
          下一页
        </NuxtLink>
        <span v-else class="disabled" aria-disabled="true">下一页</span>
      </nav>
    </template>
  </main>
</template>
