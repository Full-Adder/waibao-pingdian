<script setup lang="ts">
import type {
  CompanyDetail,
  CompanySummary,
  PageResult,
  ReviewItem,
  VerificationItem
} from '~/types/api'

useSeoMeta({ title: '发布评价 · 外包点评', robots: 'noindex' })

const api = useApi()
const auth = useRequireAuth()
const route = useRoute()

const isEdit = computed(() => Boolean(singleQuery(route.query.edit)))
const editId = computed(() => singleQuery(route.query.edit))
const usccParam = computed(() => singleQuery(route.query.uscc).trim().toUpperCase())

const step = ref(isEdit.value ? 2 : 1)
const loadingReview = ref(false)
const loadError = ref('')
const verifications = ref<VerificationItem[]>([])
const verificationsLoaded = ref(false)

const form = reactive({
  uscc: '',
  companyName: '',
  periodStart: '',
  periodEnd: '',
  totalScore: null as number | null,
  dimensions: {} as Record<string, number | null>,
  content: '',
  outsourcingType: '',
  city: '',
  projectType: ''
})

for (const code of DIMENSION_ORDER) {
  form.dimensions[code] = null
}

const keyword = ref('')
const searching = ref(false)
const searchError = ref('')
const searchResults = ref<CompanySummary[]>([])

const submitting = ref(false)
const submitError = ref('')
const submitted = ref(false)
const duplicateReviewId = ref<string | null>(null)

function singleQuery(value: unknown): string {
  if (Array.isArray(value)) return String(value[0] ?? '')
  return value === null || value === undefined ? '' : String(value)
}

const contentLength = computed(() => [...form.content].length)

const selectedDimensions = computed(() =>
  DIMENSION_ORDER.filter((code) => Number(form.dimensions[code] ?? 0) > 0).map((code) => ({
    code,
    score: Number(form.dimensions[code])
  }))
)

const dimensionCount = computed(() => selectedDimensions.value.length)
const starValue = computed(() => Number(form.totalScore ?? 0))

const periodValid = computed(() => {
  if (!form.periodStart || !form.periodEnd) return false
  return form.periodEnd >= form.periodStart
})

const step1Valid = computed(() => /^[0-9A-Z]{18}$/.test(form.uscc.trim().toUpperCase()) && periodValid.value)
const step2Valid = computed(() => starValue.value >= 1 && starValue.value <= 5 && dimensionCount.value >= 3)
const step3Valid = computed(() => contentLength.value >= 20 && contentLength.value <= 2000)
const allValid = computed(() => step1Valid.value && step2Valid.value && step3Valid.value)

const usableVerification = computed(() => {
  if (!form.uscc) return null
  return (
    verifications.value.find((item) => {
      if (item.status !== 'APPROVED') return false
      if (item.companyUscc.toUpperCase() !== form.uscc.trim().toUpperCase()) return false
      if (item.badge === 'CURRENT') return true
      if (!item.periodEnd) return false
      const end = new Date(`${item.periodEnd.slice(0, 10)}T23:59:59+08:00`)
      if (Number.isNaN(end.getTime())) return false
      end.setMonth(end.getMonth() + 12)
      return Date.now() <= end.getTime()
    }) ?? null
  )
})

const qualified = computed(() => isEdit.value || Boolean(usableVerification.value))
const previewBadge = computed(() => {
  if (isEdit.value) return null
  return usableVerification.value?.badge ?? null
})
const previewAlias = computed(() => auth.profile.value?.identity?.alias || '匿名·—')

const stepMessage = computed(() => {
  if (step.value === 1) {
    if (!form.uscc) return '请先选择要评价的公司'
    if (!periodValid.value) return '请填写有效的在职时间段（结束月不能早于开始月）'
  }
  if (step.value === 2) {
    if (starValue.value < 1) return '请选择 1–5 星的总分'
    if (dimensionCount.value < 3) return `六个维度至少选择 3 项，当前已选 ${dimensionCount.value} 项`
  }
  if (step.value === 3) {
    if (contentLength.value < 20) return `正文至少 20 字，当前 ${contentLength.value} 字`
    if (contentLength.value > 2000) return `正文不能超过 2000 字，当前 ${contentLength.value} 字`
  }
  return ''
})

