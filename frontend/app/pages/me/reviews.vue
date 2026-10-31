<script setup lang="ts">
import type {
  ComplaintEvidenceView,
  MyComplaintView,
  PageResult,
  ReviewItem
} from '~/types/api'

useSeoMeta({ title: '我的点评 · 外包点评', robots: 'noindex' })

const api = useApi()
const auth = useRequireAuth()
const route = useRoute()

const loading = ref(false)
const loadError = ref('')
const reviews = ref<ReviewItem[]>([])
const activeTab = ref('all')
const actionMessage = ref('')
const actionError = ref('')

const now = ref(Date.now())
let ticker: ReturnType<typeof setInterval> | null = null

const appealTarget = ref<ReviewItem | null>(null)
const appealStatement = ref('')
const appealError = ref('')
const appealSubmitting = ref(false)

const complaintTarget = ref<ReviewItem | null>(null)
const complaint = ref<MyComplaintView | null>(null)
const complaintLoading = ref(false)
const complaintError = ref('')
const evidenceFile = ref<File | null>(null)
const evidenceNote = ref('')
const evidenceGrade = ref('')
const evidenceSubmitting = ref(false)
const evidenceError = ref('')

const submittedBanner = computed(() => {
  const value = Array.isArray(route.query.submitted) ? route.query.submitted[0] : route.query.submitted
  return Boolean(value)
})

const tabs = [
  { key: 'all', label: '全部' },
  { key: 'reviewing', label: '审核中' },
  { key: 'published', label: '已发布' },
  { key: 'disputed', label: '争议中' },
  { key: 'folded', label: '已折叠' },
  { key: 'rejected', label: '已驳回' },
  { key: 'withdrawn', label: '已撤回' }
]

const TAB_STATUSES: Record<string, string[]> = {
  reviewing: ['DRAFT', 'MACHINE_REVIEWING', 'PENDING_MANUAL', 'EDITING'],
  published: ['PUBLISHED'],
  disputed: ['DISPUTED'],
  folded: ['FOLDED'],
  rejected: ['REJECTED'],
  withdrawn: ['WITHDRAWN']
}

const filteredReviews = computed(() => {
  const statuses = TAB_STATUSES[activeTab.value]
  if (!statuses) return reviews.value
  return reviews.value.filter((review) => statuses.includes(review.status))
})

const tabCounts = computed(() => {
  const counts: Record<string, number> = { all: reviews.value.length }
  for (const [key, statuses] of Object.entries(TAB_STATUSES)) {
    counts[key] = reviews.value.filter((review) => statuses.includes(review.status)).length
  }
  return counts
})

const complaintRemaining = computed(() => {
  if (!complaint.value?.deadline) return 0
  const deadline = new Date(complaint.value.deadline).getTime()
  if (Number.isNaN(deadline)) return 0
  return Math.max(0, Math.floor((deadline - now.value) / 1000))
})

const canUploadComplaintEvidence = computed(() => {
  return Boolean(
    complaint.value
      && complaint.value.status === 'EVIDENCE_COLLECTING'
      && !complaint.value.overdue
      && complaintRemaining.value > 0
  )
})

async function loadReviews() {
  loading.value = true
  loadError.value = ''
  try {
    const result = await api.get<PageResult<ReviewItem>>('/api/reviews/mine', {
      page: 1,
      size: 100
    })
    reviews.value = result?.items ?? []
  } catch (error) {
    loadError.value = (error as { message?: string }).message || '点评加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await auth.init()
  if (!auth.isLoggedIn.value) return
  await auth.refreshProfile()
  await loadReviews()
})

onUnmounted(() => {
  if (ticker) clearInterval(ticker)
})

function startTicker() {
  if (ticker) clearInterval(ticker)
  now.value = Date.now()
  ticker = setInterval(() => {
    now.value = Date.now()
  }, 1000)
}

function statusTone(status: string) {
  if (status === 'PUBLISHED') return 'ok'
  if (status === 'REJECTED') return 'rejected'
  if (status === 'WITHDRAWN' || status === 'FOLDED') return 'muted'
  if (status === 'DISPUTED') return 'disputed'
  return 'reviewing'
}

