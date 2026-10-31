<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    modelValue: number | null | undefined
    label: string
    allowClear?: boolean
  }>(),
  { allowClear: false }
)

const emit = defineEmits<{ 'update:modelValue': [value: number | null] }>()

const value = computed(() => Number(props.modelValue ?? 0))

function select(score: number) {
  emit('update:modelValue', score)
}

function clear() {
  emit('update:modelValue', null)
}
</script>

<template>
  <div class="star-input" role="radiogroup" :aria-label="label">
    <button
      v-for="score in 5"
      :key="score"
      type="button"
      role="radio"
      class="star-input-btn"
      :aria-checked="value === score"
      :aria-label="`${label} ${score} 星`"
      :class="{ active: value >= score }"
      @click="select(score)"
    >
      ★
    </button>
    <button
      v-if="allowClear"
      type="button"
      class="star-clear"
      aria-label="清除该项评分"
      @click="clear"
    >
      清除
    </button>
    <span class="star-input-value" aria-live="polite">
      {{ value ? `${value} 星` : '未评分' }}
    </span>
  </div>
</template>