onMounted(async () => {
  await auth.init()
  if (!auth.isLoggedIn.value) return
  await auth.refreshProfile()

  try {
    const result = await api.get<PageResult<VerificationItem>>('/api/verifications/mine', {
      page: 1,
      size: 100
    })
    verifications.value = result?.items ?? []
  } catch {
    verifications.value = []
  } finally {
    verificationsLoaded.value = true
  }

  if (editId.value) {
    await loadReview(editId.value)
  } else if (usccParam.value) {
    await loadCompany(usccParam.value)
  }
})

async function loadCompany(uscc: string) {
  try {
    const detail = await api.get<CompanyDetail>(`/api/companies/${encodeURIComponent(uscc)}`)
    if (!detail?.uscc) return
    form.uscc = detail.uscc
    form.companyName = detail.name || detail.shortName || detail.uscc
    form.city = detail.city || ''
  } catch (error) {
    loadError.value = (error as { message?: string }).message || '公司信息加载失败'
  }
}

async function loadReview(id: string) {
  loadingReview.value = true
  loadError.value = ''
  try {
    const review = await api.get<ReviewItem>(`/api/reviews/${encodeURIComponent(id)}`)
    form.uscc = review.uscc
    form.companyName = review.companyName || review.uscc
    form.periodStart = review.periodStart || ''
    form.periodEnd = review.periodEnd || ''
    form.totalScore = review.totalScore
    form.content = review.content
    form.city = review.city || ''
    for (const dimension of review.dimensions ?? []) {
      form.dimensions[dimension.code] = dimension.score
    }
    const labels = review.tags ?? []
    form.outsourcingType = labels.find((label) => label in OUTSOURCING_TYPE_LABELS) || ''
    form.projectType = labels.find((label) => label in PROJECT_TYPE_LABELS) || ''
    step.value = 2
  } catch (error) {
    loadError.value = (error as { message?: string }).message || '原评价加载失败'
  } finally {
    loadingReview.value = false
  }
}

async function runSearch() {
  searchError.value = ''
  const value = keyword.value.trim()
  if (!value) {
    searchError.value = '请输入公司名称或简称'
    return
  }
  searching.value = true
  try {
    const result = await api.get<PageResult<CompanySummary>>('/api/companies/search', {
      keyword: value,
      page: 1,
      size: 10
    })
    searchResults.value = result?.items ?? []
    if (!searchResults.value.length) {
      searchError.value = '没有匹配的公司，请换一个名称，或先上报公司档案。'
    }
  } catch (error) {
    searchError.value = (error as { message?: string }).message || '搜索失败，请稍后再试'
  } finally {
    searching.value = false
  }
}

function selectCompany(company: CompanySummary) {
  form.uscc = company.uscc
  form.companyName = company.name
  form.city = company.city || form.city
  searchResults.value = []
  keyword.value = ''
  searchError.value = ''
}

function changeCompany() {
  form.uscc = ''
  form.companyName = ''
  searchResults.value = []
}

function nextStep() {
  if (stepMessage.value) return
  if (step.value < 4) step.value += 1
}

function prevStep() {
  const minimum = isEdit.value ? 2 : 1
  if (step.value > minimum) step.value -= 1
}

async function submit() {
  submitError.value = ''
  duplicateReviewId.value = null
  if (!allValid.value) {
    step.value = firstInvalidStep()
    return
  }
  if (!qualified.value) {
    step.value = 1
    submitError.value = '未找到该公司有效的在职佐证，请先完成核验后再发评。'
    return
  }

  const tags: Record<string, string> = {}
  if (form.outsourcingType) tags.outsourcingType = form.outsourcingType
  if (form.projectType) tags.projectType = form.projectType
  if (form.city) tags.city = form.city

  const payload = {
    uscc: form.uscc.trim().toUpperCase(),
    periodStart: form.periodStart,
    periodEnd: form.periodEnd,
    totalScore: Number(form.totalScore),
    content: form.content.trim(),
    dimensions: selectedDimensions.value,
    tags
  }

  submitting.value = true
  try {
    if (isEdit.value) {
      await api.patch<ReviewItem>(`/api/reviews/${encodeURIComponent(editId.value)}`, payload)
    } else {
      await api.post<ReviewItem>('/api/reviews', payload)
    }
    submitted.value = true
    setTimeout(() => {
      navigateTo(isEdit.value ? '/me/reviews' : '/me/reviews?submitted=1')
    }, 1500)
  } catch (error) {
    const apiError = error as { message?: string; details?: unknown }
    submitError.value = apiError.message || '提交失败，请稍后再试'
    const details = apiError.details as { reviewId?: string } | null
    if (details && typeof details === 'object' && details.reviewId) {
      duplicateReviewId.value = details.reviewId
    }
  } finally {
    submitting.value = false
  }
}

