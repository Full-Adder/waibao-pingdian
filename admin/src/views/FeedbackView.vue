<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { feedbackApi } from '../api'
import type { FeedbackItemView } from '../api/types'
import {
  FEEDBACK_CATEGORY_LABEL,
  FEEDBACK_STATUS_LABEL,
  fmtDateTime,
  labelOf
} from '../utils/labels'

const loading = ref(false)
const rows = ref<FeedbackItemView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const filters = reactive({ status: '' })
const statusOptions = Object.entries(FEEDBACK_STATUS_LABEL).map(([value, label]) => ({ value, label }))

const drawerVisible = ref(false)
const detail = ref<FeedbackItemView | null>(null)

const replyState = reactive({
  visible: false,
  responseNote: '',
  close: false,
  submitting: false,
  target: null as FeedbackItemView | null
})

async function load() {
  loading.value = true
  try {
    const result = await feedbackApi.list({
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

function openDetail(row: FeedbackItemView) {
  detail.value = row
  drawerVisible.value = true
}

function openReply(row: FeedbackItemView | null) {
  if (!row) return
  replyState.visible = true
  replyState.responseNote = row.responseNote ?? ''
  replyState.close = row.status === 'CLOSED'
  replyState.target = row
}

async function submitReply() {
  if (!replyState.target) return
  if (replyState.responseNote.trim().length < 5) {
    ElMessage.warning('回复内容至少 5 个字')
    return
  }
  replyState.submitting = true
  try {
    await feedbackApi.reply(replyState.target.id, replyState.responseNote.trim(), replyState.close)
    ElMessage.success(replyState.close ? '已回复并关闭工单' : '已回复该反馈')
    replyState.visible = false
    drawerVisible.value = false
    await load()
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    replyState.submitting = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>帮助反馈</h2>
        <p>处理账号、核验、申诉受阻等求助工单；联系方式仅展示掩码。</p>
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
        <el-table-column label="分类" width="110">
          <template #default="{ row }">{{ labelOf(FEEDBACK_CATEGORY_LABEL, row.category) }}</template>
        </el-table-column>
        <el-table-column prop="contactMasked" label="联系方式" width="140">
          <template #default="{ row }"><span class="mono">{{ row.contactMasked || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="内容" min-width="320">
          <template #default="{ row }">
            <div class="clamp-2">{{ row.content }}</div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 'PENDING' ? 'warning' : row.status === 'REPLIED' ? 'success' : 'info'"
            >
              {{ labelOf(FEEDBACK_STATUS_LABEL, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="success" :disabled="row.status === 'CLOSED'" @click="openReply(row)">
              {{ row.status === 'REPLIED' ? '追加回复' : '回复' }}
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

    <el-drawer v-model="drawerVisible" size="540px" title="反馈工单详情">
      <template v-if="detail">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="工单编号">
            <span class="mono">{{ detail.id }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="分类">{{ labelOf(FEEDBACK_CATEGORY_LABEL, detail.category) }}</el-descriptions-item>
          <el-descriptions-item label="联系方式">{{ detail.contactMasked || '—' }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ labelOf(FEEDBACK_STATUS_LABEL, detail.status) }}</el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ fmtDateTime(detail.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="回复时间">{{ fmtDateTime(detail.respondedAt) }}</el-descriptions-item>
        </el-descriptions>

        <div class="detail-block" style="margin-top: 16px">
          <h4>用户反馈</h4>
          <div class="content-box">{{ detail.content }}</div>
        </div>

        <div class="detail-block">
          <h4>回复记录</h4>
          <div v-if="detail.responseNote" class="content-box">{{ detail.responseNote }}</div>
          <div v-else class="muted">尚未回复</div>
          <div v-if="detail.respondedBy" class="kv-note">处理人 {{ detail.respondedBy }}</div>
        </div>
      </template>
      <template #footer>
        <div class="drawer-footer">
          <el-button @click="drawerVisible = false">关闭</el-button>
          <el-button v-if="detail?.status !== 'CLOSED'" type="primary" @click="openReply(detail)">回复</el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="replyState.visible" title="回复反馈" width="500px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item label="回复内容（5 ~ 1000 字）" required>
          <el-input
            v-model="replyState.responseNote"
            type="textarea"
            :rows="5"
            maxlength="1000"
            show-word-limit
            placeholder="说明处理结果或下一步指引"
          />
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="replyState.close">回复后关闭工单</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="replyState.visible = false">取消</el-button>
        <el-button type="primary" :loading="replyState.submitting" @click="submitReply">提交回复</el-button>
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
