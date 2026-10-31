<script setup lang="ts">
import type { DimensionScore } from '~/types/api'

const props = defineProps<{
  dimensions: DimensionScore[]
}>()

const available = computed(() =>
  DIMENSION_ORDER.map((code) => props.dimensions.find((item) => item.code === code) || null)
    .filter((item): item is DimensionScore => Boolean(item && item.enoughSample && item.average !== null))
)

const size = 300
const center = 150
const radius = 100

const axes = computed(() => {
  const count = available.value.length
  return available.value.map((dimension, index) => {
    const angle = (Math.PI * 2 * index) / count - Math.PI / 2
    const value = Math.max(0, Math.min(5, Number(dimension.average ?? 0)))
    const ratio = value / 5
    return {
      dimension,
      labelX: center + Math.cos(angle) * (radius + 26),
      labelY: center + Math.sin(angle) * (radius + 26),
      anchor: Math.cos(angle) > 0.25 ? 'start' : Math.cos(angle) < -0.25 ? 'end' : 'middle',
      x: center + Math.cos(angle) * radius,
      y: center + Math.sin(angle) * radius,
      pointX: center + Math.cos(angle) * radius * ratio,
      pointY: center + Math.sin(angle) * radius * ratio
    }
  })
})

const polygon = computed(() =>
  axes.value.map((axis) => `${axis.pointX},${axis.pointY}`).join(' ')
)

const rings = [0.2, 0.4, 0.6, 0.8, 1]
</script>

<template>
  <svg
    v-if="available.length >= 3"
    class="radar-svg"
    :viewBox="`0 0 ${size} ${size}`"
    role="img"
    :aria-label="`六个维度雷达图，${available.length} 个维度有足够样本`"
  >
    <polygon
      v-for="ring in rings"
      :key="`ring-${ring}`"
      :points="
        Array.from({ length: available.length }, (_, index) => {
          const angle = (Math.PI * 2 * index) / available.length - Math.PI / 2
          return `${center + Math.cos(angle) * radius * ring},${center + Math.sin(angle) * radius * ring}`
        }).join(' ')
      "
      fill="none"
      stroke="#dcd7cb"
      stroke-width="1"
    />
    <line
      v-for="axis in axes"
      :key="`line-${axis.dimension.code}`"
      :x1="center"
      :y1="center"
      :x2="axis.x"
      :y2="axis.y"
      stroke="#dcd7cb"
      stroke-width="1"
    />
    <polygon :points="polygon" fill="rgba(14, 78, 74, 0.2)" stroke="#0e4e4a" stroke-width="1.6" />
    <circle
      v-for="axis in axes"
      :key="`point-${axis.dimension.code}`"
      :cx="axis.pointX"
      :cy="axis.pointY"
      r="2.6"
      fill="#0e4e4a"
    />
    <text
      v-for="axis in axes"
      :key="`label-${axis.dimension.code}`"
      :x="axis.labelX"
      :y="axis.labelY"
      :text-anchor="axis.anchor"
      fill="#6e7378"
      font-size="11"
      font-family="system-ui, sans-serif"
    >
      {{ dimensionLabel(axis.dimension.code) }} {{ formatScore(axis.dimension.average) }}
    </text>
  </svg>
</template>
