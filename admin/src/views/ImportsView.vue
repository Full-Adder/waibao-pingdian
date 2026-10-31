<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, VideoPlay } from '@element-plus/icons-vue'
import { importApi } from '../api'
import type { ImportJobView } from '../api/types'
import {
  IMPORT_STATUS_LABEL,
  IMPORT_TRIGGER_LABEL,
  fmtDateTime,
  labelOf
} from '../utils/labels'

const loading = ref(false)
const rows = ref<ImportJobView[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)

const createState = reactive({
  city: '',
  industry: '',
  triggerType: 'MANUAL',
  submitting: false
})

const cityOptions = ['上海', '深圳', '杭州', '北京', '广州', '成都']
const industryOptions = ['IT 人力外包（驻场派遣）', 'IT 项目外包']

const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<ImportJobView | null>(null)

async function load() {
  loading.value = true
  try {
    const result = await importApi.list({ page: page.value, size: size.value })
    rows.value = result.items
    total.value = result.total
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    loading.value = false
  }
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

async function triggerImport() {
  if (!createState.city.trim() || !createState.industry.trim()) {
    ElMessage.warning('请填写城市与行业')
    return
  }
  createState.submitting = true
  try {
    const job = await importApi.create({
      city: createState.city.trim(),
      industry: createState.industry.trim(),
      triggerType: createState.triggerType
    })
    ElMessage.success(`导入任务已创建：${job.city} / ${job.industry}`)
    await load()
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    createState.submitting = false
  }
}

async function openDetail(row: ImportJobView) {
  drawerVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await importApi.detail(row.id)
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    detailLoading.value = false
  }
}

async function rerun(row: ImportJobView) {
  try {
    await ElMessageBox.confirm(
      `将以「${row.city} / ${row.industry}」重新创建手动导入任务，原任务记录保留。确认重跑？`,
      '重跑导入',
      { confirmButtonText: '确认重跑', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  try {
    await importApi.create({ city: row.city, industry: row.industry, triggerType: 'MANUAL' })
    ElMessage.success('已创建重跑任务')
    await load()
  } catch {
    // 错误提示由请求层统一展示
  }
}

function failureList(job: ImportJobView | null): Array<{ uscc?: string; name?: string; attempts?: string; error?: string }> {
  return job?.detail?.failures ?? []
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>导入任务</h2>
        <p>按城市 + 行业触发批量导入，失败明细可在详情中查看并重跑。</p>
      </div>
      <el-tag size="small" effect="plain">共 {{ total }} 个任务</el-tag>
    </div>

    <div class="panel">
      <div class="table-title"><strong>触发导入</strong></div>
      <div class="filter-bar">
        <el-select v-model="createState.city" filterable allow-create clearable placeholder="城市">
          <el-option v-for="city in cityOptions" :key="city" :label="city" :value="city" />
        </el-select>
        <el-select v-model="createState.industry" filterable allow-create clearable placeholder="行业">
          <el-option v-for="industry in industryOptions" :key="industry" :label="industry" :value="industry" />
        </el-select>
        <el-select v-model="createState.triggerType" style="width: 130px">
          <el-option label="手动触发" value="MANUAL" />
          <el-option label="定时任务" value="SCHEDULE" />
        </el-select>
        <el-button type="primary" :icon="VideoPlay" :loading="createState.submitting" @click="triggerImport">
          创建导入任务
        </el-button>
        <span class="muted">任务异步执行，创建后自动刷新列表。</span>
      </div>
    </div>

    <div class="panel">
      <div class="table-title">
        <strong>任务列表</strong>
        <el-button size="small" :icon="Refresh" @click="load">刷新</el-button>
      </div>
      <el-table v-loading="loading" :data="rows" size="small" border row-key="id">
        <el-table-column label="创建时间" width="140">
          <template #default="{ row }">{{ fmtDateTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column prop="city" label="城市" width="88" />
        <el-table-column prop="industry" label="行业" min-width="170" show-overflow-tooltip />
        <el-table-column label="触发" width="96">
          <template #default="{ row }">{{ labelOf(IMPORT_TRIGGER_LABEL, row.triggerType) }}</template>
        </el-table-column>
        <el-table-column label="进度 / 结果" width="170">
          <template #default="{ row }">
            总数 {{ row.total }} · 成功 {{ row.upserted }} · 失败 {{ row.failed }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <el-tag
              size="small"
              :type="row.status === 'SUCCESS' ? 'success' : row.status === 'RUNNING' ? 'warning' : row.status === 'PARTIAL' ? 'warning' : 'danger'"
            >
              {{ labelOf(IMPORT_STATUS_LABEL, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="完成时间" width="140">
          <template #default="{ row }">{{ fmtDateTime(row.finishedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="warning" :disabled="row.status === 'RUNNING'" @click="rerun(row)">重跑</el-button>
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

    <el-drawer v-model="drawerVisible" size="640px" title="导入任务详情">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="任务编号">
              <span class="mono">{{ detail.id }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="触发方式">
              {{ labelOf(IMPORT_TRIGGER_LABEL, detail.triggerType) }}
            </el-descriptions-item>
            <el-descriptions-item label="城市">{{ detail.city }}</el-descriptions-item>
            <el-descriptions-item label="行业">{{ detail.industry }}</el-descriptions-item>
            <el-descriptions-item label="总数">{{ detail.total }}</el-descriptions-item>
            <el-descriptions-item label="成功 / 失败">{{ detail.upserted }} / {{ detail.failed }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              {{ labelOf(IMPORT_STATUS_LABEL, detail.status) }}
            </el-descriptions-item>
            <el-descriptions-item label="页数">{{ detail.detail?.pages ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="开始时间">{{ fmtDateTime(detail.startedAt) }}</el-descriptions-item>
            <el-descriptions-item label="结束时间">{{ fmtDateTime(detail.finishedAt) }}</el-descriptions-item>
          </el-descriptions>

          <div v-if="detail.detail?.error" class="detail-block" style="margin-top: 16px">
            <h4>任务错误</h4>
            <el-alert type="error" :closable="false" :title="detail.detail.error" />
          </div>

          <div class="detail-block" style="margin-top: 16px">
            <h4>失败明细（{{ failureList(detail).length }} 条）</h4>
            <el-table v-if="failureList(detail).length" :data="failureList(detail)" size="small" border>
              <el-table-column prop="name" label="公司" min-width="160" show-overflow-tooltip />
              <el-table-column prop="uscc" label="USCC" width="180">
                <template #default="{ row }"><span class="mono">{{ row.uscc }}</span></template>
              </el-table-column>
              <el-table-column prop="attempts" label="尝试" width="64" />
              <el-table-column prop="error" label="错误" min-width="200" show-overflow-tooltip />
            </el-table>
            <div v-else class="muted">本次任务无失败记录</div>
          </div>

          <div class="detail-block">
            <el-button type="warning" plain @click="rerun(detail)">重跑该任务</el-button>
            <div class="kv-note">重跑会以相同城市与行业创建新的手动任务。</div>
          </div>
        </template>
        <el-empty v-else-if="!detailLoading" description="未获取到任务详情" />
      </div>
    </el-drawer>
  </div>
</template>
