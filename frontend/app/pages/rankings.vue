<script setup lang="ts">
import type { RankingPage } from '~/types/api'

const DEFAULT_CITY = '北京'
const DEFAULT_INDUSTRY = 'IT人力外包'

const route = useRoute()
const api = useApi()

function singleValue(value: unknown): string {
  if (Array.isArray(value)) return String(value[0] ?? '')
  return value === null || value === undefined ? '' : String(value)
}

const city = computed(() => singleValue(route.query.city) || DEFAULT_CITY)
const industry = computed(() => singleValue(route.query.industry) || DEFAULT_INDUSTRY)
const rankingType = computed<'RECOMMEND' | 'IMPROVE'>(() =>
  singleValue(route.query.type).toUpperCase() === 'IMPROVE' ? 'IMPROVE' : 'RECOMMEND'
)
const typeLabel = computed(() => (rankingType.value === 'RECOMMEND' ? '推荐榜' : '待改善名单'))

const { data, pending, error } = await useAsyncData<RankingPage>(
  'ranking-page',
  () =>
    api.get<RankingPage>('/api/rankings', {
      city: city.value,
      industry: industry.value,
      type: rankingType.value,
      limit: 50
    }),
  {
    watch: [() => route.query.city, () => route.query.industry, () => route.query.type]
  }
)

const items = computed(() => data.value?.items ?? [])
const metricDefinitions = computed(() => data.value?.metricDefinitions ?? null)
const params = computed<Record<string, unknown>>(() => data.value?.params ?? {})
const minSample = computed(() => Number(params.value.minSample ?? 3))
const recommendPercentile = computed(() => Number(params.value.recommendPercentile ?? 0.2))
const improveParams = computed(
  () => (params.value.improve ?? {}) as Record<string, number>
)
const threshold = computed(() => data.value?.threshold ?? null)

function buildQuery(overrides: Record<string, string> = {}): Record<string, string> {
  return {
    city: city.value,
    industry: industry.value,
    type: rankingType.value,
    ...overrides
  }
}

function onSelectChange(event: Event, key: 'city' | 'industry') {
  const value = (event.target as HTMLSelectElement).value
  if (!value) return
  navigateTo({ path: '/rankings', query: buildQuery({ [key]: value }) })
}

useSeoMeta({
  title: () => `${city.value}${industry.value}${typeLabel.value} · 外包点评`,
  description: () =>
    `${city.value}${industry.value}的${typeLabel.value}。展示算法版本、计算时间、样本门槛、样本数、低分集中度、争议率与排序分，只呈现可核对指标。`,
  ogTitle: () => `${city.value}${industry.value}${typeLabel.value} · 外包点评`
})
</script>

