<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox, type UploadFile, type UploadInstance } from 'element-plus'
import { Refresh, Search, Upload } from '@element-plus/icons-vue'
import { authState, isSuperAdmin } from '../auth'
import { complaintApi, materialApi } from '../api'
import type { ComplaintDetailView } from '../api/types'
import {
  COMPLAINT_RESULT_LABEL,
  COMPLAINT_STATUS_LABEL,
  COMPLAINT_STATUS_TYPE,
  COMPLAINT_TYPE_LABEL,
  DIMENSION_LABEL,
  fmtDateTime,
  fmtDuration,
  labelOf
} from '../utils/labels'

const loading = ref(false)
const rows = ref<ComplaintDetailView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const filters = reactive({ status: '' })

const statusOptions = Object.entries(COMPLAINT_STATUS_LABEL).map(([value, label]) => ({ value, label }))

const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<ComplaintDetailView | null>(null)

const verifyState = reactive({
  visible: false,
  verified: true,
  note: '',
  submitting: false
})

const evidenceState = reactive({
  party: 'ENTERPRISE',
  grade: 'B',
  note: '',
  file: null as File | null,
  submitting: false
})
const uploadRef = ref<UploadInstance>()

const rulingState = reactive({
  visible: false,
  confirmation: false,
  result: '',
  enterpriseGrade: 'C',
  userGrade: 'C',
  reasonText: '',
  citedMaterialIds: [] as string[],
  ruleClause: '',
  requiresDualReview: false,
  submitting: false
})

const gradeOptions = [
  { value: 'A', label: 'A 强' },
  { value: 'B', label: 'B 中' },
  { value: 'C', label: 'C 弱' },
  { value: 'NONE', label: '未提交' }
]

const resultOptions = computed(() => {
  const e = rulingState.enterpriseGrade
  const u = rulingState.userGrade
  const eStrong = e === 'A' || e === 'B'
  const uStrong = u === 'A' || u === 'B'
  if (!eStrong && !uStrong) return ['INSUFFICIENT']
  if (eStrong && !uStrong) return ['COMPANY_WIN']
  if (uStrong && !eStrong) return ['USER_WIN']
  return ['USER_WIN', 'COMPANY_WIN']
})

const matrixHint = computed(() => {
  const options = resultOptions.value.map((item) => COMPLAINT_RESULT_LABEL[item]).join(' / ')
  return `按证据分级矩阵，当前组合仅允许：${options}`
})

const availableMaterialIds = computed(() => {
  if (!detail.value) return []
  return [
    ...detail.value.evidences.map((item) => item.id),
    ...detail.value.materials.map((item) => item.id)
  ]
})

async function load() {
  loading.value = true
  try {
    const result = await complaintApi.list({
      status: filters.status || undefined,
      page: page.value,
      size: size.value
    })
    rows.value = result.items
    total.value = result.total
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    loading.value = false
  }
}

function search() {
  page.value = 1
  load()
}

function resetFilters() {
  filters.status = ''
  page.value = 1
  load()
}

function handlePageChange(value: number) {
  page.value = value
  load()
}

function handleSizeChange(value: number) {
  size.value = value
  page.value = 1
  load()
}

async function openDetail(row: ComplaintDetailView) {
  drawerVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await complaintApi.detail(row.id)
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    detailLoading.value = false
  }
}

async function refreshDetail() {
  if (!detail.value) return
  try {
    detail.value = await complaintApi.detail(detail.value.id)
  } catch {
    // 错误提示由请求层统一展示
  }
  await load()
}

function openVerify(verified: boolean) {
  verifyState.verified = verified
  verifyState.note = ''
  verifyState.visible = true
}

async function submitVerify() {
  if (!detail.value) return
  if (!verifyState.verified && !verifyState.note.trim()) {
    ElMessage.warning('拒绝主体核验时请填写说明')
    return
  }
  verifyState.submitting = true
  try {
    await complaintApi.verify(detail.value.id, verifyState.verified, verifyState.note.trim() || undefined)
    ElMessage.success(verifyState.verified ? '主体核验通过，点评转为争议中' : '已拒绝主体核验')
    verifyState.visible = false
    await refreshDetail()
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    verifyState.submitting = false
  }
}

function onFileChange(uploadFile: UploadFile) {
  evidenceState.file = uploadFile.raw ?? null
}

