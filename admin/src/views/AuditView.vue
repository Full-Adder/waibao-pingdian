<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { auditApi } from '../api'
import type { AuditLogItem } from '../api/types'
import { ROLE_LABEL, fmtDateTime, labelOf } from '../utils/labels'

const loading = ref(false)
const rows = ref<AuditLogItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const filters = reactive({
  actorId: '',
  action: '',
  targetType: '',
  targetId: ''
})

const dateRange = ref<[string, string] | null>(null)

const drawerVisible = ref(false)
const current = ref<AuditLogItem | null>(null)

async function load() {
  loading.value = true
  try {
    const result = await auditApi.list({
      actorId: filters.actorId.trim() || undefined,
      action: filters.action.trim() || undefined,
      targetType: filters.targetType.trim() || undefined,
      targetId: filters.targetId.trim() || undefined,
      from: dateRange.value?.[0],
      to: dateRange.value?.[1],
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
  filters.actorId = ''
  filters.action = ''
  filters.targetType = ''
  filters.targetId = ''
  dateRange.value = null
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

function openDetail(row: AuditLogItem) {
  current.value = row
  drawerVisible.value = true
}

function prettyDetail(detail?: string): string {
  if (!detail) return '—'
  try {
    return JSON.stringify(JSON.parse(detail), null, 2)
  } catch {
    return detail
  }
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>审计日志</h2>
        <p>仅超级管理员可查询；所有后台写操作与材料读取都会留痕，不提供修改与删除。</p>
      </div>
      <el-tag size="small" type="danger" effect="plain">只读</el-tag>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="filters.actorId" clearable placeholder="操作人 ID" style="width: 220px" class="mono" />
        <el-input v-model="filters.action" clearable placeholder="动作前缀，如 REVIEW_" style="width: 170px" />
        <el-input v-model="filters.targetType" clearable placeholder="对象类型，如 REVIEW" style="width: 170px" />
        <el-input v-model="filters.targetId" clearable placeholder="对象 ID" style="width: 220px" class="mono" />
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 250px"
        />
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
      </div>
    </div>

    <div class="panel">
      <el-table v-loading="loading" :data="rows" size="small" border row-key="id">
        <el-table-column label="时间" width="140">
          <template #default="{ row }">{{ fmtDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="190">
          <template #default="{ row }">
            <div class="mono">{{ row.actorId || 'SYSTEM' }}</div>
            <div class="muted">{{ labelOf(ROLE_LABEL, row.actorRole) }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="action" label="动作" width="180" show-overflow-tooltip />
        <el-table-column label="对象" min-width="210">
          <template #default="{ row }">
            {{ row.targetType || '—' }}
            <div class="mono muted">{{ row.targetId || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="原因码" width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ row.reasonCode || '—' }}</template>
        </el-table-column>
        <el-table-column label="IP" width="120">
          <template #default="{ row }"><span class="mono">{{ row.ip || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="工单" width="150" show-overflow-tooltip>
          <template #default="{ row }"><span class="mono">{{ row.ticketId || '—' }}</span></template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
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

    <el-drawer v-model="drawerVisible" size="560px" title="审计日志详情">
      <template v-if="current">
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="日志 ID">
            <span class="mono">{{ current.id }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="操作人">
            <span class="mono">{{ current.actorId || 'SYSTEM' }}</span>
            · {{ labelOf(ROLE_LABEL, current.actorRole) }}
          </el-descriptions-item>
          <el-descriptions-item label="动作">{{ current.action }}</el-descriptions-item>
          <el-descriptions-item label="对象">
            {{ current.targetType || '—' }} / <span class="mono">{{ current.targetId || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="原因码">{{ current.reasonCode || '—' }}</el-descriptions-item>
          <el-descriptions-item label="工单号">{{ current.ticketId || '—' }}</el-descriptions-item>
          <el-descriptions-item label="IP">{{ current.ip || '—' }}</el-descriptions-item>
          <el-descriptions-item label="时间">{{ fmtDateTime(current.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="前哈希">
            <span class="mono break">{{ current.beforeHash || '—' }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="后哈希">
            <span class="mono break">{{ current.afterHash || '—' }}</span>
          </el-descriptions-item>
        </el-descriptions>
        <div class="detail-block" style="margin-top: 16px">
          <h4>详情</h4>
          <pre class="detail-pre">{{ prettyDetail(current.detail) }}</pre>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.break {
  word-break: break-all;
}

.detail-pre {
  margin: 0;
  padding: 12px;
  background: #fafafa;
  border: 1px solid var(--rule);
  border-radius: 4px;
  font-family: var(--font-mono);
  font-size: 11.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 360px;
  overflow: auto;
}
</style>
