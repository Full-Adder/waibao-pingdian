<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { materialApi, reviewApi } from '../api'
import type {
  ReasonTemplateBundle,
  ReasonTemplateView,
  ReviewQueueDetail,
  ReviewQueueItem
} from '../api/types'
import {
  AI_FLAG_LABEL,
  DIMENSION_LABEL,
  EVIDENCE_TYPE_LABEL,
  REVIEW_SOURCE_LABEL,
  labelOf
} from '../utils/labels'

type DispositionAction = 'approve' | 'fold' | 'reject'

const ACTION_LABEL: Record<DispositionAction, string> = {
  approve: '通过',
  fold: '折叠',
  reject: '驳回'
}

const FALLBACK_TEMPLATES: ReasonTemplateBundle = {
  approve: [
    { reasonCode: 'CONTENT_COMPLIANT', text: '内容合规，证据充分，予以通过' },
    { reasonCode: 'EVIDENCE_SUFFICIENT', text: '佐证材料充分，符合社区规范' },
    { reasonCode: 'FACT_VERIFIED', text: '事实描述清晰，可公开发布' },
    { reasonCode: 'MANUAL_CONFIRMED', text: '人工复核确认无违规内容' }
  ],
  fold: [
    { reasonCode: 'INSUFFICIENT_EVIDENCE', text: '证据不足，折叠展示' },
    { reasonCode: 'VAGUE_CLAIM', text: '描述笼统，缺乏可核实依据' },
    { reasonCode: 'UNVERIFIABLE', text: '无法核实具体事实，折叠展示' },
    { reasonCode: 'LOW_INFORMATION', text: '信息量不足，折叠展示' }
  ],
  reject: [
    { reasonCode: 'INSUFFICIENT_EVIDENCE', text: '证据不足，无法支持该指控' },
    { reasonCode: 'FALSE_CLAIM', text: '包含未经证实的不实指控' },
    { reasonCode: 'ABUSIVE_LANGUAGE', text: '包含人身攻击或侮辱性语言' },
    { reasonCode: 'ADVERTISING', text: '包含广告或联系方式' },
    { reasonCode: 'PRIVACY', text: '泄露个人隐私信息' },
    { reasonCode: 'OTHER', text: '其他不符合社区规范的内容' }
  ]
}

const loading = ref(false)
const rows = ref<ReviewQueueItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const selection = ref<ReviewQueueItem[]>([])
const templates = ref<ReasonTemplateBundle>(FALLBACK_TEMPLATES)

const filters = reactive({
  source: '',
  keyword: ''
})

const sourceOptions = Object.entries(REVIEW_SOURCE_LABEL).map(([value, label]) => ({ value, label }))

const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<ReviewQueueDetail | null>(null)

const disposition = reactive({
  visible: false,
  mode: 'single' as 'single' | 'batch',
  action: 'approve' as DispositionAction,
  ids: [] as string[],
  reasonCode: '',
  note: ''
})

const submitting = ref(false)

const currentTemplates = computed<ReasonTemplateView[]>(() => {
  return templates.value[disposition.action] ?? []
})

const selectedCount = computed(() => selection.value.length)

