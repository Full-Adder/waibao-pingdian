<script setup lang="ts">
import type { RankingPage, ReviewItem } from '~/types/api'

const DEFAULT_CITY = '北京'
const DEFAULT_INDUSTRY = 'IT人力外包'

const api = useApi()

const { data, pending } = await useAsyncData('home-page', async () => {
  const safe = async <T>(loader: () => Promise<T>): Promise<T | null> => {
    try {
      return await loader()
    } catch {
      return null
    }
  }

  const [latest, recommend, improve] = await Promise.all([
    safe(() => api.get<ReviewItem[]>('/api/reviews/latest', { limit: 6 })),
    safe(() =>
      api.get<RankingPage>('/api/rankings', {
        city: DEFAULT_CITY,
        industry: DEFAULT_INDUSTRY,
        type: 'RECOMMEND',
        limit: 5
      })
    ),
    safe(() =>
      api.get<RankingPage>('/api/rankings', {
        city: DEFAULT_CITY,
        industry: DEFAULT_INDUSTRY,
        type: 'IMPROVE',
        limit: 5
      })
    )
  ])

  return { latest: latest ?? [], recommend, improve }
})

const latestReviews = computed(() => data.value?.latest ?? [])
const recommendItems = computed(() => (data.value?.recommend?.items ?? []).slice(0, 5))
const improveItems = computed(() => (data.value?.improve?.items ?? []).slice(0, 5))

const rankingsQuery = { city: DEFAULT_CITY, industry: DEFAULT_INDUSTRY }

useSeoMeta({
  title: '外包点评 · 外包公司真实口碑查询与评价',
  description:
    '按公司名称、简称、城市与行业检索外包公司档案。综合分仅在有效评价满 3 条时展示，评价不足时显示评价条数与样本说明。',
  ogTitle: '外包点评 · 外包公司真实口碑查询与评价',
  ogDescription: '搜得到公司，看得到经在职佐证核验的真实评价。'
})
</script>

