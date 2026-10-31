<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { reportApi } from '../api'
import type { ReportItemView } from '../api/types'
import {
  REPORT_REASON_LABEL,
  REPORT_STATUS_LABEL,
  REPORT_TARGET_LABEL,
  fmtDateTime,
  labelOf
} from '../utils/labels'

const loading = ref(false)
const rows = ref<ReportItemView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const filters = reactive({ status: 'PENDING' })
const statusOptions = Object.entries(REPORT_STATUS_LABEL).map(([value, label]) => ({ value, label }))

const drawerVisible = ref(false)
const detail = ref<ReportItemView | null>(null)

const resolveState = reactive({
  visible: false,
  result: 'VALID',
  note: '',
  submitting: false,
  target: null as ReportItemView | null
})

async function load() {
  loading.value = true
  try {
    const result = await reportApi.list({
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

function openDetail(row: ReportItemView) {
  detail.value = row
  drawerVisible.value = true
}

function openResolve(row: ReportItemView | null, result: 'VALID' | 'INVALID') {
  if (!row) return
  resolveState.visible = true
  resolveState.result = result
  resolveState.note = ''
  resolveState.target = row
}

async function submitResolve() {
  if (!resolveState.target) return
  if (resolveState.result === 'VALID') {
    try {
      await ElMessageBox.confirm(
        '标记为有效举报后，举报人将获得 +20 贡献值（幂等）。确认继续？',
        '确认有效举报',
        { confirmButtonText: '确认有效', cancelButtonText: '取消', type: 'success' }
      )
    } catch {
      return
    }
  }
  resolveState.submitting = true
  try {
    await reportApi.resolve(resolveState.target.id, resolveState.result, resolveState.note.trim() || undefined)
    ElMessage.success(resolveState.result === 'VALID' ? '已标记为有效举报' : '已标记为无效举报')
    resolveState.visible = false
    drawerVisible.value = false
    await load()
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    resolveState.submitting = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>举报处置</h2>
        <p>支持举报点评与公司；同一对象被不同账号举报达阈值后自动进入人工审核。</p>
      </div>
      <el-tag size="small" effect="plain">共 {{ total }} 条</el-tag>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-select v-model="filters.status" clearable placeholder="处置状态">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
      </div>
    </div>

    <div class="panel">
      <el-table v-loading="loading" :data="rows" size="small" border row-key="id">
        <el-table-column label="举报时间" width="140">
          <template #default="{ row }">{{ fmtDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="对象" width="150">
          <template #default="{ row }">
            <el-tag size="small" effect="plain">{{ labelOf(REPORT_TARGET_LABEL, row.targetType) }}</el-tag>
            <div class="mono muted" style="margin-top: 2px">{{ row.targetId }}</div>
          </template>
        </el-table-column>
        <el-table-column label="理由" width="130">
          <template #default="{ row }">{{ labelOf(REPORT_REASON_LABEL, row.reasonCode) }}</template>
        </el-table-column>
        <el-table-column label="说明" min-width="240">
          <template #default="{ row }">
            <div class="clamp-2">{{ row.detail || '—' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="阈值" width="96">
          <template #default="{ row }">
            <el-tag v-if="row.thresholdReached" size="small" type="danger" effect="plain">已达阈值</el-tag>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="92">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 'PENDING' ? 'warning' : row.status === 'VALID' ? 'success' : 'info'"
            >
              {{ labelOf(REPORT_STATUS_LABEL, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="success" :disabled="row.status !== 'PENDING'" @click="openResolve(row, 'VALID')">
              有效
            </el-button>
            <el-button link type="danger" :disabled="row.status !== 'PENDING'" @click="openResolve(row, 'INVALID')">
              无效
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

    <el-drawer v-model="drawerVisible" size="520px" title="举报详情">
      <template v-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="举报编号">
            <span class="mono">{{ detail.id }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="对象类型">{{ labelOf(REPORT_TARGET_LABEL, detail.targetType) }}</el-descriptions-item>
          <el-descriptions-item label="对象编号">
            <span class="mono">{{ detail.targetId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="理由">{{ labelOf(REPORT_REASON_LABEL, detail.reasonCode) }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ labelOf(REPORT_STATUS_LABEL, detail.status) }}</el-descriptions-item>
          <el-descriptions-item label="阈值">
            {{ detail.thresholdReached ? '已达人工审核阈值' : '未达阈值' }}
          </el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ fmtDateTime(detail.createdAt) }}</el-descriptions-item>
        </el-descriptions>
        <div class="detail-block" style="margin-top: 16px">
          <h4>举报说明</h4>
          <div class="content-box">{{ detail.detail || '—' }}</div>
        </div>
      </template>
      <template #footer>
        <div class="drawer-footer">
          <el-button @click="drawerVisible = false">关闭</el-button>
          <el-button v-if="detail?.status === 'PENDING'" type="success" @click="openResolve(detail, 'VALID')">有效</el-button>
          <el-button v-if="detail?.status === 'PENDING'" type="danger" @click="openResolve(detail, 'INVALID')">无效</el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="resolveState.visible" :title="resolveState.result === 'VALID' ? '标记为有效举报' : '标记为无效举报'" width="460px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="处置备注">
          <el-input v-model="resolveState.note" type="textarea" :rows="3" maxlength="500" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveState.visible = false">取消</el-button>
        <el-button
          :type="resolveState.result === 'VALID' ? 'success' : 'danger'"
          :loading="resolveState.submitting"
          @click="submitResolve"
        >
          确认
        </el-button>
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
</style>