async function load() {
  loading.value = true
  try {
    const result = await reviewApi.queue({
      source: filters.source || undefined,
      keyword: filters.keyword || undefined,
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
  filters.source = ''
  filters.keyword = ''
  page.value = 1
  load()
}

function handleSelectionChange(value: ReviewQueueItem[]) {
  selection.value = value
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

async function openDetail(row: ReviewQueueItem) {
  drawerVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await reviewApi.detail(row.reviewId)
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    detailLoading.value = false
  }
}

function openDisposition(ids: string[], action: DispositionAction, mode: 'single' | 'batch') {
  if (!ids.length) {
    ElMessage.warning('请先选择要处置的点评')
    return
  }
  disposition.visible = true
  disposition.mode = mode
  disposition.action = action
  disposition.ids = ids
  disposition.reasonCode = ''
  disposition.note = ''
}

function openBatch() {
  openDisposition(
    selection.value.map((row) => row.reviewId),
    'approve',
    'batch'
  )
}

function onActionChange() {
  disposition.reasonCode = ''
}

async function submitDisposition() {
  if (!disposition.reasonCode) {
    ElMessage.warning('请选择理由模板')
    return
  }
  if (!disposition.note.trim()) {
    ElMessage.warning('请填写备注，处置理由与备注会写入审计日志')
    return
  }

  if (disposition.action === 'reject') {
    try {
      await ElMessageBox.confirm('驳回后点评将通知作者，确认提交？', '确认驳回', {
        confirmButtonText: '确认驳回',
        cancelButtonText: '取消',
        type: 'warning'
      })
    } catch {
      return
    }
  }

  submitting.value = true
  try {
    if (disposition.mode === 'batch') {
      const result = await reviewApi.batch(
        disposition.ids,
        disposition.action,
        disposition.reasonCode,
        disposition.note.trim()
      )
      if (result.failed > 0) {
        const failures = result.results
          .filter((item) => !item.success)
          .map((item) => `${item.reviewId.slice(0, 8)}：${item.message ?? '失败'}`)
        ElMessage.warning(`批量处置完成：成功 ${result.succeeded} 条，失败 ${result.failed} 条`)
        await ElMessageBox.alert(failures.join('\n'), '部分条目处置失败', {
          confirmButtonText: '知道了'
        }).catch(() => undefined)
      } else {
        ElMessage.success(`批量处置完成：共 ${result.total} 条`)
      }
    } else {
      const id = disposition.ids[0]
      const note = disposition.note.trim()
      if (disposition.action === 'approve') {
        await reviewApi.approve(id, disposition.reasonCode, note)
      } else if (disposition.action === 'fold') {
        await reviewApi.fold(id, disposition.reasonCode, note)
      } else {
        await reviewApi.reject(id, disposition.reasonCode, note)
      }
      ElMessage.success(`已${ACTION_LABEL[disposition.action]}该点评`)
      if (drawerVisible.value) drawerVisible.value = false
    }
    disposition.visible = false
    selection.value = []
    await load()
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    submitting.value = false
  }
}

function flagLabel(flag: string): string {
  return labelOf(AI_FLAG_LABEL, flag)
}

function confidenceText(value?: number | null): string {
  if (value === undefined || value === null) return '—'
  return Number(value).toFixed(2)
}

function waitingText(hours: number): string {
  if (hours >= 48) return `${Math.floor(hours / 24)} 天 ${hours % 24} 小时`
  return `${hours} 小时`
}

async function openEvidence(materialId: string, type: string) {
  if (!detail.value) return
  try {
    const result = await materialApi.signedUrl(type, materialId, detail.value.reviewId)
    window.open(result.url, '_blank', 'noopener')
  } catch {
    // 材料签名失败时错误信息由请求层展示
  }
}

onMounted(async () => {
  await load()
  try {
    templates.value = await reviewApi.reasonTemplates()
  } catch {
    templates.value = FALLBACK_TEMPLATES
  }
})
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>审核队列</h2>
        <p>仅接收低置信度、含具体指控、被投诉三类；按等待时长升序，目标 48 小时内清空。</p>
      </div>
      <el-tag size="small" effect="plain">待处理 {{ total }} 条</el-tag>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-select v-model="filters.source" clearable placeholder="来源类型">
          <el-option v-for="item in sourceOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-input v-model="filters.keyword" clearable placeholder="搜索公司 / 内容关键词" @keyup.enter="search" />
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
        <span class="grow" />
        <el-button type="warning" plain :disabled="!selectedCount" @click="openBatch">
          批量处置{{ selectedCount ? `（${selectedCount}）` : '' }}
        </el-button>
      </div>
    </div>

    <div class="panel">
      <el-table
        v-loading="loading"
        :data="rows"
        size="small"
        border
        row-key="reviewId"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="42" reserve-selection />
        <el-table-column label="等待" width="118">
          <template #default="{ row }">
            <span :class="{ 'overdue-text': row.overdue }">{{ waitingText(row.waitingHours) }}</span>
            <el-tag v-if="row.overdue" size="small" type="danger" style="margin-left: 4px">超时</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="companyName" label="公司" min-width="150" show-overflow-tooltip />
        <el-table-column label="身份 / 分" width="120">
          <template #default="{ row }">
            <div>{{ row.identityAlias || '匿名' }}</div>
            <div class="muted">{{ row.totalScore }} 分</div>
          </template>
        </el-table-column>
        <el-table-column label="内容摘要" min-width="260">
          <template #default="{ row }">
            <div class="clamp-2">{{ row.content }}</div>
          </template>
        </el-table-column>
        <el-table-column label="AI 命中" min-width="170">
          <template #default="{ row }">
            <el-tag
              v-for="flag in row.aiFlags"
              :key="flag"
              size="small"
              type="warning"
              effect="plain"
              style="margin: 1px 4px 1px 0"
            >
              {{ flagLabel(flag) }}
            </el-tag>
            <span v-if="!row.aiFlags?.length" class="muted">无</span>
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="76" align="right">
          <template #default="{ row }">{{ confidenceText(row.aiConfidence) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="104">
          <template #default="{ row }">
            <el-tag
              size="small"
              effect="plain"
              :type="row.source === 'APPEAL' ? 'danger' : 'info'"
            >
              {{ labelOf(REVIEW_SOURCE_LABEL, row.source) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="success" @click="openDisposition([row.reviewId], 'approve', 'single')">
              通过
            </el-button>
            <el-button link type="warning" @click="openDisposition([row.reviewId], 'fold', 'single')">
              折叠
            </el-button>
            <el-button link type="danger" @click="openDisposition([row.reviewId], 'reject', 'single')">
              驳回
            </el-button>
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

    <el-drawer v-model="drawerVisible" size="640px" title="点评审核详情">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="点评编号">
              <span class="mono">{{ detail.reviewId }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="当前状态">
              {{ detail.status }}
            </el-descriptions-item>
            <el-descriptions-item label="公司">{{ detail.companyName }}</el-descriptions-item>
            <el-descriptions-item label="USCC">
              <span class="mono">{{ detail.uscc }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="匿名身份">{{ detail.identityAlias || '匿名' }}</el-descriptions-item>
            <el-descriptions-item label="综合分">{{ detail.totalScore }} 分</el-descriptions-item>
            <el-descriptions-item label="来源">
              {{ labelOf(REVIEW_SOURCE_LABEL, detail.source) }}
            </el-descriptions-item>
            <el-descriptions-item label="等待时长">
              {{ waitingText(detail.waitingHours) }}
              <el-tag v-if="detail.overdue" size="small" type="danger" style="margin-left: 4px">超时</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="AI 置信度">{{ confidenceText(detail.aiConfidence) }}</el-descriptions-item>
            <el-descriptions-item label="规则版本">{{ detail.rulesVersion || '—' }}</el-descriptions-item>
            <el-descriptions-item label="申诉" :span="2">
              <span v-if="detail.source === 'APPEAL'">申诉复审来源（作者对驳回结果发起）</span>
              <span v-else-if="detail.noAppeal">红线违规，不可申诉</span>
              <span v-else>{{ detail.canAppeal ? '可申诉一次' : '不可申诉' }}</span>
            </el-descriptions-item>
          </el-descriptions>

          <div class="detail-block" style="margin-top: 18px">
            <h4>点评原文</h4>
            <div class="content-box">{{ detail.content }}</div>
            <div class="kv-note">
              维度评分：
              <span v-for="dimension in detail.dimensions" :key="dimension.code" style="margin-right: 10px">
                {{ labelOf(DIMENSION_LABEL, dimension.code) }} {{ dimension.score }}
              </span>
            </div>
          </div>

          <div class="detail-block">
            <h4>AI 初审命中项</h4>
            <div v-if="detail.aiFlags?.length">
              <el-tag
                v-for="flag in detail.aiFlags"
                :key="flag"
                size="small"
                type="warning"
                effect="plain"
                style="margin: 2px 4px 2px 0"
              >
                {{ flagLabel(flag) }}
              </el-tag>
            </div>
            <div v-else class="muted">无 AI 命中项</div>
          </div>

          <div class="detail-block">
            <h4>佐证材料</h4>
            <div v-if="detail.materials?.length">
              <div v-for="material in detail.materials" :key="material.evidenceId" class="evidence-row">
                <div>
                  <div>{{ labelOf(EVIDENCE_TYPE_LABEL, material.type) }}</div>
                  <div class="muted mono">{{ material.evidenceId }}</div>
                </div>
                <div>
                  <el-tag size="small" effect="plain">{{ material.materialStatus }}</el-tag>
                  <el-button link type="primary" style="margin-left: 8px" @click="openEvidence(material.evidenceId, material.type)">
                    查看佐证
                  </el-button>
                </div>
              </div>
            </div>
            <div v-else class="muted">未上传佐证材料{{ detail.hasEvidence ? '' : '（含具体指控但无佐证）' }}</div>
            <div class="kv-note">材料读取必须携带工单号，每次读取写入审计日志。</div>
          </div>

          <div class="detail-block">
            <h4>审核历史</h4>
            <el-timeline v-if="detail.auditHistory?.length">
              <el-timeline-item
                v-for="(item, index) in detail.auditHistory"
                :key="index"
                :timestamp="item.createdAt"
                placement="top"
              >
                <div>
                  <strong>{{ item.stage }} / {{ item.action }}</strong>
                  <span v-if="item.fromStatus || item.toStatus" class="muted">
                    （{{ item.fromStatus || '—' }} → {{ item.toStatus || '—' }}）
                  </span>
                </div>
                <div class="kv-note">
                  {{ item.actorType }} {{ item.reasonCode || '' }}
                  <span v-if="item.detail"> · {{ item.detail }}</span>
                </div>
              </el-timeline-item>
            </el-timeline>
            <div v-else class="muted">暂无审核历史</div>
          </div>
        </template>
        <el-empty v-else-if="!detailLoading" description="未获取到详情" />
      </div>

      <template #footer>
        <div class="drawer-footer">
          <el-button @click="drawerVisible = false">关闭</el-button>
          <el-button
            v-if="detail"
            type="success"
            @click="openDisposition([detail.reviewId], 'approve', 'single')"
          >
            通过
          </el-button>
          <el-button
            v-if="detail"
            type="warning"
            @click="openDisposition([detail.reviewId], 'fold', 'single')"
          >
            折叠
          </el-button>
          <el-button
            v-if="detail"
            type="danger"
            @click="openDisposition([detail.reviewId], 'reject', 'single')"
          >
            驳回
          </el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog
      v-model="disposition.visible"
      :title="`${disposition.mode === 'batch' ? `批量${ACTION_LABEL[disposition.action]}` : ACTION_LABEL[disposition.action]}点评`"
      width="520px"
      destroy-on-close
    >
      <el-form label-width="88px" label-position="left">
        <el-form-item v-if="disposition.mode === 'batch'" label="处置动作">
          <el-select v-model="disposition.action" @change="onActionChange">
            <el-option label="通过" value="approve" />
            <el-option label="折叠" value="fold" />
            <el-option label="驳回" value="reject" />
          </el-select>
          <div class="kv-note">共 {{ disposition.ids.length }} 条点评将逐条处置，部分失败不回滚已成功项。</div>
        </el-form-item>
        <el-form-item label="理由模板" required>
          <el-select v-model="disposition.reasonCode" placeholder="请选择理由模板" style="width: 100%">
            <el-option
              v-for="template in currentTemplates"
              :key="template.reasonCode"
              :label="template.text"
              :value="template.reasonCode"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备注" required>
          <el-input
            v-model="disposition.note"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            placeholder="填写处置说明，将写入审计日志"
          />
        </el-form-item>
      </el-form>
      <div v-if="disposition.action === 'reject'" class="legend">
        驳回后点评转「已驳回」，非红线原因允许作者申诉一次。
      </div>
      <template #footer>
        <el-button @click="disposition.visible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitDisposition">确认提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.overdue-text {
  color: var(--danger);
  font-weight: 600;
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
