<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    score: number | null | undefined
    showNumber?: boolean
  }>(),
  { showNumber: true }
)

const filled = computed(() => {
  const value = Number(props.score ?? 0)
  if (!Number.isFinite(value)) return 0
  return Math.max(0, Math.min(5, Math.round(value)))
})

const label = computed(() => {
  if (props.score === null || props.score === undefined) return '暂无评分'
  return `${formatScore(props.score)} 分（满分 5 分）`
})
</script>

<template>
  <span class="stars" role="img" :aria-label="label">
    <span aria-hidden="true">{{ '★'.repeat(filled) + '☆'.repeat(5 - filled) }}</span>
    <span v-if="showNumber && score !== null && score !== undefined" class="stars-num">
      {{ formatScore(score) }}
    </span>
  </span>
</template>
