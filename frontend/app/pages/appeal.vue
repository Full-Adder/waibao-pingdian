<script setup lang="ts">
import type { ComplaintCreatedResult } from '~/types/api'

useSeoMeta({ title: '企业申诉 · 外包点评', description: '外包点评企业申诉公开表单：提交营业执照、公章申诉函与 USCC。' })

const api = useApi()
const route = useRoute()

const uscc = ref('')
const reviewId = ref('')
const contact = ref('')
const licenseFile = ref<File | null>(null)
const letterFile = ref<File | null>(null)
const amountFlag = ref(false)
const safetyFlag = ref(false)
const submitting = ref(false)
const errorMessage = ref('')
const result = ref<ComplaintCreatedResult | null>(null)

const maxFileSize = 10 * 1024 * 1024

function singleQuery(value: unknown): string {
  if (Array.isArray(value)) return String(value[0] ?? '')
  return value === null || value === undefined ? '' : String(value)
}

function onLicense(event: Event) {
  const input = event.target as HTMLInputElement
  licenseFile.value = input.files?.[0] ?? null
}

function onLetter(event: Event) {
  const input = event.target as HTMLInputElement
  letterFile.value = input.files?.[0] ?? null
}

onMounted(() => {
  uscc.value = singleQuery(route.query.uscc).trim().toUpperCase()
  reviewId.value = singleQuery(route.query.reviewId || route.query.target).trim()
})

async function submit() {
  errorMessage.value = ''
  result.value = null
  const code = uscc.value.trim().toUpperCase()
  if (!/^[0-9A-Z]{18}$/.test(code)) {
    errorMessage.value = '统一社会信用代码必须为 18 位字母或数字'
    return
  }
  if (!/^[0-9a-fA-F-]{36}$/.test(reviewId.value.trim())) {
    errorMessage.value = '请填写被投诉点评的 32 位 UUID 编号'
    return
  }
  if (!/^1[3-9]\d{9}$/.test(contact.value.trim())) {
    errorMessage.value = '企业联系人手机号格式不正确'
    return
  }
  if (!licenseFile.value) {
    errorMessage.value = '请上传营业执照'
    return
  }
  if (!letterFile.value) {
    errorMessage.value = '请上传加盖公章的申诉函'
    return
  }
  if (licenseFile.value.size > maxFileSize || letterFile.value.size > maxFileSize) {
    errorMessage.value = '单个文件不能超过 10MB'
    return
  }

  const form = new FormData()
  form.append('uscc', code)
  form.append('reviewId', reviewId.value.trim())
  form.append('contact', contact.value.trim())
  form.append('licenseFile', licenseFile.value)
  form.append('letterFile', letterFile.value)
  form.append('amountFlag', String(amountFlag.value))
  form.append('safetyFlag', String(safetyFlag.value))

  submitting.value = true
  try {
    result.value = await api.upload<ComplaintCreatedResult>('/api/appeals', form)
  } catch (error) {
    errorMessage.value = (error as { message?: string }).message || '申诉提交失败，请稍后再试'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="wrap page">
    <div class="mono">ENTERPRISE APPEAL · 企业申诉</div>
    <h1>企业申诉</h1>
    <p class="meta">
      企业认为某条评价存在不实内容时，可通过公开表单提交主体材料。平台核对 USCC 与公司档案一致后才进入处理流程。
    </p>

    <div class="note warn">
      未通过主体核验不会改变点评状态，也不会触发降权；材料不齐会一次性告知补充。
      同一条点评只接受一次投诉，重复投诉将被拒绝并记录异常行为。
    </div>

    <section v-if="result" class="paper-card form-card success-card">
      <div class="mono">APPEAL SUBMITTED · 已受理</div>
      <h2>申诉材料已提交</h2>
      <p>
        工单号：<b class="mono-data">{{ result.complaintId }}</b><br />
        当前状态：<b>{{ complaintStatusLabel(result.status) }}</b>
      </p>
      <p>
        平台将在核对 USCC 与公司档案、营业执照、公章申诉函后决定是否进入争议举证。
        主体核验通过前，点评状态保持不变。
      </p>
      <div class="button-row">
        <NuxtLink class="button primary" to="/report-policy#enterprise">查看企业申诉说明</NuxtLink>
        <NuxtLink class="button ghost" to="/">返回首页</NuxtLink>
      </div>
    </section>

    <template v-else>
      <section class="paper-card form-card">
        <div class="section-head">
          <h2>提交材料</h2>
          <span class="mono">LICENSE · LETTER · CONTACT</span>
        </div>

        <form class="form-grid" @submit.prevent="submit">
          <div class="field">
            <label for="appeal-uscc">企业统一社会信用代码（USCC）</label>
            <input
              id="appeal-uscc"
              v-model="uscc"
              class="input"
              type="text"
              maxlength="18"
              autocomplete="off"
              placeholder="18 位 USCC，须与公司档案一致"
            />
          </div>
          <div class="field">
            <label for="appeal-review">被投诉点评 ID</label>
            <input
              id="appeal-review"
              v-model="reviewId"
              class="input"
              type="text"
              autocomplete="off"
              placeholder="点评 UUID（可在点评详情链接中找到）"
            />
          </div>
          <div class="field">
            <label for="appeal-contact">企业联系人手机号</label>
            <input
              id="appeal-contact"
              v-model="contact"
              class="input"
              type="tel"
              inputmode="numeric"
              maxlength="11"
              autocomplete="tel"
              placeholder="用于主体核验与结果通知"
            />
          </div>

          <div class="field">
            <label for="appeal-license">营业执照</label>
            <input
              id="appeal-license"
              class="input file-input"
              type="file"
              accept="image/*,application/pdf"
              @change="onLicense"
            />
            <span class="hint">需清晰可辨，并与统一社会信用代码一致。</span>
          </div>

          <div class="field">
            <label for="appeal-letter">加盖公章的申诉函</label>
            <input
              id="appeal-letter"
              class="input file-input"
              type="file"
              accept="image/*,application/pdf"
              @change="onLetter"
            />
            <span class="hint">申诉函需写明目标点评编号、具体异议点与企业联系人。</span>
          </div>

          <div class="field form-span">
            <label class="field-label">强制双人复核标记（如有）</label>
            <label class="check-line">
              <input v-model="amountFlag" type="checkbox" />
              <span>涉及金额 1 万元及以上（人工标注）</span>
            </label>
            <label class="check-line">
              <input v-model="safetyFlag" type="checkbox" />
              <span>涉及人身安全或性骚扰指控</span>
            </label>
          </div>

          <div class="field form-actions">
            <button class="button primary" type="submit" :disabled="submitting">
              {{ submitting ? '提交中…' : '提交企业申诉' }}
            </button>
          </div>
        </form>

        <p v-if="errorMessage" class="form-error" role="alert">{{ errorMessage }}</p>
      </section>

      <div class="note">
        提交前请确认：营业执照、公章申诉函与 USCC 一致；同一评价只接受一次投诉；
        平台不接受任何形式的付费删帖、付费置顶或付费降权请求。
      </div>
    </template>
  </main>
</template>