function firstInvalidStep() {
  if (!step1Valid.value) return 1
  if (!step2Valid.value) return 2
  return 3
}

const tagPreview = computed(() => {
  const tags: string[] = []
  if (form.outsourcingType) tags.push(outsourcingTypeLabel(form.outsourcingType))
  if (form.city) tags.push(form.city)
  if (form.projectType) tags.push(projectTypeLabel(form.projectType))
  return tags
})
</script>

<template>
  <main class="wrap page">
    <div class="mono">{{ isEdit ? 'EDIT REVIEW · 编辑评价' : 'NEW REVIEW · 发布评价' }}</div>
    <h1>{{ isEdit ? '编辑评价' : '发布一条评价' }}</h1>
    <p class="meta">
      评价基于你在该公司的真实在职经历。总分必填，六个维度至少填 3 项，正文 20–2000 字。
    </p>

    <div v-if="!auth.ready.value" class="empty-state">
      <h2>正在校验登录状态</h2>
      <p>请稍候，正在读取本机登录凭证。</p>
    </div>

    <div v-else-if="!auth.isLoggedIn.value" class="empty-state">
      <h2>登录后才能发布评价</h2>
      <p>发评需要已登录并完成实名与在职佐证核验。</p>
      <NuxtLink class="button primary" to="/login?redirect=/review/new">去登录</NuxtLink>
    </div>

    <div v-else-if="loadingReview" class="skeleton-list">
      <div class="skeleton skeleton-card" style="height: 220px" />
    </div>

    <div v-else-if="loadError" class="empty-state">
      <h2>页面数据加载失败</h2>
      <p>{{ loadError }}</p>
      <NuxtLink class="button" to="/me/reviews">返回我的点评</NuxtLink>
    </div>

    <section v-else-if="submitted" class="paper-card form-card success-card">
      <div class="mono">SUBMITTED · 已提交</div>
      <h2>{{ isEdit ? '修改已提交，正在重新审核' : '评价已提交，状态为「审核中」' }}</h2>
      <p>
        机器初审与必要的人工复核期间，评价不会展示在公司档案上，也不计入综合分。
        审核结果会通过站内通知发送，可在「我的点评」查看状态。
      </p>
      <NuxtLink class="button primary" to="/me/reviews">前往我的点评</NuxtLink>
    </section>

    <template v-else>
      <div v-if="!qualified && verificationsLoaded" class="note warn">
        <p style="margin: 0 0 8px">
          未找到该公司有效的在职佐证。已实名但未通过佐证的用户不能发评；
          曾在职超过 12 个月发评窗口的，需要重新提交佐证。
        </p>
        <NuxtLink class="button" to="/me/verification">去提交 / 更新在职佐证</NuxtLink>
      </div>

      <ol class="steps" aria-label="发评步骤">
        <li
          v-for="item in [
            { no: 1, label: '选公司与在职信息' },
            { no: 2, label: '打分（总分 + 维度）' },
            { no: 3, label: '正文与模糊标签' },
            { no: 4, label: '匿名预览与提交' }
          ]"
          :key="item.no"
          class="step"
          :class="{ active: step === item.no, done: step > item.no }"
          :aria-current="step === item.no ? 'step' : undefined"
        >
          <span class="step-no">{{ item.no }}</span>
          <span class="step-label">{{ item.label }}</span>
        </li>
      </ol>

      <section class="paper-card form-card">
        <!-- Step 1 -->
        <div v-if="step === 1" class="step-body">
          <div class="section-head">
            <h2>选择公司与在职时间段</h2>
            <span class="mono">STEP 01 · COMPANY &amp; PERIOD</span>
          </div>

          <div v-if="form.uscc" class="selected-company">
            <div>
              <div class="mono">已选公司</div>
              <div class="selected-company-name">{{ form.companyName || form.uscc }}</div>
              <div class="company-meta">
                <span>USCC {{ form.uscc }}</span>
                <span v-if="form.city">城市 {{ form.city }}</span>
              </div>
            </div>
            <button type="button" class="button ghost" @click="changeCompany">更换公司</button>
          </div>

          <div v-else class="company-picker">
            <form class="search-inline" @submit.prevent="runSearch">
              <div class="field">
                <label for="review-company-keyword">按公司名称或简称搜索</label>
                <input
                  id="review-company-keyword"
                  v-model="keyword"
                  class="input"
                  type="search"
                  autocomplete="off"
                  placeholder="例如「极光软件」"
                />
              </div>
              <button class="button primary" type="submit" :disabled="searching">
                {{ searching ? '搜索中…' : '搜索公司' }}
              </button>
            </form>

            <p v-if="searchError" class="form-error" role="alert">{{ searchError }}</p>

            <div v-if="searchResults.length" class="company-list" style="margin-top: 12px">
              <button
                v-for="company in searchResults"
                :key="company.uscc"
                type="button"
                class="company-option"
                @click="selectCompany(company)"
              >
                <span class="company-option-name">{{ company.name }}</span>
                <span class="company-meta">
                  <span>USCC {{ company.uscc }}</span>
                  <span v-if="company.city">{{ company.city }}</span>
                  <span v-if="company.industry">{{ company.industry }}</span>
                </span>
              </button>
            </div>

            <p class="hint" style="margin-top: 10px">
              找不到公司？可先通过
              <NuxtLink to="/report-company">上报公司</NuxtLink>
              提交待核验候选，或直接填写 18 位 USCC：
            </p>
            <div class="field">
              <label for="review-uscc">统一社会信用代码</label>
              <input
                id="review-uscc"
                v-model="form.uscc"
                class="input"
                type="text"
                maxlength="18"
                autocomplete="off"
                placeholder="18 位 USCC"
              />
            </div>
          </div>

          <div class="form-grid" style="margin-top: 16px">
            <div class="field">
              <label for="review-period-start">在职开始月</label>
              <input id="review-period-start" v-model="form.periodStart" class="input" type="month" />
            </div>
            <div class="field">
              <label for="review-period-end">在职结束月</label>
              <input id="review-period-end" v-model="form.periodEnd" class="input" type="month" />
            </div>
          </div>
          <p class="hint">
            在职时间段用于核验与重复规则判断：同一用户 × 同一公司 × 同一时间段只能有 1 条评价。
            同行离职评价请使用真实区间，前端不会公开具体年月。
          </p>
        </div>

        <!-- Step 2 -->
        <div v-else-if="step === 2" class="step-body">
          <div class="section-head">
            <h2>打分</h2>
            <span class="mono">STEP 02 · SCORE</span>
          </div>

          <p v-if="isEdit" class="hint" style="margin-top: 0">
            编辑时公司、在职时间段与核验佐证不可变更；修改提交后会重新进入审核。
          </p>

          <div class="score-field">
            <label class="field-label">总分（必填，1–5 星）</label>
            <StarInput v-model="form.totalScore" label="总分" />
          </div>

          <div class="dimension-form">
            <div class="section-head" style="margin-bottom: 10px">
              <h3 style="margin: 0; font-size: 17px">六个维度（至少选择 3 项）</h3>
              <span class="mono">已选 {{ dimensionCount }} / 6</span>
            </div>

            <div
              v-for="code in DIMENSION_ORDER"
              :key="code"
              class="dimension-form-row"
            >
              <span class="dimension-form-name">{{ dimensionLabel(code) }}</span>
              <StarInput
                v-model="form.dimensions[code]"
                :label="dimensionLabel(code)"
                allow-clear
              />
            </div>

            <p v-if="dimensionCount < 3" class="form-error" role="alert">
              至少选择 3 个维度，当前已选 {{ dimensionCount }} 项。不足 3 项时无法进入下一步。
            </p>
          </div>
        </div>

        <!-- Step 3 -->
        <div v-else-if="step === 3" class="step-body">
          <div class="section-head">
            <h2>正文与模糊标签</h2>
            <span class="mono">STEP 03 · CONTENT</span>
          </div>

          <div class="field">
            <label for="review-content">
              评价正文（20–2000 字，当前 {{ contentLength }} 字）
            </label>
            <textarea
              id="review-content"
              v-model="form.content"
              class="input textarea"
              rows="8"
              maxlength="2000"
              placeholder="写清楚岗位、项目节奏、薪资发放、合同与管理等真实经历。请勿填写身份证号、手机号、住址等个人信息，也不要编造违法事实。"
            />
            <span class="hint">
              少于 20 字会被退回补充；超过 2000 字无法提交。含具体指控但无佐证的内容会被转人工复核。
            </span>
          </div>

          <fieldset class="tag-fieldset">
            <legend>模糊标签（可选，禁止填写具体项目名称）</legend>
            <div class="form-grid">
              <div class="field">
                <label for="review-outsourcing">外包类型</label>
                <select id="review-outsourcing" v-model="form.outsourcingType" class="select">
                  <option value="">不填写</option>
                  <option
                    v-for="(label, code) in OUTSOURCING_TYPE_LABELS"
                    :key="code"
                    :value="code"
                  >
                    {{ label }}
                  </option>
                </select>
              </div>
              <div class="field">
                <label for="review-city">城市</label>
                <select id="review-city" v-model="form.city" class="select">
                  <option value="">不填写</option>
                  <option v-for="city in CITIES" :key="city" :value="city">{{ city }}</option>
                </select>
              </div>
              <div class="field">
                <label for="review-project-type">项目类型</label>
                <select id="review-project-type" v-model="form.projectType" class="select">
                  <option value="">不填写</option>
                  <option
                    v-for="(label, code) in PROJECT_TYPE_LABELS"
                    :key="code"
                    :value="code"
                  >
                    {{ label }}
                  </option>
                </select>
              </div>
            </div>
            <p class="hint">
              标签只用于粗粒度筛选，不能包含公司内部项目名、客户名、人名或联系方式。
            </p>
          </fieldset>

          <div class="field">
            <label for="review-evidence">佐证文件（暂未开放）</label>
            <input id="review-evidence" class="input file-input" type="file" disabled />
            <span class="hint">
              后端当前未开放点评佐证上传接口，本版仅提交文字与模糊标签。
              在职佐证请在「我的核验」中提交，前台不会展示原始材料。
            </span>
          </div>
        </div>

        <!-- Step 4 -->
        <div v-else class="step-body">
          <div class="section-head">
            <h2>匿名预览与提交</h2>
            <span class="mono">STEP 04 · PREVIEW</span>
          </div>

          <article class="review-card preview-card">
            <header class="review-head">
              <div class="review-author">
                <span class="alias">{{ previewAlias }}</span>
                <span v-if="previewBadge" class="badge">{{ badgeLabel(previewBadge) }}</span>
                <span v-else-if="isEdit" class="status-tag">编辑后重新审核</span>
              </div>
              <span class="review-date">匿名预览</span>
            </header>
            <div class="review-sub">
              <StarRating :score="form.totalScore" />
              <span v-if="form.companyName">{{ form.companyName }}</span>
            </div>
            <p class="review-content">{{ form.content || '（尚未填写正文）' }}</p>
            <div v-if="tagPreview.length" class="review-tags">
              <span v-for="tag in tagPreview" :key="tag" class="review-tag">{{ tag }}</span>
            </div>
            <div class="review-tags">
              <span class="review-tag">{{ reviewPeriod(form.periodStart, form.periodEnd) }}</span>
            </div>
            <div v-if="selectedDimensions.length" class="dimension-inline">
              <span v-for="dimension in selectedDimensions" :key="dimension.code">
                {{ dimensionLabel(dimension.code) }} <b>{{ dimension.score }}</b>
              </span>
            </div>
          </article>

          <div class="note">
            匿名别名由账号固定生成，用户不可自定义。评价会在写入时快照匿名身份；
            更换身份后历史评价仍显示旧别名，不会与新内容关联展示。
          </div>

          <p v-if="!qualified" class="form-error" role="alert">
            未找到该公司有效的在职佐证，无法提交。请先
            <NuxtLink to="/me/verification">提交在职佐证</NuxtLink>。
          </p>

          <p v-if="submitError" class="form-error" role="alert">
            {{ submitError }}
            <NuxtLink
              v-if="duplicateReviewId"
              :to="{ path: '/review/new', query: { edit: duplicateReviewId } }"
            >
              去编辑原评价
            </NuxtLink>
          </p>

          <div class="button-row">
            <button class="button primary" type="button" :disabled="submitting || !allValid || !qualified" @click="submit">
              {{ submitting ? '提交中…' : isEdit ? '提交修改并重新审核' : '提交评价，进入审核' }}
            </button>
            <button class="button ghost" type="button" @click="prevStep">返回修改</button>
          </div>
          <p v-if="!allValid" class="hint">仍有未完成的必填项，请返回对应步骤补充。</p>
        </div>
      </section>

      <div class="step-footer">
        <button class="button ghost" type="button" :disabled="step === 1 || (isEdit && step === 2)" @click="prevStep">
          上一步
        </button>
        <span v-if="stepMessage" class="form-error" role="alert">{{ stepMessage }}</span>
        <button
          v-if="step < 4"
          class="button primary"
          type="button"
          :disabled="Boolean(stepMessage)"
          @click="nextStep"
        >
          下一步
        </button>
      </div>
    </template>
  </main>
</template>