function statusDescription(review: ReviewItem): string {
  switch (review.status) {
    case 'DRAFT':
      return '草稿只对本人可见，不计分、不索引。'
    case 'MACHINE_REVIEWING':
      return '机器初审中，暂不展示、不计分。'
    case 'PENDING_MANUAL':
      return '已转人工复核，等待审核结果。'
    case 'PUBLISHED':
      return '已发布，计入公司档案。'
    case 'EDITING':
      return '修改后重新审核中，期间暂离展示且不计分。'
    case 'DISPUTED':
      return '企业投诉主体已核验，评价保留展示但带争议标识，排序降权且不计入榜单。'
    case 'FOLDED':
      return review.collapseNote || '争议未决，评价默认折叠，内容不删除。'
    case 'REJECTED':
      return '审核未通过，可在规则允许时申诉一次。'
    case 'WITHDRAWN':
      return '你已撤回该评价，立即移出评分与索引；30 天内不能对同一公司再发评。'
    case 'REMOVED':
      return '评价已下架，仅本人可见记录。'
    default:
      return ''
  }
}

function editDisabledReason(review: ReviewItem): string {
  if (review.status !== 'PUBLISHED') return ''
  if (review.editable) return ''
  if (review.remainingEdits <= 0 || review.editCount >= 2) return '编辑次数已用完（最多 2 次）'
  return '已超过发布后 7 天编辑窗口'
}

function appealDisabledReason(review: ReviewItem): string {
  if (review.status !== 'REJECTED') return ''
  return review.canAppeal ? '' : '该驳回属于红线违规或已申诉过，不可再次申诉'
}

async function withdraw(review: ReviewItem) {
  actionError.value = ''
  actionMessage.value = ''
  if (!window.confirm('确认撤回这条评价？撤回后将立即移出评分与索引，30 天内不能对同一公司再次发评。')) {
    return
  }
  try {
    await api.post(`/api/reviews/${encodeURIComponent(review.id)}/withdraw`)
    actionMessage.value = '评价已撤回。'
    await loadReviews()
  } catch (error) {
    actionError.value = (error as { message?: string }).message || '撤回失败，请稍后再试'
  }
}

function openAppeal(review: ReviewItem) {
  appealTarget.value = review
  appealStatement.value = ''
  appealError.value = ''
}

function closeAppeal() {
  appealTarget.value = null
  appealStatement.value = ''
  appealError.value = ''
}

async function submitAppeal() {
  if (!appealTarget.value) return
  appealError.value = ''
  const statement = appealStatement.value.trim()
  if (statement.length > 500) {
    appealError.value = '申诉说明不能超过 500 字'
    return
  }
  appealSubmitting.value = true
  try {
    await api.post(`/api/reviews/${encodeURIComponent(appealTarget.value.id)}/appeal`, {
      statement: statement || null
    })
    actionMessage.value = '申诉已提交，状态转「待人工」，由人工复核。'
    closeAppeal()
    await loadReviews()
  } catch (error) {
    appealError.value = (error as { message?: string }).message || '申诉提交失败，请稍后再试'
  } finally {
    appealSubmitting.value = false
  }
}

async function openComplaint(review: ReviewItem) {
  complaintTarget.value = review
  complaint.value = null
  complaintError.value = ''
  evidenceError.value = ''
  evidenceFile.value = null
  evidenceNote.value = ''
  evidenceGrade.value = ''
  complaintLoading.value = true
  startTicker()
  try {
    complaint.value = await api.get<MyComplaintView>(
      `/api/complaints/mine/${encodeURIComponent(review.id)}`
    )
  } catch (error) {
    complaintError.value = (error as { message?: string }).message || '该评价暂无投诉工单'
  } finally {
    complaintLoading.value = false
  }
}

function closeComplaint() {
  complaintTarget.value = null
  complaint.value = null
  complaintError.value = ''
  if (ticker) {
    clearInterval(ticker)
    ticker = null
  }
}

function onEvidenceFile(event: Event) {
  const input = event.target as HTMLInputElement
  evidenceFile.value = input.files?.[0] ?? null
}