<template>
  <main class="wrap page">
    <div class="mono">RANKINGS · 城市与行业双榜</div>
    <h1>外包公司双榜</h1>
    <p class="meta">
      推荐榜按加权分与分位阈值排序；待改善名单按低分集中度与争议率的组合排序。
      所有指标每日重算，算法参数带版本号。
    </p>

    <div class="paper-card filter-bar">
      <div class="field">
        <label for="ranking-city">城市</label>
        <select id="ranking-city" class="select" :value="city" @change="onSelectChange($event, 'city')">
          <option v-for="option in CITIES" :key="option" :value="option">{{ option }}</option>
        </select>
      </div>
      <div class="field">
        <label for="ranking-industry">行业</label>
        <select
          id="ranking-industry"
          class="select"
          :value="industry"
          @change="onSelectChange($event, 'industry')"
        >
          <option v-for="option in INDUSTRIES" :key="option" :value="option">{{ option }}</option>
        </select>
      </div>
      <div class="field">
        <span class="mono" style="color: var(--muted)">榜单类型</span>
        <div class="segmented" role="tablist" aria-label="榜单类型">
          <NuxtLink
            :to="{ path: '/rankings', query: buildQuery({ type: 'RECOMMEND' }) }"
            :class="{ active: rankingType === 'RECOMMEND' }"
            :aria-current="rankingType === 'RECOMMEND' ? 'page' : undefined"
          >
            推荐榜
          </NuxtLink>
          <NuxtLink
            :to="{ path: '/rankings', query: buildQuery({ type: 'IMPROVE' }) }"
            :class="{ active: rankingType === 'IMPROVE' }"
            :aria-current="rankingType === 'IMPROVE' ? 'page' : undefined"
          >
            待改善名单
          </NuxtLink>
        </div>
      </div>
    </div>

    <div v-if="pending" class="skeleton-list">
      <div class="skeleton skeleton-card" style="height: 80px" />
      <div class="skeleton skeleton-card" style="height: 300px" />
    </div>

    <div v-else-if="error" class="empty-state">
      <h2>榜单暂时无法加载</h2>
      <p>请稍后再试，或换一个城市与行业查看。</p>
      <NuxtLink class="button" to="/">返回首页</NuxtLink>
    </div>

    <template v-else>
      <div class="stat-strip">
        <div class="stat">
          <span class="mono">算法版本</span>
          <div class="stat-value">{{ data?.algorithmVersion || '—' }}</div>
        </div>
        <div class="stat">
          <span class="mono">计算时间</span>
          <div class="stat-value">{{ formatDateTime(data?.computedAt) }}</div>
        </div>
        <div class="stat">
          <span class="mono">样本门槛</span>
          <div class="stat-value">≥ {{ minSample }} 条</div>
        </div>
        <div class="stat">
          <span class="mono">入榜公司</span>
          <div class="stat-value">{{ items.length }} 家</div>
        </div>
        <div v-if="rankingType === 'RECOMMEND'" class="stat">
          <span class="mono">分位阈值</span>
          <div class="stat-value">{{ formatScore(threshold) }} · 前 {{ formatPercent(recommendPercentile, 0) }}</div>
        </div>
        <div v-else class="stat">
          <span class="mono">入榜阈值</span>
          <div class="stat-value">
            低分集中度 ≥ {{ formatPercent(improveParams.lowScoreConcentrationThreshold, 0) }} 或 争议率 ≥
            {{ formatPercent(improveParams.disputeRateThreshold, 0) }}
          </div>
        </div>
      </div>

      <div v-if="rankingType === 'IMPROVE'" class="metric-note" style="margin-bottom: 18px">
        待改善名单仅呈现可核对指标（样本数、低分集中度、争议率与排序分），不代表定性评价。
        排序分 = 低分集中度 × {{ improveParams.lowScoreWeight ?? 0.6 }} + 争议率 ×
        {{ improveParams.disputeWeight ?? 0.4 }}，同分按样本数降序。
      </div>

      <div class="table-wrap">
        <table class="data-table">
          <caption class="sr-only">
            {{ city }}{{ industry }}{{ typeLabel }}，仅展示可核对指标
          </caption>
          <thead>
            <tr>
              <th class="num" scope="col">排名</th>
              <th scope="col">公司</th>
              <th class="num" scope="col">样本数</th>
              <th class="num" scope="col">低分集中度</th>
              <th class="num" scope="col">争议率</th>
              <th class="num" scope="col">加权分</th>
              <th class="num" scope="col">排序分</th>
            </tr>
          </thead>
          <tbody v-if="items.length">
            <tr v-for="item in items" :key="item.uscc">
              <td class="num">{{ String(item.rank).padStart(2, '0') }}</td>
              <td>
                <NuxtLink :to="companyPath(item.uscc)">{{ item.name }}</NuxtLink>
                <div class="company-meta" style="margin-top: 2px">USCC {{ item.uscc }}</div>
              </td>
              <td class="num">{{ item.sampleCount }}</td>
              <td class="num">{{ formatPercent(item.lowScoreConcentration) }}</td>
              <td class="num">{{ formatPercent(item.disputeRate) }}</td>
              <td class="num">{{ formatScore(item.weightedScore, 2) }}</td>
              <td class="num">{{ formatScore(item.score, 2) }}</td>
            </tr>
          </tbody>
          <tbody v-else>
            <tr>
              <td colspan="7">
                <div class="empty-state" style="border: 0; background: transparent">
                  <h2>暂无入榜公司</h2>
                  <p>
                    当前城市与行业没有满足样本门槛与指标条件的公司。
                    可以换一个城市或行业查看，或先浏览公司搜索。
                  </p>
                  <NuxtLink class="button ghost" to="/search">去搜索公司</NuxtLink>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <section v-if="metricDefinitions" class="section">
        <div class="section-head">
          <h2>指标口径</h2>
          <span class="mono">METRIC DEFINITIONS · 随榜单返回</span>
        </div>
        <ul class="formula-list">
          <li>
            <span class="mono">公开样本集合</span>
            {{ metricDefinitions.sampleSet }}
          </li>
          <li>
            <span class="mono">加权分</span>
            {{ metricDefinitions.weightedScore }}
          </li>
          <li>
            <span class="mono">低分集中度</span>
            {{ metricDefinitions.lowScoreConcentration }}
          </li>
          <li>
            <span class="mono">争议率</span>
            {{ metricDefinitions.disputeRate }}
          </li>
        </ul>
      </section>

      <p class="metric-note">
        推荐榜只纳入样本 ≥ {{ minSample }} 条的公司；争议中与已折叠评价不计入推荐榜。
        榜单参数与公式文本随接口返回，算法版本用于历史回溯。
      </p>
    </template>
  </main>
</template>
