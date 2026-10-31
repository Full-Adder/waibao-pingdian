<script setup lang="ts">
import type { NuxtError } from '#app'

const props = defineProps<{ error: NuxtError }>()

const statusCode = computed(() => props.error?.statusCode || 500)
const message = computed(() => {
  if (statusCode.value === 404) {
    return '没有找到这个页面。公司档案可能已合并，也可能链接有误。'
  }
  return '页面暂时无法打开，请稍后再试。'
})

useSeoMeta({
  title: () => `${statusCode.value} · 外包点评`,
  robots: 'noindex'
})
</script>

<template>
  <NuxtLayout>
    <main class="wrap page error-page">
      <div class="mono">ERROR · {{ statusCode }}</div>
      <h1>{{ statusCode === 404 ? '页面不存在' : '服务暂时不可用' }}</h1>
      <p class="lead">{{ message }}</p>
      <div class="error-actions">
        <button type="button" class="button primary" @click="clearError({ redirect: '/' })">
          返回首页
        </button>
        <NuxtLink to="/search" class="button ghost">去搜索公司</NuxtLink>
      </div>
    </main>
  </NuxtLayout>
</template>