async function submitEvidence() {
  if (!detail.value) return
  if (!evidenceState.file) {
    ElMessage.warning('请选择要上传的证据文件')
    return
  }
  evidenceState.submitting = true
  try {
    await complaintApi.addEvidence(detail.value.id, {
      party: evidenceState.party,
      grade: evidenceState.grade,
      note: evidenceState.note.trim(),
      file: evidenceState.file
    })
    ElMessage.success('证据已上传')
    evidenceState.file = null
    evidenceState.note = ''
    uploadRef.value?.clearFiles()
    await refreshDetail()
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    evidenceState.submitting = false
  }
}

function openRuling(confirmation = false) {
  if (!detail.value) return
  const stored = detail.value
  rulingState.confirmation = confirmation
  rulingState.result = confirmation ? stored.proposedResolution ?? '' : ''
  rulingState.enterpriseGrade = stored.enterpriseGrade || 'C'
  rulingState.userGrade = stored.userGrade || 'C'
  rulingState.reasonText = confirmation ? stored.rulingReason ?? '' : ''
  rulingState.citedMaterialIds = stored.citedMaterials
    ? stored.citedMaterials.split(',').map((item) => item.trim()).filter(Boolean)
    : []
  rulingState.ruleClause = stored.ruleClause ?? ''
  rulingState.requiresDualReview = confirmation
  rulingState.visible = true
}

async function submitRuling() {
  if (!detail.value) return
  if (!rulingState.result) {
    ElMessage.warning('请选择裁定结论')
    return
  }
  if (!resultOptions.value.includes(rulingState.result)) {
    ElMessage.warning(matrixHint.value)
    return
  }
  if (!rulingState.reasonText.trim()) {
    ElMessage.warning('请填写裁定理由')
    return
  }
  rulingState.submitting = true
  try {
    const result = await complaintApi.ruling(detail.value.id, {
      result: rulingState.result,
      enterpriseGrade: rulingState.enterpriseGrade,
      userGrade: rulingState.userGrade,
      reasonText: rulingState.reasonText.trim(),
      citedMaterialIds: rulingState.citedMaterialIds,
      ruleClause: rulingState.ruleClause.trim() || undefined,
      requiresDualReview: rulingState.requiresDualReview || undefined
    })
    rulingState.visible = false
    if (result.pendingDualReview) {
      await ElMessageBox.alert(
        '该裁定已进入双人复核，需另一名超级管理员在投诉工单中确认后才会生效。',
        '待双人复核',
        { confirmButtonText: '知道了' }
      )
    } else {
      ElMessage.success(`裁定完成：${COMPLAINT_RESULT_LABEL[result.result] ?? result.result}`)
    }
    await refreshDetail()
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    rulingState.submitting = false
  }
}

async function openMaterial(materialId: string, materialType: string) {
  if (!detail.value) return
  try {
    const result = await materialApi.signedUrl(materialType, materialId, detail.value.id)
    window.open(result.url, '_blank', 'noopener')
  } catch {
    // 错误提示由请求层统一展示
  }
}

async function addToBlocklist() {
  if (!detail.value) return
  try {
    const { value } = await ElMessageBox.prompt(
      `将企业 ${detail.value.claimantUscc} 加入投诉黑名单，请填写原因`,
      '投诉黑名单',
      { confirmButtonText: '确认加入', cancelButtonText: '取消', inputPlaceholder: '违规原因' }
    )
    await complaintApi.blocklist({ uscc: detail.value.claimantUscc, reason: value })
    ElMessage.success('已加入投诉黑名单')
  } catch {
    // 用户取消或请求失败
  }
}

const canConfirmDualReview = computed(() => {
  if (!detail.value || !isSuperAdmin()) return false
  return (
    detail.value.status === 'PENDING_RULING' &&
    detail.value.dualReviewRequired &&
    detail.value.proposedBy !== authState.profile?.id
  )
})

