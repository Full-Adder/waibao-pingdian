<script setup lang="ts">
import type {
  PageResult,
  RealnameResult,
  VerificationItem
} from '~/types/api'

useSeoMeta({ title: '我的核验 · 外包点评', robots: 'noindex' })

const api = useApi()
const auth = useRequireAuth()

const loading = ref(false)
const loadError = ref('')
const verifications = ref<VerificationItem[]>([])

const name = ref('')
const idNumber = ref('')
const realnameSubmitting = ref(false)
const realnameError = ref('')
const realnameResult = ref<RealnameResult | null>(null)

const proofType = ref('LABOR_CONTRACT')
const companyUscc = ref('')
const periodStart = ref('')
const periodEnd = ref('')
const file = ref<File | null>(null)
const submitError = ref('')
const submitSuccess = ref('')
const submitting = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

const realnameVerified = computed(() => auth.profile.value?.realnameStatus === 'VERIFIED')
const locked = computed(() => {
  const until = realnameResult.value?.lockedUntil
  return Boolean(until && new Date(until).getTime() > Date.now())
})

const approvedItems = computed(() =>
  verifications.value.filter((item) => item.status === 'APPROVED')
)

const latestBadge = computed(() => approvedItems.value[0]?.badge ?? null)

async function loadVerifications() {
  loading.value = true
  loadError.value = ''
  try {
    const result = await api.get<PageResult<VerificationItem>>('/api/verifications/mine', {
      page: 1,
      size: 50
    })
    verifications.value = result?.items ?? []
  } catch (error) {
    loadError.value = (error as { message?: string }).message || '核验记录加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await auth.init()
  if (!auth.isLoggedIn.value) return
  await auth.refreshProfile()
  await loadVerifications()
})

async function submitRealname() {
  realnameError.value = ''
  realnameResult.value = null
  const trimmedName = name.value.trim()
  const trimmedId = idNumber.value.trim()
  if (trimmedName.length < 2 || trimmedName.length > 32) {
    realnameError.value = '请输入 2–32 个字符的姓名'
    return
  }
  if (!/^[0-9]{17}[0-9Xx]$/.test(trimmedId)) {
    realnameError.value = '请输入 18 位身份证号'
    return
  }

  realnameSubmitting.value = true
  try {
    const result = await api.post<RealnameResult>(
      '/api/users/me/realname',
      { name: trimmedName, idNumber: trimmedId },
      { redirectOnAuthError: true }
    )
    realnameResult.value = result
    if (result.success) {
      await auth.refreshProfile()
      submitSuccess.value = '实名认证已通过，可以提交在职佐证。'
    }
  } catch (error) {
    realnameError.value = (error as { message?: string }).message || '实名认证失败，请稍后再试'
  } finally {
    // 不保留身份证号明文；提交完成后立即清空输入。
    idNumber.value = ''
    realnameSubmitting.value = false
  }
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  file.value = input.files?.[0] ?? null
}

async function submitVerification() {
  submitError.value = ''
  submitSuccess.value = ''
  const uscc = companyUscc.value.trim().toUpperCase()
  if (!/^[0-9A-Z]{18}$/.test(uscc)) {
    submitError.value = '公司统一社会信用代码必须为 18 位字母或数字'
    return
  }
  if (!periodStart.value || !periodEnd.value) {
    submitError.value = '请填写在职开始与结束月份'
    return
  }
  if (periodEnd.value < periodStart.value) {
    submitError.value = '在职结束月不能早于开始月'
    return
  }
  if (!file.value) {
    submitError.value = '请上传在职佐证材料'
    return
  }

  const form = new FormData()
  form.append('proofType', proofType.value)
  form.append('companyUscc', uscc)
  form.append('periodStart', periodStart.value)
  form.append('periodEnd', periodEnd.value)
  form.append('file', file.value)

  submitting.value = true
  try {
    await api.upload<VerificationItem>('/api/verifications', form)
    submitSuccess.value = '佐证已提交，进入待审核队列。审核结果会通过站内通知发送。'
    file.value = null
    if (fileInput.value) fileInput.value.value = ''
    companyUscc.value = ''
    periodStart.value = ''
    periodEnd.value = ''
    await loadVerifications()
  } catch (error) {
    submitError.value = (error as { message?: string }).message || '佐证提交失败，请稍后再试'
  } finally {
    submitting.value = false
  }
}

function resubmit(item: VerificationItem) {
  proofType.value = item.proofType
  companyUscc.value = item.companyUscc
  periodStart.value = item.periodStart ?? ''
  periodEnd.value = item.periodEnd ?? ''
  submitError.value = ''
  submitSuccess.value = '已填入原核验信息，请重新选择材料后提交。'
}

