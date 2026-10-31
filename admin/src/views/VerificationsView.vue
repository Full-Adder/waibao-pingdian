<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { materialApi, verificationApi } from '../api'
import type { VerificationItem } from '../api/types'
import {
  MATERIAL_STATUS_LABEL,
  PROOF_TYPE_LABEL,
  VERIFICATION_REJECT_LABEL,
  VERIFICATION_STATUS_LABEL,
  labelOf
} from '../utils/labels'

const loading = ref(false)
const rows = ref<VerificationItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const filters = reactive({
  status: 'PENDING'
})

const drawerVisible = ref(false)
const detail = ref<VerificationItem | null>(null)
const materialUrl = ref('')
const materialExpiresAt = ref('')

const rejectState = reactive({
  visible: false,
  reasonCode: '',
  note: '',
  submitting: false,
  target: null as VerificationItem | null
})

const rejectOptions = Object.entries(VERIFICATION_REJECT_LABEL).map(([value, label]) => ({ value, label }))

const statusOptions = Object.entries(VERIFICATION_STATUS_LABEL).map(([value, label]) => ({ value, label }))

const drawerTitle = computed(() => (detail.value ? `核验详情 · ${detail.value.companyName || detail.value.companyUscc}` : '核验详情'))

async function load() {
  loading.value = true
  try {
    const result = await verificationApi.list({
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
  filters.status = 'PENDING'
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

function openDetail(row: VerificationItem) {
  detail.value = row
  materialUrl.value = ''
  materialExpiresAt.value = ''
  drawerVisible.value = true
}

async function viewMaterial() {
  if (!detail.value) return
  try {
    const result = await materialApi.signedUrl('VERIFICATION', detail.value.id, detail.value.id)
    materialUrl.value = result.url
    materialExpiresAt.value = result.expiresAt
    window.open(result.url, '_blank', 'noopener')
    ElMessage.success('已生成签名地址，5 分钟内有效')
  } catch {
    // 错误提示由请求层统一展示
  }
}

async function approve(row: VerificationItem | null) {
  if (!row) return
  try {
    await ElMessageBox.confirm(
      `确认通过「${row.companyName || row.companyUscc}」的${labelOf(PROOF_TYPE_LABEL, row.proofType)}核验？`,
      '核验通过',
      { confirmButtonText: '确认通过', cancelButtonText: '取消', type: 'success' }
    )
  } catch {
    return
  }
  try {
    await verificationApi.approve(row.id)
    ElMessage.success('已通过该核验')
    drawerVisible.value = false
    await load()
  } catch {
    // 错误提示由请求层统一展示
  }
}

function openReject(row: VerificationItem | null) {
  if (!row) return
  rejectState.visible = true
  rejectState.reasonCode = ''
  rejectState.note = ''
  rejectState.target = row
}

async function submitReject() {
  if (!rejectState.target) return
  if (!rejectState.reasonCode) {
    ElMessage.warning('请选择驳回原因码')
    return
  }
  rejectState.submitting = true
  try {
    await verificationApi.reject(rejectState.target.id, rejectState.reasonCode, rejectState.note.trim())
    ElMessage.success('已驳回该核验')
    rejectState.visible = false
    drawerVisible.value = false
    await load()
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    rejectState.submitting = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>核验审核</h2>
        <p>审核在职佐证材料，材料仅支持 5 分钟签名地址访问，每次读取写入审计日志。</p>
      </div>
      <el-tag size="small" effect="plain">共 {{ total }} 条</el-tag>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-select v-model="filters.status" clearable placeholder="核验状态">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
      </div>
    </div>

    <div class="panel">
      <el-table v-loading="loading" :data="rows" size="small" border row-key="id">
        <el-table-column label="提交时间" width="140">
          <template #default="{ row }">{{ row.createdAt?.slice(0, 16).replace('T', ' ') }}</template>
        </el-table-column>
        <el-table-column prop="companyName" label="公司" min-width="170" show-overflow-tooltip>
          <template #default="{ row }">{{ row.companyName || row.companyUscc }}</template>
        </el-table-column>
        <el-table-column label="佐证类型" width="110">
          <template #default="{ row }">{{ labelOf(PROOF_TYPE_LABEL, row.proofType) }}</template>
        </el-table-column>
        <el-table-column label="在职周期" width="190">
          <template #default="{ row }">{{ row.periodStart }} ~ {{ row.periodEnd }}</template>
        </el-table-column>
        <el-table-column label="材料" width="90">
          <template #default="{ row }">{{ labelOf(MATERIAL_STATUS_LABEL, row.materialStatus) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 'PENDING' ? 'warning' : row.status === 'APPROVED' ? 'success' : 'info'"
            >
              {{ labelOf(VERIFICATION_STATUS_LABEL, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="success" :disabled="row.status !== 'PENDING'" @click="approve(row)">
              通过
            </el-button>
            <el-button link type="danger" :disabled="row.status !== 'PENDING'" @click="openReject(row)">
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

    <el-drawer v-model="drawerVisible" size="560px" :title="drawerTitle">
      <template v-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="核验编号">
            <span class="mono">{{ detail.id }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="用户 ID">
            <span class="mono">{{ detail.userId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="公司">{{ detail.companyName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="USCC">
            <span class="mono">{{ detail.companyUscc }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="佐证类型">{{ labelOf(PROOF_TYPE_LABEL, detail.proofType) }}</el-descriptions-item>
          <el-descriptions-item label="在职周期">{{ detail.periodStart }} ~ {{ detail.periodEnd }}</el-descriptions-item>
          <el-descriptions-item label="材料状态">
            {{ labelOf(MATERIAL_STATUS_LABEL, detail.materialStatus) }}
          </el-descriptions-item>
          <el-descriptions-item label="核验状态">
            <el-tag size="small" :type="detail.status === 'PENDING' ? 'warning' : detail.status === 'APPROVED' ? 'success' : 'info'">
              {{ labelOf(VERIFICATION_STATUS_LABEL, detail.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ detail.createdAt }}</el-descriptions-item>
          <el-descriptions-item label="审核时间">{{ detail.reviewedAt || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.rejectReasonCode" label="驳回原因">
            {{ labelOf(VERIFICATION_REJECT_LABEL, detail.rejectReasonCode) }}
            <div v-if="detail.rejectNote" class="kv-note">{{ detail.rejectNote }}</div>
          </el-descriptions-item>
        </el-descriptions>

        <div class="detail-block" style="margin-top: 18px">
          <h4>材料访问</h4>
          <el-button type="primary" plain @click="viewMaterial">生成签名地址并打开</el-button>
          <div class="legend">必须携带 ticketId（核验编号），签名地址 5 分钟有效。</div>
          <div v-if="materialUrl" class="material-url mono">{{ materialUrl }}</div>
          <div v-if="materialExpiresAt" class="legend">有效期至 {{ materialExpiresAt }}</div>
        </div>
      </template>

      <template #footer>
        <div class="drawer-footer">
          <el-button @click="drawerVisible = false">关闭</el-button>
          <el-button v-if="detail?.status === 'PENDING'" type="success" @click="approve(detail)">通过</el-button>
          <el-button v-if="detail?.status === 'PENDING'" type="danger" @click="openReject(detail)">驳回</el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="rejectState.visible" title="驳回核验" width="480px" destroy-on-close>
      <el-form label-width="84px" label-position="left">
        <el-form-item label="原因码" required>
          <el-select v-model="rejectState.reasonCode" placeholder="请选择原因码" style="width: 100%">
            <el-option
              v-for="item in rejectOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input
            v-model="rejectState.note"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="补充驳回说明（选填）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectState.visible = false">取消</el-button>
        <el-button type="danger" :loading="rejectState.submitting" @click="submitReject">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.material-url {
  margin-top: 10px;
  padding: 8px 10px;
  border: 1px solid var(--rule);
  background: #fafafa;
  border-radius: 4px;
  word-break: break-all;
  font-size: 11px;
}
</style>