function evidenceGradeTag(side: string): string {
  return side === 'ENTERPRISE' ? '企业' : '用户'
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>投诉工单</h2>
        <p>主体核验 → 双向举证（48 小时）→ 裁定；下架超过 30 天内容及四类情形需双人复核。</p>
      </div>
      <el-tag size="small" effect="plain">共 {{ total }} 条</el-tag>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-select v-model="filters.status" clearable placeholder="工单状态">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
      </div>
    </div>

    <div class="panel">
      <el-table v-loading="loading" :data="rows" size="small" border row-key="id">
        <el-table-column label="提交时间" width="140">
          <template #default="{ row }">{{ fmtDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="企业 / 点评" min-width="220">
          <template #default="{ row }">
            <div>{{ row.review?.companyName || row.claimantUscc }}</div>
            <div class="muted mono">{{ row.claimantUscc }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="112">
          <template #default="{ row }">
            <el-tag size="small" :type="COMPLAINT_STATUS_TYPE[row.status] ?? 'info'">
              {{ labelOf(COMPLAINT_STATUS_LABEL, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="主体核验" width="92">
          <template #default="{ row }">
            <el-tag size="small" :type="row.subjectVerified ? 'success' : 'info'" effect="plain">
              {{ row.subjectVerified ? '已通过' : '未通过' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="举证倒计时" width="128">
          <template #default="{ row }">
            <span :class="{ 'overdue-text': row.overdue }">
              {{ row.overdue ? '已超时' : fmtDuration(row.remainingSeconds) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="标记" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.amountFlag" size="small" type="warning" effect="plain" style="margin-right: 4px">金额</el-tag>
            <el-tag v-if="row.safetyFlag" size="small" type="danger" effect="plain">安全</el-tag>
            <span v-if="!row.amountFlag && !row.safetyFlag" class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="双人复核" width="96">
          <template #default="{ row }">
            <el-tag v-if="row.dualReviewRequired" size="small" type="danger" effect="plain">需复核</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next"
          :total="total"
          :page-size="size"
          :current-page="page"
          :page-sizes="[10, 20, 50, 100]"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </div>

    <el-drawer v-model="drawerVisible" size="780px" title="投诉工单详情">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-alert
            v-if="detail.dualReviewRequired && detail.status === 'PENDING_RULING'"
            type="warning"
            :closable="false"
            show-icon
            style="margin-bottom: 14px"
          >
            <template #title>该工单已进入双人复核</template>
            提案结论：{{ labelOf(COMPLAINT_RESULT_LABEL, detail.proposedResolution) }} ·
            提交人 {{ detail.proposedBy || '—' }}。需另一名超级管理员确认后生效。
          </el-alert>

          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="工单编号">
              <span class="mono">{{ detail.id }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag size="small" :type="COMPLAINT_STATUS_TYPE[detail.status] ?? 'info'">
                {{ labelOf(COMPLAINT_STATUS_LABEL, detail.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="企业 USCC">
              <span class="mono">{{ detail.claimantUscc }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="主体核验">
              <el-tag size="small" :type="detail.subjectVerified ? 'success' : 'info'" effect="plain">
                {{ detail.subjectVerified ? '已通过' : '未通过' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="投诉类型">{{ labelOf(COMPLAINT_TYPE_LABEL, detail.type) }}</el-descriptions-item>
            <el-descriptions-item label="风险标记">
              <el-tag v-if="detail.amountFlag" size="small" type="warning" effect="plain">涉及金额</el-tag>
              <el-tag v-if="detail.safetyFlag" size="small" type="danger" effect="plain" style="margin-left: 4px">人身安全</el-tag>
              <span v-if="!detail.amountFlag && !detail.safetyFlag" class="muted">无</span>
            </el-descriptions-item>
            <el-descriptions-item label="举证截止">{{ fmtDateTime(detail.deadline) }}</el-descriptions-item>
            <el-descriptions-item label="倒计时">
              <span :class="{ 'overdue-text': detail.overdue }">
                {{ detail.overdue ? '已超时' : fmtDuration(detail.remainingSeconds) }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="企业证据等级">{{ detail.enterpriseGrade || '—' }}</el-descriptions-item>
            <el-descriptions-item label="用户证据等级">{{ detail.userGrade || '—' }}</el-descriptions-item>
          </el-descriptions>

          <div class="detail-block" style="margin-top: 18px">
            <h4>点评原文</h4>
            <template v-if="detail.review">
              <div class="content-box">{{ detail.review.content }}</div>
              <div class="kv-note">
                公司 {{ detail.review.companyName }} · 匿名身份 {{ detail.review.identityAlias || '匿名' }} ·
                综合分 {{ detail.review.totalScore }} ·
                状态 {{ detail.review.status }}
                <span v-for="item in detail.review.dimensions" :key="item.code" style="margin-left: 10px">
                  {{ labelOf(DIMENSION_LABEL, item.code) }} {{ item.score }}
                </span>
              </div>
            </template>
            <div v-else class="muted">未关联点评</div>
          </div>

          <div class="detail-block">
            <h4>主体核验</h4>
            <div v-if="detail.status === 'PENDING_VERIFY'" class="verify-actions">
              <el-button type="success" @click="openVerify(true)">核验通过</el-button>
              <el-button type="danger" plain @click="openVerify(false)">核验拒绝</el-button>
              <span class="legend">未通过主体核验不生成工单、不改变点评状态、不触发降权。</span>
            </div>
            <div v-else class="muted">
              {{ detail.subjectVerified ? '主体核验已通过，点评已转为争议中并开始 48 小时举证倒计时。' : '主体核验未通过。' }}
            </div>
          </div>

          <div class="detail-block">
            <h4>双方举证</h4>
            <el-alert
              v-if="detail.status === 'EVIDENCE_COLLECTING' || detail.status === 'PENDING_RULING'"
              type="info"
              :closable="false"
              show-icon
              style="margin-bottom: 10px"
            >
              <template #title>举证窗口</template>
              企业备注：{{ detail.enterpriseEvidenceNote || '未提交' }} · 用户备注：{{ detail.userEvidenceNote || '未提交' }}
            </el-alert>

            <div v-if="detail.evidences?.length">
              <div v-for="evidence in detail.evidences" :key="evidence.id" class="evidence-row">
                <div>
                  <strong>{{ evidenceGradeTag(evidence.side) }}举证</strong>
                  <el-tag size="small" effect="plain" style="margin-left: 6px">等级 {{ evidence.grade || '—' }}</el-tag>
                  <div class="muted">
                    {{ evidence.fileName || '未命名文件' }}
                    <span v-if="evidence.note"> · {{ evidence.note }}</span>
                  </div>
                  <div class="mono muted">{{ evidence.id }}</div>
                </div>
                <div>
                  <el-tag size="small" effect="plain">{{ evidence.materialStatus || 'ACTIVE' }}</el-tag>
                  <el-button
                    link
                    type="primary"
                    style="margin-left: 8px"
                    @click="openMaterial(evidence.id, 'COMPLAINT_EVIDENCE')"
                  >
                    查看
                  </el-button>
                </div>
              </div>
            </div>
            <div v-else class="muted">双方暂未提交举证材料</div>

            <div v-if="detail.materials?.length" style="margin-top: 8px">
              <div v-for="material in detail.materials" :key="`${material.materialType}-${material.id}`" class="evidence-row">
                <div>
                  <strong>{{ material.materialType === 'COMPLAINT_LETTER' ? '申诉函' : '营业执照' }}</strong>
                  <div class="mono muted">{{ material.id }}</div>
                </div>
                <el-button link type="primary" @click="openMaterial(material.id, material.materialType)">
                  查看
                </el-button>
              </div>
            </div>

            <div v-if="detail.status === 'EVIDENCE_COLLECTING'" class="upload-block">
              <h4>管理员代为上传证据</h4>
              <el-form label-width="72px" label-position="left" size="small">
                <el-form-item label="举证方">
                  <el-select v-model="evidenceState.party" style="width: 160px">
                    <el-option label="企业" value="ENTERPRISE" />
                    <el-option label="用户" value="USER" />
                  </el-select>
                </el-form-item>
                <el-form-item label="等级">
                  <el-select v-model="evidenceState.grade" style="width: 160px">
                    <el-option label="A 强" value="A" />
                    <el-option label="B 中" value="B" />
                    <el-option label="C 弱" value="C" />
                  </el-select>
                </el-form-item>
                <el-form-item label="备注">
                  <el-input v-model="evidenceState.note" placeholder="选填，最多 500 字" maxlength="500" />
                </el-form-item>
                <el-form-item label="文件">
                  <el-upload
                    ref="uploadRef"
                    :auto-upload="false"
                    :limit="1"
                    :on-change="onFileChange"
                    :on-exceed="() => ElMessage.warning('每次仅上传一个文件')"
                  >
                    <el-button :icon="Upload">选择文件</el-button>
                  </el-upload>
                </el-form-item>
                <el-form-item>
                  <el-button type="primary" :loading="evidenceState.submitting" @click="submitEvidence">
                    上传证据
                  </el-button>
                </el-form-item>
              </el-form>
            </div>
          </div>

          <div class="detail-block">
            <h4>裁定结果</h4>
            <template v-if="detail.status === 'RULED_USER_WIN' || detail.status === 'RULED_COMPANY_WIN' || detail.status === 'RULED_INSUFFICIENT'">
              <div class="content-box">{{ detail.rulingReason || detail.resolution || '—' }}</div>
              <div class="kv-note">
                引用材料：{{ detail.citedMaterials || '—' }} · 适用条款：{{ detail.ruleClause || '—' }}
              </div>
            </template>
            <template v-else>
              <div class="ruling-actions">
                <el-button
                  v-if="detail.status === 'EVIDENCE_COLLECTING' || (detail.status === 'PENDING_RULING' && !detail.dualReviewRequired)"
                  type="primary"
                  @click="openRuling(false)"
                >
                  出具裁定
                </el-button>
                <el-button v-if="canConfirmDualReview" type="warning" @click="openRuling(true)">
                  确认双人复核
                </el-button>
                <span
                  v-else-if="detail.status === 'PENDING_RULING' && detail.dualReviewRequired"
                  class="legend"
                >
                  需另一名超级管理员确认（提交人与确认人不得相同）。
                </span>
              </div>
              <div class="kv-note">裁定须记录理由、引用材料编号与适用条款，对外只给结论。</div>
            </template>
          </div>

          <div v-if="detail.responses?.length" class="detail-block">
            <h4>企业回应</h4>
            <div v-for="response in detail.responses" :key="response.id" class="content-box" style="margin-bottom: 8px">
              {{ response.content }}
            </div>
          </div>

          <div v-if="isSuperAdmin()" class="detail-block">
            <h4>风控</h4>
            <el-button size="small" type="danger" plain @click="addToBlocklist">加入投诉黑名单</el-button>
            <span class="legend">仅超级管理员可维护，主体核验造假或恶意投诉的企业适用。</span>
          </div>
        </template>
        <el-empty v-else-if="!detailLoading" description="未获取到详情" />
      </div>
    </el-drawer>

    <el-dialog v-model="verifyState.visible" :title="verifyState.verified ? '主体核验通过' : '主体核验拒绝'" width="480px" destroy-on-close>
      <p class="dialog-text" v-if="verifyState.verified">
        通过后点评立即转为「争议中」，排序降权 50%、移出榜单，并开始 48 小时举证倒计时。
      </p>
      <el-form label-position="top">
        <el-form-item label="备注">
          <el-input
            v-model="verifyState.note"
            type="textarea"
            :rows="3"
            maxlength="500"
            placeholder="记录核对结果（拒绝时必须填写）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="verifyState.visible = false">取消</el-button>
        <el-button
          :type="verifyState.verified ? 'success' : 'danger'"
          :loading="verifyState.submitting"
          @click="submitVerify"
        >
          确认{{ verifyState.verified ? '通过' : '拒绝' }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="rulingState.visible"
      :title="rulingState.confirmation ? '确认双人复核' : '出具裁定'"
      width="600px"
      destroy-on-close
    >
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 12px">
        <template #title>证据分级矩阵</template>
        {{ matrixHint }}
      </el-alert>
      <el-form label-width="92px" label-position="left">
        <el-form-item label="企业等级">
          <el-select v-model="rulingState.enterpriseGrade" style="width: 180px" @change="rulingState.result = ''">
            <el-option v-for="item in gradeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="用户等级">
          <el-select v-model="rulingState.userGrade" style="width: 180px" @change="rulingState.result = ''">
            <el-option v-for="item in gradeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="裁定结论" required>
          <el-select v-model="rulingState.result" placeholder="请选择结论" style="width: 100%">
            <el-option
              v-for="item in resultOptions"
              :key="item"
              :label="COMPLAINT_RESULT_LABEL[item]"
              :value="item"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="裁定理由" required>
          <el-input
            v-model="rulingState.reasonText"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
            placeholder="记录比对过程与结论依据"
          />
        </el-form-item>
        <el-form-item label="引用材料">
          <el-select
            v-model="rulingState.citedMaterialIds"
            multiple
            filterable
            allow-create
            default-first-option
            placeholder="选择或输入材料编号"
            style="width: 100%"
          >
            <el-option
              v-for="id in availableMaterialIds"
              :key="id"
              :label="id"
              :value="id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="适用条款">
          <el-input v-model="rulingState.ruleClause" maxlength="200" placeholder="选填，例如 R14-证据分级" />
        </el-form-item>
        <el-form-item v-if="!rulingState.confirmation" label="双人复核">
          <el-checkbox v-model="rulingState.requiresDualReview">本次裁定需要双人复核</el-checkbox>
          <div class="kv-note">同公司同期 ≥3 条投诉、金额 ≥1 万、安全指控、下架超 30 天内容须强制复核。</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rulingState.visible = false">取消</el-button>
        <el-button type="primary" :loading="rulingState.submitting" @click="submitRuling">
          {{ rulingState.confirmation ? '确认并生效' : '提交裁定' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.overdue-text {
  color: var(--danger);
  font-weight: 600;
}

.verify-actions,
.ruling-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.upload-block {
  margin-top: 16px;
  padding-top: 12px;
  border-top: 1px dashed var(--rule);
}

.dialog-text {
  margin: 0 0 10px;
  color: var(--muted);
  font-size: 12.5px;
  line-height: 1.7;
}
</style>