<template>
  <main>
    <section class="wrap home-hero">
      <div class="eyebrow">PUBLIC ARCHIVE · 可查的公司口碑</div>
      <h1>外包公司怎么样，<br />先看可核对的口碑</h1>
      <p class="lead">
        以外包公司的统一社会信用代码为锚，把经在职佐证核验的工作经历聚合到公司档案上。
        搜得到公司，也看得到评价是否来自真实在职经历。
      </p>

      <form class="search-hero" action="/search" method="get" role="search">
        <label class="sr-only" for="home-keyword">按公司名称或简称搜索</label>
        <input
          id="home-keyword"
          class="input"
          name="keyword"
          type="search"
          placeholder="输入公司名称或简称，例如「极光软件」"
          autocomplete="off"
        />
        <button class="button primary" type="submit">搜索公司</button>
      </form>

      <div class="button-row">
        <NuxtLink class="button ghost" to="/rankings">查看双榜</NuxtLink>
        <NuxtLink class="button ghost" to="/report-policy">举报与申诉说明</NuxtLink>
      </div>
    </section>

    <section class="wrap section">
      <div class="quick-nav">
        <div class="paper-card nav-group">
          <div class="mono">01 · CITY / 按城市</div>
          <div class="chip-set">
            <NuxtLink
              v-for="city in CITIES"
              :key="city"
              class="chip"
              :to="{ path: '/search', query: { city } }"
            >
              {{ city }}
            </NuxtLink>
          </div>
        </div>
        <div class="paper-card nav-group">
          <div class="mono">02 · INDUSTRY / 按行业</div>
          <div class="chip-set">
            <NuxtLink
              v-for="industry in INDUSTRIES"
              :key="industry"
              class="chip"
              :to="{ path: '/search', query: { industry } }"
            >
              {{ industry }}
            </NuxtLink>
          </div>
        </div>
      </div>
    </section>

    <section class="wrap section">
      <div class="section-head">
        <h2>双榜摘要</h2>
        <span class="mono">{{ DEFAULT_CITY }} · {{ DEFAULT_INDUSTRY }} · 每榜前 5</span>
      </div>

      <div v-if="pending" class="skeleton-list">
        <div class="skeleton skeleton-card" />
        <div class="skeleton skeleton-card" />
      </div>

      <div v-else class="two-up">
        <div class="paper-card rank-card">
          <div class="rank-card-head">
            <h3>推荐榜</h3>
            <span class="mono">RECOMMEND</span>
          </div>
          <ol v-if="recommendItems.length" class="rank-list">
            <li v-for="item in recommendItems" :key="item.uscc" class="rank-item">
              <span class="rank-no">{{ String(item.rank).padStart(2, '0') }}</span>
              <div class="rank-main">
                <NuxtLink class="rank-name" :to="companyPath(item.uscc)">{{ item.name }}</NuxtLink>
                <div class="rank-metrics">
                  <span>样本 <b>{{ item.sampleCount }}</b> 条</span>
                  <span>加权分 <b>{{ formatScore(item.weightedScore) }}</b></span>
                </div>
              </div>
            </li>
          </ol>
          <div v-else class="empty-state">
            <h2>暂无推荐榜数据</h2>
            <p>当前城市与行业还没有满足样本门槛的公司，先去看看其他城市或行业。</p>
            <NuxtLink class="button ghost" :to="{ path: '/rankings', query: rankingsQuery }">查看完整榜单</NuxtLink>
          </div>
          <NuxtLink class="rank-link" :to="{ path: '/rankings', query: rankingsQuery }">查看完整推荐榜 →</NuxtLink>
        </div>

        <div class="paper-card rank-card">
          <div class="rank-card-head">
            <h3>待改善名单</h3>
            <span class="mono">IMPROVE</span>
          </div>
          <ol v-if="improveItems.length" class="rank-list">
            <li v-for="item in improveItems" :key="item.uscc" class="rank-item">
              <span class="rank-no">{{ String(item.rank).padStart(2, '0') }}</span>
              <div class="rank-main">
                <NuxtLink class="rank-name" :to="companyPath(item.uscc)">{{ item.name }}</NuxtLink>
                <div class="rank-metrics">
                  <span>样本 <b>{{ item.sampleCount }}</b> 条</span>
                  <span>低分集中度 <b>{{ formatPercent(item.lowScoreConcentration) }}</b></span>
                  <span>争议率 <b>{{ formatPercent(item.disputeRate) }}</b></span>
                  <span>排序分 <b>{{ formatScore(item.score) }}</b></span>
                </div>
              </div>
            </li>
          </ol>
          <div v-else class="empty-state">
            <h2>暂无待改善名单</h2>
            <p>当前城市与行业没有同时满足样本与指标门槛的公司。</p>
            <NuxtLink class="button ghost" :to="{ path: '/rankings', query: { ...rankingsQuery, type: 'IMPROVE' } }">
              查看完整名单
            </NuxtLink>
          </div>
          <p class="metric-note">
            仅呈现可核对指标：样本数、低分集中度、争议率与排序分，不代表定性评价。
          </p>
          <NuxtLink
            class="rank-link"
            :to="{ path: '/rankings', query: { ...rankingsQuery, type: 'IMPROVE' } }"
          >
            查看完整待改善名单 →
          </NuxtLink>
        </div>
      </div>
    </section>

    <section class="wrap section">
      <div class="section-head">
        <h2>最新评价</h2>
        <span class="mono">LATEST · 已发布</span>
      </div>

      <div v-if="pending" class="skeleton-list">
        <div class="skeleton skeleton-card" />
        <div class="skeleton skeleton-card" />
        <div class="skeleton skeleton-card" />
      </div>

      <div v-else-if="latestReviews.length" class="review-grid">
        <ReviewCard
          v-for="review in latestReviews"
          :key="review.id"
          :review="review"
          compact
          show-company
        />
      </div>

      <div v-else class="empty-state">
        <h2>还没有已发布评价</h2>
        <p>如果你在某家外包公司工作过，可以搜索这家公司，进入档案后写第一条评价。</p>
        <NuxtLink class="button primary" to="/search">去搜索公司</NuxtLink>
      </div>
    </section>
  </main>
</template>
