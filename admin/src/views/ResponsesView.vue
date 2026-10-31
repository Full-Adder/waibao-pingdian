<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { responseApi } from '../api'
import type { AdminResponseItem } from '../api/types'
import { RESPONSE_STATUS_LABEL, fmtDateTime, labelOf } from '../utils/labels'

const loading = ref(false)
const rows = ref<AdminResponseItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const filters = reactive({ status: 'PENDING' })
const statusOptions = Object.entries(RESPONSE_STATUS_LABEL).map(([value, label]) => ({ value, label }))

const drawerVisible = ref(false)
const detail = ref<AdminResponseItem | null>(null)

const rejectState = reactive({
  visible: false,
  reason: '',
  note: '',
  submitting: false,
  target: null as AdminResponseItem | null
})

async function load() {
  loading.value = true
  try {
    const result = await responseApi.list({
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

function openDetail(row: AdminResponseItem) {
  detail.value = row
  drawerVisible.value = true
}

async function approve(row: AdminResponseItem) {
  try {
    await ElMessageBox.confirm('通过后企业回应将展示在该点评下方，标注「企业回应」，不计分、不改状态。', '通过企业回应', {
      confirmButtonText: '确认通过',
      cancelButtonText: '取消',
      type: 'success'
    })
  } catch {
    return
  }
  try {
    await responseApi.approve(row.id)
    ElMessage.success('已通过该企业回应')
    drawerVisible.value = false
    await load()
  } catch {
    // 错误提示由请求层统一展示
  }
}

function openReject(row: AdminResponseItem | null) {
  if (!row) return
  rejectState.visible = true
  rejectState.reason = ''
  rejectState.note = ''
  rejectState.target = row
}

async function submitReject() {
  if (!rejectState.target) return
  if (!rejectState.reason.trim()) {
    ElMessage.warning('请填写驳回原因')
    return
  }
  rejectState.submitting = true
  try {
    await responseApi.reject(rejectState.target.id, rejectState.reason.trim(), rejectState.note.trim())
    ElMessage.success('已驳回该企业回应')
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
        <h2>企业回应</h2>
        <p>经核验企业凭投诉工单提交，≤500 字并过滤联系方式；审核通过后展示在该点评下方。</p>
      </div>
      <el-tag size="small" effect="plain">共 {{ total }} 条</el-tag>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-select v-model="filters.status" clearable placeholder="审核状态">
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
        <el-table-column label="公司 / USCC" min-width="170">
          <template #default="{ row }">
            <div class="mono">{{ row.uscc || '—' }}</div>
            <div class="muted mono">{{ row.reviewId }}</div>
          </template>
        </el-table-column>
        <el-table-column label="回应内容" min-width="300">
          <template #default="{ row }">
            <div class="clamp-2">{{ row.content }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 'PENDING' ? 'warning' : row.status === 'APPROVED' ? 'success' : 'info'"
            >
              {{ labelOf(RESPONSE_STATUS_LABEL, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="170" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="success" :disabled="row.status !== 'PENDING'" @click="approve(row)">通过</el-button>
            <el-button link type="danger" :disabled="row.status !== 'PENDING'" @click="openReject(row)">驳回</el-button>
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

    <el-drawer v-model="drawerVisible" size="520px" title="企业回应详情">
      <template v-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="回应编号">
            <span class="mono">{{ detail.id }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="关联点评">
            <span class="mono">{{ detail.reviewId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="关联投诉">
            <span class="mono">{{ detail.complaintId || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ labelOf(RESPONSE_STATUS_LABEL, detail.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ fmtDateTime(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="审核时间">{{ fmtDateTime(detail.reviewedAt) }}</el-descriptions-item>
        </el-descriptions>
        <div class="detail-block" style="margin-top: 16px">
          <h4>回应正文</h4>
          <div class="content-box">{{ detail.content }}</div>
          <div class="kv-note">企业不可自行删除；审核通过后仅在该点评下展示。</div>
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

    <el-dialog v-model="rejectState.visible" title="驳回企业回应" width="480px" destroy-on-close>
      <el-form label-width="72px" label-position="left">
        <el-form-item label="原因" required>
          <el-input v-model="rejectState.reason" maxlength="200" placeholder="例如：含联系方式、内容失实" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="rejectState.note" type="textarea" :rows="3" maxlength="500" placeholder="选填" />
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
</style>