function statusTone(status: string) {
  return status === 'APPROVED' ? 'ok' : status === 'REJECTED' ? 'rejected' : 'reviewing'
}
</script>

<template>
  <main class="wrap page">
    <div class="mono">VERIFICATION · 我的核验</div>
    <h1>实名与在职佐证</h1>
    <p class="meta">
      实名用于确认账号归属；在职佐证决定你能否对某家公司发评，以及显示「在职中 / 曾在职」徽标。
      原始材料只进入私有存储，前台永不展示。
    </p>

    <div v-if="!auth.ready.value" class="empty-state">
      <h2>正在校验登录状态</h2>
      <p>请稍候，正在读取本机登录凭证。</p>
    </div>

    <div v-else-if="!auth.isLoggedIn.value" class="empty-state">
      <h2>登录后查看核验状态</h2>
      <p>核验记录与发评资格只对本人可见。</p>
      <NuxtLink class="button primary" to="/login?redirect=/me/verification">去登录</NuxtLink>
    </div>

    <template v-else>
      <section class="paper-card form-card">
        <div class="section-head">
          <h2>第一步 · 实名认证</h2>
          <span class="mono">REALNAME · 三要素平台核验</span>
        </div>

        <div v-if="realnameVerified" class="status-line ok">
          <span class="status-tag ok">已实名</span>
          <span>实名认证已完成，你的身份证号只以掩码形式保存在后台。</span>
        </div>

        <template v-else>
          <div class="note">
            连续 3 次实名核验失败会锁定实名能力 24 小时。身份证号不保存在本页面与浏览器存储中，
            提交完成后立即清空输入。
          </div>

          <form class="form-grid" @submit.prevent="submitRealname">
            <div class="field">
              <label for="realname-name">真实姓名</label>
              <input
                id="realname-name"
                v-model="name"
                class="input"
                type="text"
                autocomplete="name"
                maxlength="32"
                placeholder="与身份证一致的姓名"
              />
            </div>
            <div class="field">
              <label for="realname-id">身份证号</label>
              <input
                id="realname-id"
                v-model="idNumber"
                class="input"
                type="text"
                inputmode="text"
                autocomplete="off"
                maxlength="18"
                placeholder="18 位身份证号"
              />
            </div>
            <div class="field form-actions">
              <button class="button primary" type="submit" :disabled="realnameSubmitting">
                {{ realnameSubmitting ? '核验中…' : '提交实名认证' }}
              </button>
            </div>
          </form>

          <p v-if="realnameError" class="form-error" role="alert">{{ realnameError }}</p>
          <p v-if="realnameResult && realnameResult.success" class="form-success">
            实名认证通过，证件号掩码：{{ realnameResult.idMasked || '—' }}
          </p>
          <div v-else-if="realnameResult && !realnameResult.success" class="note warn">
            核验未通过（已失败 {{ realnameResult.failCount }} 次）。
            <template v-if="locked">
              当前已锁定，解锁时间：{{ formatDateTime(realnameResult.lockedUntil) }}。
            </template>
            <template v-else-if="realnameResult.failCount >= 2">
              再失败一次将锁定 24 小时，请核对姓名与证件号。
            </template>
            <template v-else>请核对姓名与证件号后重试。</template>
          </div>
        </template>
      </section>

      <section class="paper-card form-card" style="margin-top: 18px">
        <div class="section-head">
          <h2>第二步 · 在职佐证</h2>
          <span class="mono">PROOF · 仅用于核验</span>
        </div>

        <div v-if="!realnameVerified" class="note warn">
          需先完成实名认证，才能提交在职佐证。
        </div>

        <div v-else>
          <div class="badge-summary">
            <div v-if="approvedItems.length" class="status-line ok">
              <span v-if="latestBadge" class="badge">{{ badgeLabel(latestBadge) }}</span>
              <span>已核验记录 {{ approvedItems.length }} 条。前台只会展示徽标，不展示材料。</span>
            </div>
            <div v-else class="status-line">
              <span class="status-tag">暂无已通过核验</span>
              <span>提交材料并通过审核后，才能对对应公司发布评价。</span>
            </div>
            <p class="hint">
              「在职中」表示结束月不早于当前月，可在该公司长期发评；
              「曾在职」的发评窗口为结束月起 12 个月内，超窗需重新提交佐证。
            </p>
          </div>

          <form class="form-grid" @submit.prevent="submitVerification">
            <div class="field">
              <label for="proof-type">佐证类型</label>
              <select id="proof-type" v-model="proofType" class="select">
                <option value="LABOR_CONTRACT">劳动合同</option>
                <option value="BADGE">工牌</option>
                <option value="PAYROLL">工资流水</option>
                <option value="COMPANY_EMAIL">企业邮箱</option>
              </select>
            </div>
            <div class="field">
              <label for="proof-uscc">公司统一社会信用代码</label>
              <input
                id="proof-uscc"
                v-model="companyUscc"
                class="input"
                type="text"
                maxlength="18"
                autocomplete="off"
                placeholder="18 位 USCC，与公司档案一致"
              />
            </div>
            <div class="field">
              <label for="proof-start">在职开始月</label>
              <input id="proof-start" v-model="periodStart" class="input" type="month" />
            </div>
            <div class="field">
              <label for="proof-end">在职结束月</label>
              <input id="proof-end" v-model="periodEnd" class="input" type="month" />
            </div>
            <div class="field form-span">
              <label for="proof-file">佐证材料（图片或 PDF，≤10MB）</label>
              <input
                id="proof-file"
                ref="fileInput"
                class="input file-input"
                type="file"
                accept="image/*,application/pdf"
                @change="onFileChange"
              />
              <span class="hint">
                图片会生成去 EXIF 的降采样副本；原始文件仅审核员可读。请勿在文件名中填写身份证号等信息。
              </span>
            </div>
            <div class="field form-actions">
              <button class="button primary" type="submit" :disabled="submitting">
                {{ submitting ? '提交中…' : '提交在职佐证' }}
              </button>
            </div>
          </form>

          <p v-if="submitError" class="form-error" role="alert">{{ submitError }}</p>
          <p v-if="submitSuccess" class="form-success">{{ submitSuccess }}</p>
        </div>
      </section>

      <section class="section">
        <div class="section-head">
          <h2>核验记录</h2>
          <span class="mono">TIMELINE · 最新在前</span>
        </div>

        <div v-if="loading" class="skeleton-list">
          <div class="skeleton skeleton-card" />
          <div class="skeleton skeleton-card" />
        </div>

        <div v-else-if="loadError" class="empty-state">
          <h2>核验记录加载失败</h2>
          <p>{{ loadError }}</p>
          <button class="button" type="button" @click="loadVerifications">重试</button>
        </div>

        <div v-else-if="verifications.length === 0" class="empty-state">
          <h2>还没有核验记录</h2>
          <p>完成实名后提交在职佐证，审核通过后即可获得相应公司档案的发评资格。</p>
        </div>

        <ol v-else class="timeline">
          <li v-for="item in verifications" :key="item.id" class="timeline-item">
            <div class="timeline-dot" :class="statusTone(item.status)" aria-hidden="true" />
            <div class="timeline-body">
              <div class="timeline-head">
                <span class="status-tag" :class="statusTone(item.status)">
                  {{ verificationStatusLabel(item.status) }}
                </span>
                <span v-if="item.badge" class="badge">{{ badgeLabel(item.badge) }}</span>
                <span class="mono">{{ proofTypeLabel(item.proofType) }}</span>
                <time class="review-date" :datetime="item.createdAt || undefined">
                  {{ formatDateTime(item.createdAt) }}
                </time>
              </div>
              <p class="timeline-main">
                {{ item.companyName || item.companyUscc }} ·
                {{ reviewPeriod(item.periodStart, item.periodEnd) }}
              </p>
              <p v-if="item.status === 'REJECTED'" class="form-error" style="margin: 4px 0">
                驳回原因：{{ rejectReasonLabel(item.rejectReasonCode) || '未标注原因' }}
                <template v-if="item.rejectNote"> · 备注：{{ item.rejectNote }}</template>
              </p>
              <p v-if="item.status === 'APPROVED' && item.badge === 'FORMER'" class="hint">
                曾在职发评窗口：结束月起 12 个月内有效，超窗需重新提交佐证。
              </p>
              <p v-if="item.reviewedAt" class="hint">
                审核时间：{{ formatDateTime(item.reviewedAt) }}
              </p>
              <div class="button-row" style="margin-top: 8px">
                <button
                  v-if="item.status === 'REJECTED'"
                  type="button"
                  class="button"
                  @click="resubmit(item)"
                >
                  按原信息重新提交
                </button>
                <NuxtLink
                  v-if="item.status === 'APPROVED'"
                  class="button ghost"
                  :to="{ path: '/review/new', query: { uscc: item.companyUscc } }"
                >
                  去评价这家公司
                </NuxtLink>
              </div>
            </div>
          </li>
        </ol>
      </section>
    </template>
  </main>
</template>