async function submitEvidence() {
  if (!complaint.value) return
  evidenceError.value = ''
  if (!evidenceFile.value) {
    evidenceError.value = '请选择要上传的举证材料'
    return
  }
  if (evidenceNote.value.trim().length > 500) {
    evidenceError.value = '举证说明不能超过 500 字'
    return
  }

  const form = new FormData()
  form.append('file', evidenceFile.value)
  if (evidenceNote.value.trim()) form.append('note', evidenceNote.value.trim())
  if (evidenceGrade.value) form.append('grade', evidenceGrade.value)

  evidenceSubmitting.value = true
  try {
    await api.upload<ComplaintEvidenceView>(
      `/api/complaints/${encodeURIComponent(complaint.value.complaintId)}/evidence`,
      form
    )
    actionMessage.value = '举证材料已提交，倒计时结束前请留意通知。'
    evidenceFile.value = null
    evidenceNote.value = ''
    evidenceGrade.value = ''
    complaint.value = await api.get<MyComplaintView>(
      `/api/complaints/mine/${encodeURIComponent(complaint.value.reviewId)}`
    )
  } catch (error) {
    evidenceError.value = (error as { message?: string }).message || '举证上传失败，请稍后再试'
  } finally {
    evidenceSubmitting.value = false
  }
}

function countdown(seconds: number): string {
  const total = Math.max(0, seconds)
  const hours = Math.floor(total / 3600)
  const minutes = Math.floor((total % 3600) / 60)
  const secs = total % 60
  return `${String(hours).padStart(2, '0')}:${String(minutes).padStart(2, '0')}:${String(secs).padStart(2, '0')}`
}
</script>

