<script setup lang="ts">
import type { ReportSubmitView } from '~/types/api'

useSeoMeta({ title: '举报点评或公司 · 外包点评', description: '按结构化原因码举报违规点评或公司档案。' })

const api = useApi()
const auth = useAuth()
const route = useRoute()

const targetType = ref<'REVIEW' | 'COMPANY'>('REVIEW')
const targetId = ref('')
const reasonCode = ref('FALSE_INFO')
const detail = ref('')
const submitting = ref(false)
const errorMessage = ref('')
const result = ref<ReportSubmitView | null>(null)

const reasons = [
  { code: 'FALSE_INFO', label: '虚构违法事实' },
  { code: 'ABUSIVE', label: '人身攻击' },
  { code: 'ADVERTISING', label: '广告引流' },
  { code: 'PRIVACY', label: '泄露隐私' },
  { code: 'SPAM', label: '垃圾信息' },
  { code: 'OTHER', label: '其他违规情形' }
]

function singleQuery(value: unknown): string {
  if (Array.isArray(value)) return String(value[0] ?? '')
  return value === null || value === undefined ? '' : String(value)
}

onMounted(async () => {
  await auth.init()

  const target = singleQuery(route.query.target)
  if (target.includes(':')) {
    const [type, id] = target.split(':')
    if (type.toLowerCase() === 'company') {
      targetType.value = 'COMPANY'
      targetId.value = id
    } else if (type.toLowerCase() === 'review') {
      targetType.value = 'REVIEW'
      targetId.value = id
    }
  }
  const queryType = singleQuery(route.query.targetType).toUpperCase()
  if (queryType === 'REVIEW' || queryType === 'COMPANY') {
    targetType.value = queryType
  }
  if (!targetId.value) {
    targetId.value = singleQuery(route.query.targetId).trim()
  }
})

function loginLink() {
  return {
    path: '/login',
    query: { redirect: route.fullPath }
  }
}

async function submit() {
  errorMessage.value = ''
  result.value = null
  if (!auth.isLoggedIn.value) {
    await navigateTo(loginLink())
    return
  }
  if (!targetId.value.trim()) {
    errorMessage.value = '请填写举报对象的编号'
    return
  }
  if (targetType.value === 'REVIEW' && !/^[0-9a-fA-F-]{36}$/.test(targetId.value.trim())) {
    errorMessage.value = '点评编号应为 36 位 UUID'
    return
  }
  if (targetType.value === 'COMPANY' && !/^[0-9A-Za-z]{18}$/.test(targetId.value.trim())) {
    errorMessage.value = '公司举报请填写 18 位统一社会信用代码'
    return
  }
  if (!reasonCode.value) {
    errorMessage.value = '请选择举报原因'
    return
  }
  if (detail.value.trim().length > 500) {
    errorMessage.value = '举报说明不能超过 500 字'
    return
  }

  submitting.value = true
  try {
    result.value = await api.post<ReportSubmitView>('/api/reports', {
      targetType: targetType.value,
      targetId: targetId.value.trim(),
      reasonCode: reasonCode.value,
      detail: detail.value.trim() || null
    })
  } catch (error) {
    const apiError = error as { message?: string; details?: { availableAt?: string } }
    let message = apiError.message || '举报提交失败，请稍后再试'
    if (apiError.details?.availableAt) {
      message += ` 可再次举报时间：${formatDateTime(apiError.details.availableAt)}`
    }
    errorMessage.value = message
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="wrap page">
    <div class="mono">REPORT · 用户举报</div>
    <h1>举报点评或公司</h1>
    <p class="meta">
      举报理由采用结构化原因码加说明。平台不直接修改点评状态，达到阈值后自动转入人工审核。
    </p>

    <div v-if="!auth.isLoggedIn.value && auth.ready.value" class="note warn">
      举报需要登录后提交。请先
      <NuxtLink :to="loginLink()">登录</NuxtLink>
      再填写并提交；登录后会自动回到本页。
    </div>

    <section v-if="result" class="paper-card form-card success-card">
      <div class="mono">REPORT SUBMITTED · 已提交</div>
      <h2>举报已提交</h2>
      <p>
        工单号：<b class="mono-data">{{ result.reportId }}</b><br />
        当前状态：{{ result.status === 'PENDING' ? '待处理' : result.status }}
      </p>
      <p v-if="result.thresholdReached">
        该对象已被多个账号举报，系统已自动转入人工审核队列。
      </p>
      <p v-else class="hint">
        后台会在核实后处置；有效举报发放 20 贡献值，同一举报只发放一次。
      </p>
      <div class="button-row">
        <button type="button" class="button" @click="result = null">继续举报</button>
        <NuxtLink class="button ghost" to="/report-policy">查看举报与申诉说明</NuxtLink>
      </div>
    </section>

    <section v-else class="paper-card form-card">
      <div class="section-head">
        <h2>举报信息</h2>
        <span class="mono">24H · 同一对象限 1 次</span>
      </div>

      <form class="form-grid" @submit.prevent="submit">
        <div class="field form-span">
          <label class="field-label">举报对象类型</label>
          <div class="radio-row">
            <label class="check-line">
              <input v-model="targetType" type="radio" value="REVIEW" />
              <span>点评</span>
            </label>
            <label class="check-line">
              <input v-model="targetType" type="radio" value="COMPANY" />
              <span>公司档案</span>
            </label>
          </div>
        </div>

        <div class="field form-span">
          <label for="report-target">
            {{ targetType === 'REVIEW' ? '点评 ID（UUID）' : '公司统一社会信用代码（USCC）' }}
          </label>
          <input
            id="report-target"
            v-model="targetId"
            class="input"
            type="text"
            autocomplete="off"
            :placeholder="targetType === 'REVIEW' ? '点评 UUID' : '18 位 USCC'"
          />
        </div>

        <div class="field form-span">
          <label for="report-reason">举报原因</label>
          <select id="report-reason" v-model="reasonCode" class="select">
            <option v-for="reason in reasons" :key="reason.code" :value="reason.code">
              {{ reason.label }}
            </option>
          </select>
        </div>

        <div class="field form-span">
          <label for="report-detail">补充说明（选填，≤500 字）</label>
          <textarea
            id="report-detail"
            v-model="detail"
            class="input textarea"
            rows="5"
            maxlength="500"
            placeholder="请描述具体问题位置与事实，不要填写他人身份证号、手机号等个人信息。"
          />
          <span class="hint">{{ detail.length }} / 500 字</span>
        </div>

        <div class="field form-actions">
          <button class="button primary" type="submit" :disabled="submitting">
            {{ submitting ? '提交中…' : auth.isLoggedIn.value ? '提交举报' : '登录后提交举报' }}
          </button>
        </div>
      </form>

      <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>

      <div class="note">
        同一账号对同一对象 24 小时内只能举报 1 次；重复提交会返回可再次举报的时间。
        同一对象被 5 个不同账号举报后会自动进入人工审核队列。
      </div>
    </section>
  </main>
</template>
