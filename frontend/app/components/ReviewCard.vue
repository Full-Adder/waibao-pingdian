<script setup lang="ts">
import type { EnterpriseResponse, ReviewItem } from '~/types/api'

const props = withDefaults(
  defineProps<{
    review: ReviewItem
    compact?: boolean
    showCompany?: boolean
    enterpriseResponse?: EnterpriseResponse | null
  }>(),
  { compact: false, showCompany: false, enterpriseResponse: null }
)

const response = computed(() => props.enterpriseResponse ?? props.review.enterpriseResponse)
const foldedOpen = ref(false)
const isFolded = computed(() => props.review.status === 'FOLDED' || props.review.collapsed)
const isDisputed = computed(() => props.review.status === 'DISPUTED' || props.review.disputed)
const alias = computed(() => props.review.identityAlias || '匿名用户')
const tagList = computed(() => (props.review.tags || []).filter(Boolean))
const evidenceCount = computed(() => (props.review.evidence || []).length)
const collapseNote = computed(() =>
  props.review.collapseNote || (props.review.everDisputed ? '争议未决，双方举证不足，评价已折叠' : '该评价证据不足，已折叠')
)
</script>

<template>
  <article class="review-card" :class="{ compact }">
    <header class="review-head">
      <div class="review-author">
        <span class="alias">{{ alias }}</span>
        <span v-if="review.verificationBadge" class="badge">
          {{ badgeLabel(review.verificationBadge) }}
        </span>
        <span v-if="isDisputed" class="status-tag">争议中</span>
        <span v-if="isFolded" class="status-tag folded">已折叠</span>
      </div>
      <time v-if="review.publishedAt" class="review-date" :datetime="review.publishedAt">
        {{ formatDate(review.publishedAt) }}
      </time>
    </header>

    <div class="review-sub">
      <StarRating :score="review.totalScore" />
      <NuxtLink v-if="showCompany && review.companyName" class="company-link" :to="companyPath(review.uscc)">
        {{ review.companyName }}
      </NuxtLink>
      <span v-if="compact && review.city">{{ review.city }}</span>
    </div>

    <details v-if="isFolded" class="folded-details" @toggle="foldedOpen = $event.target.open">
      <summary>{{ collapseNote }}（展开查看）</summary>
      <p v-if="foldedOpen" class="review-content">{{ review.content }}</p>
    </details>
    <p v-else class="review-content">{{ review.content }}</p>

    <div v-if="!compact && tagList.length" class="review-tags">
      <span v-for="tag in tagList" :key="tag" class="review-tag">{{ tag }}</span>
    </div>

    <div v-if="!compact" class="dimension-inline">
      <span v-for="dimension in review.dimensions" :key="dimension.code">
        {{ dimensionLabel(dimension.code) }} <b>{{ dimension.score }}</b>
      </span>
    </div>

    <div class="review-tags">
      <span v-if="review.periodStart || review.periodEnd" class="review-tag">
        {{ reviewPeriod(review.periodStart, review.periodEnd) }}
      </span>
      <span v-if="!compact && review.city" class="review-tag">{{ review.city }}</span>
      <span v-if="evidenceCount" class="review-tag">附在职佐证 {{ evidenceCount }} 项</span>
    </div>

    <div v-if="response" class="enterprise-response">
      <div class="resp-head">
        <span>{{ response.label || '企业回应' }}</span>
        <time v-if="response.createdAt" :datetime="response.createdAt">{{ formatDate(response.createdAt) }}</time>
      </div>
      <p>{{ response.content }}</p>
    </div>

    <div class="review-card-links">
      <NuxtLink :to="{ path: '/report', query: { target: `review:${review.id}` } }">
        举报这条评价
      </NuxtLink>
    </div>
  </article>
</template>
