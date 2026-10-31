<script setup lang="ts">
import type { CompanyReportResult } from '~/types/api'

useSeoMeta({ title: '上报公司 · 外包点评', robots: 'noindex' })

const api = useApi()
const auth = useRequireAuth()

const name = ref('')
const city = ref('')
const industry = ref('')
const submitting = ref(false)
const errorMessage = ref('')
const result = ref<CompanyReportResult | null>(null)

async function submit() {
  errorMessage.value = ''
  result.value = null
  if (!name.value.trim()) {
    errorMessage.value = '请填写公司名称'
    return
  }
  if (name.value.trim().length > 255) {
    errorMessage.value = '公司名称最多 255 字'
    return
  }

  submitting.value = true
  try {
    result.value = await api.post<CompanyReportResult>('/api/companies/report', {
      name: name.value.trim(),
      city: city.value.trim() || null,
      industry: industry.value.trim() || null
    })
  } catch (error) {
    errorMessage.value = (error as { message?: string }).message || '公司上报失败，请稍后再试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="wrap page">
    <div class="mono">REPORT COMPANY · 上报公司</div>
    <h1>上报公司档案</h1>
    <p class="meta">
      上报会先按名称、简称与别名匹配工商库。命中直接打开已有档案；未命中只进入待核验候选池，不会自由建档。
    </p>

    <div v-if="!auth.ready.value" class="empty-state">
      <h2>正在校验登录状态</h2>
      <p>请稍候，正在读取本机登录凭证。</p>
    </div>

    <div v-else-if="!auth.isLoggedIn.value" class="empty-state">
      <h2>登录后上报公司</h2>
      <p>公司上报需要登录，避免重复与恶意提交。</p>
      <NuxtLink class="button primary" to="/login?redirect=/report-company">去登录</NuxtLink>
    </div>

    <template v-else>
      <section v-if="result" class="paper-card form-card success-card">
        <div class="mono">{{ result.matched ? 'MATCHED · 已匹配' : 'PENDING · 待核验' }}</div>
        <h2>{{ result.matched ? '已找到匹配的公司档案' : '公司已进入待核验池' }}</h2>

        <template v-if="result.matched && result.company">
          <p>
            公司：<b>{{ result.company.name }}</b><br />
            USCC：<b class="mono-data">{{ result.company.uscc }}</b>
            <template v-if="result.company.city"> · {{ result.company.city }}</template>
            <template v-if="result.company.industry"> · {{ result.company.industry }}</template>
          </p>
          <p class="hint">命中工商库不会新建重复档案，可直接进入公司页查看或发布评价。</p>
          <div class="button-row">
            <NuxtLink class="button primary" :to="companyPath(result.company.uscc)">打开公司档案</NuxtLink>
            <button type="button" class="button ghost" @click="result = null">再上报一家</button>
          </div>
        </template>

        <template v-else>
          <p>
            未在工商库中找到匹配公司，已生成待核验候选
            <b v-if="result.candidateId" class="mono-data">（{{ result.candidateId }}）</b>。
          </p>
          <p class="hint">
            候选不会立即创建公司档案，也不会生成重复 USCC。管理员核验、补录或匹配后才能进入公司库。
          </p>
          <div class="button-row">
            <button type="button" class="button" @click="result = null">再上报一家</button>
            <NuxtLink class="button ghost" to="/search">返回搜索</NuxtLink>
          </div>
        </template>
      </section>

      <section v-else class="paper-card form-card">
        <div class="section-head">
          <h2>公司信息</h2>
          <span class="mono">MATCH FIRST · 先匹配后建档</span>
        </div>

        <form class="form-grid" @submit.prevent="submit">
          <div class="field form-span">
            <label for="report-company-name">公司名称</label>
            <input
              id="report-company-name"
              v-model="name"
              class="input"
              type="text"
              maxlength="255"
              autocomplete="organization"
              placeholder="工商登记全称或常用简称"
            />
          </div>
          <div class="field">
            <label for="report-company-city">城市（选填）</label>
            <input
              id="report-company-city"
              v-model="city"
              class="input"
              type="text"
              maxlength="64"
              autocomplete="address-level2"
              placeholder="例如 北京"
            />
          </div>
          <div class="field">
            <label for="report-company-industry">行业（选填）</label>
            <input
              id="report-company-industry"
              v-model="industry"
              class="input"
              type="text"
              maxlength="64"
              placeholder="例如 IT人力外包"
            />
          </div>
          <div class="field form-actions">
            <button class="button primary" type="submit" :disabled="submitting">
              {{ submitting ? '提交中…' : '上报公司' }}
            </button>
          </div>
        </form>

        <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>

        <div class="note">
          上报只用于补充公司档案线索。已经存在的公司会直接命中，不会生成重复档案；
          平台的 USCC 主键由工商数据维护。
        </div>
      </section>
    </template>
  </main>
</template>