<template>
  <main class="wrap page">
    <div class="mono">MY REVIEWS · 我的点评</div>
    <h1>我的点评</h1>
    <p class="meta">
      按状态查看你发布过的评价、驳回原因与可执行操作。评价不存在删除入口，只能编辑、撤回或申诉。
    </p>

    <div v-if="!auth.ready.value" class="empty-state">
      <h2>正在校验登录状态</h2>
      <p>请稍候，正在读取本机登录凭证。</p>
    </div>

    <div v-else-if="!auth.isLoggedIn.value" class="empty-state">
      <h2>登录后查看我的点评</h2>
      <p>点评状态、驳回原因与编辑入口只对作者本人可见。</p>
      <NuxtLink class="button primary" to="/login?redirect=/me/reviews">去登录</NuxtLink>
    </div>

    <template v-else>
      <div v-if="submittedBanner" class="note">
        评价已提交，当前为「审核中」。审核结果会通过站内通知发送。
      </div>

      <div v-if="actionMessage" class="note success" role="status">{{ actionMessage }}</div>
      <p v-if="actionError" class="form-error" role="alert">{{ actionError }}</p>

      <div class="button-row" style="margin-bottom: 18px">
        <NuxtLink class="button primary" to="/review/new">写新评价</NuxtLink>
        <NuxtLink class="button ghost" to="/me/verification">我的核验</NuxtLink>
        <NuxtLink class="button ghost" to="/me/notifications">通知中心</NuxtLink>
      </div>

      <div class="tabs" role="tablist" aria-label="按点评状态筛选">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          role="tab"
          class="tab"
          :class="{ active: activeTab === tab.key }"
          :aria-selected="activeTab === tab.key"
          @click="activeTab = tab.key"
        >
          {{ tab.label }}
          <span class="tab-count">{{ tabCounts[tab.key] ?? 0 }}</span>
        </button>
      </div>

      <div v-if="loading" class="skeleton-list" style="margin-top: 18px">
        <div class="skeleton skeleton-card" style="height: 180px" />
        <div class="skeleton skeleton-card" style="height: 180px" />
      </div>

      <div v-else-if="loadError" class="empty-state" style="margin-top: 18px">
        <h2>点评加载失败</h2>
        <p>{{ loadError }}</p>
        <button type="button" class="button" @click="loadReviews">重试</button>
      </div>

      <div v-else-if="filteredReviews.length === 0" class="empty-state" style="margin-top: 18px">
        <h2>当前筛选下没有评价</h2>
        <p>如果你在某家外包公司待过，可以先完成核验，再写第一条评价。</p>
        <div class="button-row" style="justify-content: center">
          <NuxtLink class="button primary" to="/me/verification">去核验</NuxtLink>
          <NuxtLink class="button ghost" to="/search">搜索公司</NuxtLink>
        </div>
      </div>

      <div v-else class="review-list" style="margin-top: 18px">
        <article v-for="review in filteredReviews" :key="review.id" class="review-card my-review">
          <header class="my-review-head">
            <div class="review-author">
              <span class="status-tag" :class="statusTone(review.status)">
                {{ statusLabel(review.status) }}
              </span>
              <span v-if="review.verificationBadge" class="badge">
                {{ badgeLabel(review.verificationBadge) }}
              </span>
              <NuxtLink
                v-if="review.companyName"
                class="company-link"
                :to="companyPath(review.uscc)"
              >
                {{ review.companyName }}
              </NuxtLink>
            </div>
            <time v-if="review.createdAt" class="review-date" :datetime="review.createdAt">
              提交于 {{ formatDateTime(review.createdAt) }}
            </time>
          </header>

          <div class="review-sub">
            <StarRating :score="review.totalScore" />
            <span>{{ reviewPeriod(review.periodStart, review.periodEnd) }}</span>
            <span v-if="review.publishedAt">发布于 {{ formatDateTime(review.publishedAt) }}</span>
          </div>

          <p class="review-content">{{ review.content }}</p>

          <div v-if="review.tags.length" class="review-tags">
            <span v-for="tag in review.tags" :key="tag" class="review-tag">{{ tag }}</span>
          </div>

          <p class="hint" style="margin: 10px 0 0">{{ statusDescription(review) }}</p>

          <div v-if="review.status === 'REJECTED'" class="reject-box">
            <div class="mono">驳回原因</div>
            <p>
              {{ review.rejectReasonCode || '未标注原因' }}
              <template v-if="review.rejectMessage"> · {{ review.rejectMessage }}</template>
            </p>
            <p v-if="!review.canAppeal" class="hint">{{ appealDisabledReason(review) }}</p>
          </div>

          <p v-if="review.status === 'PUBLISHED'" class="hint" style="margin: 10px 0 0">
            剩余可编辑次数 {{ review.remainingEdits }} / 2；编辑窗口为发布后 7 天内。
          </p>

          <div class="review-actions">
            <template v-if="review.status === 'PUBLISHED'">
              <NuxtLink
                v-if="review.editable"
                class="button"
                :to="{ path: '/review/new', query: { edit: review.id } }"
              >
                编辑
              </NuxtLink>
              <span v-else class="button disabled" aria-disabled="true" :title="editDisabledReason(review)">
                编辑
              </span>
              <button type="button" class="button ghost" @click="withdraw(review)">撤回</button>
            </template>

            <template v-if="review.status === 'REJECTED'">
              <button
                v-if="review.canAppeal"
                type="button"
                class="button"
                @click="openAppeal(review)"
              >
                申诉（仅一次）
              </button>
              <span v-else class="button disabled" aria-disabled="true" :title="appealDisabledReason(review)">
                申诉不可用
              </span>
            </template>

            <button
              v-if="review.status === 'DISPUTED' || review.everDisputed"
              type="button"
              class="button ghost"
              @click="openComplaint(review)"
            >
              查看投诉工单
            </button>

            <NuxtLink class="button ghost" :to="companyPath(review.uscc)">查看公司档案</NuxtLink>
          </div>
          <p v-if="editDisabledReason(review)" class="hint" style="margin-top: 6px">
            {{ editDisabledReason(review) }}
          </p>
        </article>
      </div>
    </template>

    <!-- 申诉弹窗 -->
    <div v-if="appealTarget" class="modal-backdrop" role="dialog" aria-modal="true" aria-labelledby="appeal-title">
      <div class="modal">
        <div class="modal-head">
          <h2 id="appeal-title">提交申诉</h2>
          <button type="button" class="button ghost" @click="closeAppeal">关闭</button>
        </div>
        <p class="hint">
          每条驳回评价只能申诉一次。红线违规（隐私、广告引流、人身攻击等）不可申诉。
          申诉说明不超过 500 字。
        </p>
        <div class="field">
          <label for="appeal-statement">申诉说明（选填，≤500 字）</label>
          <textarea
            id="appeal-statement"
            v-model="appealStatement"
            class="input textarea"
            rows="5"
            maxlength="500"
          />
        </div>
        <p v-if="appealError" class="form-error" role="alert">{{ appealError }}</p>
        <div class="button-row">
          <button type="button" class="button primary" :disabled="appealSubmitting" @click="submitAppeal">
            {{ appealSubmitting ? '提交中…' : '提交申诉' }}
          </button>
          <button type="button" class="button ghost" @click="closeAppeal">取消</button>
        </div>
      </div>
    </div>

    <!-- 投诉工单弹窗 -->
    <div
      v-if="complaintTarget"
      class="modal-backdrop"
      role="dialog"
      aria-modal="true"
      aria-labelledby="complaint-title"
    >
      <div class="modal modal-wide">
        <div class="modal-head">
          <h2 id="complaint-title">投诉工单</h2>
          <button type="button" class="button ghost" @click="closeComplaint">关闭</button>
        </div>

        <div v-if="complaintLoading" class="skeleton skeleton-card" style="height: 100px" />
        <div v-else-if="complaintError" class="empty-state">
          <h2>暂无投诉工单</h2>
          <p>{{ complaintError }}</p>
        </div>
        <template v-else-if="complaint">
          <div class="complaint-summary">
            <span class="status-tag" :class="complaint.status === 'EVIDENCE_COLLECTING' ? 'disputed' : 'reviewing'">
              {{ complaintStatusLabel(complaint.status) }}
            </span>
            <span v-if="complaint.subjectVerified" class="badge">主体已核验</span>
          </div>

          <p v-if="complaint.status === 'EVIDENCE_COLLECTING'" class="countdown">
            举证剩余时间：
            <b class="mono-data">{{ countdown(complaintRemaining) }}</b>
            <span v-if="complaint.overdue"> · 已截止，超时视为弃权</span>
          </p>
          <p v-else-if="complaint.deadline" class="hint">
            原举证截止时间：{{ formatDateTime(complaint.deadline) }}
          </p>

          <div class="evidence-status">
            <div class="evidence-party">
              <div class="mono">你（作者）</div>
              <p>{{ complaint.userEvidenceSubmitted ? '已提交举证' : '尚未提交举证' }}</p>
              <p v-if="complaint.userEvidenceNote" class="hint">{{ complaint.userEvidenceNote }}</p>
            </div>
            <div class="evidence-party">
              <div class="mono">企业方</div>
              <p>{{ complaint.enterpriseEvidenceSubmitted ? '已提交举证' : '尚未提交举证' }}</p>
              <p v-if="complaint.enterpriseEvidenceNote" class="hint">{{ complaint.enterpriseEvidenceNote }}</p>
            </div>
          </div>

          <p class="hint">
            倒计时对双方可见，截止前 24 小时会向未举证方发送站内与短信提醒。
            对外只公布裁定结论，不披露对方证据细节。
          </p>

          <div v-if="canUploadComplaintEvidence" class="evidence-form">
            <div class="section-head" style="margin-bottom: 8px">
              <h3 style="margin: 0; font-size: 16px">提交我的举证</h3>
              <span class="mono">EVIDENCE · 可选证据等级</span>
            </div>
            <div class="form-grid">
              <div class="field">
                <label for="evidence-grade">证据等级（可选）</label>
                <select id="evidence-grade" v-model="evidenceGrade" class="select">
                  <option value="">不标注</option>
                  <option value="A">A 强：流水 / 合同 / 社保 / 判决</option>
                  <option value="B">B 中：企业邮箱 / 工牌 / 内部系统截图</option>
                  <option value="C">C 弱：纯文字 / 无元数据截图</option>
                </select>
              </div>
              <div class="field form-span">
                <label for="evidence-file">举证材料（≤10MB）</label>
                <input
                  id="evidence-file"
                  class="input file-input"
                  type="file"
                  accept="image/*,application/pdf"
                  @change="onEvidenceFile"
                />
              </div>
              <div class="field form-span">
                <label for="evidence-note">举证说明（≤500 字）</label>
                <textarea id="evidence-note" v-model="evidenceNote" class="input textarea" rows="3" maxlength="500" />
              </div>
            </div>
            <p v-if="evidenceError" class="form-error" role="alert">{{ evidenceError }}</p>
            <button type="button" class="button primary" :disabled="evidenceSubmitting" @click="submitEvidence">
              {{ evidenceSubmitting ? '上传中…' : '提交举证' }}
            </button>
          </div>

          <p v-if="complaint.resolution" class="note">
            裁定结论：{{ complaint.resolution }}
          </p>

          <div v-if="complaint.hasEnterpriseResponse" class="enterprise-response">
            <div class="resp-head"><span>企业回应</span></div>
            <p v-for="response in complaint.enterpriseResponses" :key="response.id">
              {{ response.content }}
            </p>
            <p class="hint">企业回应不计分、不改变评价状态。</p>
          </div>
        </template>
      </div>
    </div>
  </main>
</template>
