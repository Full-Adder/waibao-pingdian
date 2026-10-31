<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { companyApi } from '../api'
import type { CompanyCandidateItem, CompanySummary } from '../api/types'
import {
  CANDIDATE_STATUS_LABEL,
  COMPANY_SOURCE_LABEL,
  COMPANY_STATUS_LABEL,
  fmtDateTime,
  fmtScore,
  labelOf
} from '../utils/labels'

const tab = ref('companies')

const loading = ref(false)
const rows = ref<CompanySummary[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const filters = reactive({ keyword: '', status: '', city: '', industry: '' })
const statusOptions = Object.entries(COMPANY_STATUS_LABEL).map(([value, label]) => ({ value, label }))

const editState = reactive({
  visible: false,
  submitting: false,
  uscc: '',
  name: '',
  shortName: '',
  city: '',
  industry: '',
  companyType: '',
  status: 'ACTIVE',
  reason: '',
  note: '',
  ticketId: ''
})

const candidateLoading = ref(false)
const candidates = ref<CompanyCandidateItem[]>([])
const candidateTotal = ref(0)
const candidatePage = ref(1)
const candidateSize = ref(20)
const candidateStatus = ref('PENDING')
const candidateStatusOptions = Object.entries(CANDIDATE_STATUS_LABEL).map(([value, label]) => ({
  value,
  label
}))

const resolveState = reactive({
  visible: false,
  mode: 'match' as 'match' | 'create' | 'reject',
  submitting: false,
  candidate: null as CompanyCandidateItem | null,
  uscc: '',
  name: '',
  city: '',
  industry: '',
  note: '',
  ticketId: ''
})

const mergeState = reactive({
  sourceUscc: '',
  targetUscc: '',
  reason: '',
  ticketId: '',
  confirmed: false,
  submitting: false
})

const usccPattern = /^[0-9A-Z]{18}$/

const editTitle = computed(() => (editState.uscc ? `编辑公司 · ${editState.uscc}` : '编辑公司'))

async function loadCompanies() {
  loading.value = true
  try {
    const result = await companyApi.list({
      keyword: filters.keyword || undefined,
      status: filters.status || undefined,
      city: filters.city || undefined,
      industry: filters.industry || undefined,
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

function searchCompanies() {
  page.value = 1
  loadCompanies()
}

function resetCompanyFilters() {
  filters.keyword = ''
  filters.status = ''
  filters.city = ''
  filters.industry = ''
  page.value = 1
  loadCompanies()
}

function companyPageChange(value: number) {
  page.value = value
  loadCompanies()
}

function companySizeChange(value: number) {
  size.value = value
  page.value = 1
  loadCompanies()
}

async function loadCandidates() {
  candidateLoading.value = true
  try {
    const result = await companyApi.candidates({
      status: candidateStatus.value || undefined,
      page: candidatePage.value,
      size: candidateSize.value
    })
    candidates.value = result.items
    candidateTotal.value = result.total
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    candidateLoading.value = false
  }
}

function candidatePageChange(value: number) {
  candidatePage.value = value
  loadCandidates()
}

function candidateSizeChange(value: number) {
  candidateSize.value = value
  candidatePage.value = 1
  loadCandidates()
}

function onTabChange(value: string) {
  if (value === 'candidates' && !candidates.value.length) loadCandidates()
}

function openEdit(row: CompanySummary) {
  editState.visible = true
  editState.uscc = row.uscc
  editState.name = row.name
  editState.shortName = row.shortName ?? ''
  editState.city = row.city ?? ''
  editState.industry = row.industry ?? ''
  editState.companyType = row.companyType ?? ''
  editState.status = 'ACTIVE'
  editState.reason = ''
  editState.note = ''
  editState.ticketId = ''
}

async function submitEdit() {
  if (!editState.name.trim()) {
    ElMessage.warning('公司名称不能为空')
    return
  }
  editState.submitting = true
  try {
    await companyApi.update(editState.uscc, {
      name: editState.name.trim(),
      shortName: editState.shortName.trim(),
      city: editState.city.trim(),
      industry: editState.industry.trim(),
      companyType: editState.companyType.trim(),
      reason: editState.reason.trim() || undefined,
      note: editState.note.trim() || undefined,
      ticketId: editState.ticketId.trim() || undefined
    })
    ElMessage.success('公司档案已更新')
    editState.visible = false
    await loadCompanies()
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    editState.submitting = false
  }
}

function openResolve(candidate: CompanyCandidateItem, mode: 'match' | 'create' | 'reject') {
  resolveState.visible = true
  resolveState.mode = mode
  resolveState.candidate = candidate
  resolveState.uscc = candidate.matchedUscc ?? ''
  resolveState.name = candidate.name
  resolveState.city = candidate.city ?? ''
  resolveState.industry = candidate.industry ?? ''
  resolveState.note = ''
  resolveState.ticketId = ''
}

async function submitResolve() {
  if (!resolveState.candidate) return
  const id = resolveState.candidate.id
  if (resolveState.mode === 'match' && !usccPattern.test(resolveState.uscc.trim().toUpperCase())) {
    ElMessage.warning('USCC 必须为 18 位大写字母或数字')
    return
  }
  if (resolveState.mode === 'create' && !resolveState.name.trim()) {
    ElMessage.warning('补录时必须填写公司名称')
    return
  }
  if (resolveState.mode === 'reject' && !resolveState.note.trim()) {
    ElMessage.warning('驳回时必须填写原因')
    return
  }
  resolveState.submitting = true
  try {
    if (resolveState.mode === 'match') {
      await companyApi.matchCandidate(id, {
        uscc: resolveState.uscc.trim().toUpperCase(),
        note: resolveState.note.trim() || undefined,
        ticketId: resolveState.ticketId.trim() || undefined
      })
      ElMessage.success('候选已匹配到现有公司')
    } else if (resolveState.mode === 'create') {
      const uscc = await companyApi.createCandidate(id, {
        name: resolveState.name.trim(),
        city: resolveState.city.trim() || undefined,
        industry: resolveState.industry.trim() || undefined,
        note: resolveState.note.trim() || undefined,
        ticketId: resolveState.ticketId.trim() || undefined
      })
      ElMessage.success(`已补录公司：${uscc}`)
    } else {
      await companyApi.rejectCandidate(id, {
        note: resolveState.note.trim(),
        ticketId: resolveState.ticketId.trim() || undefined
      })
      ElMessage.success('已驳回该候选')
    }
    resolveState.visible = false
    await loadCandidates()
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    resolveState.submitting = false
  }
}

function normalizeUscc(value: string): string {
  return value.trim().toUpperCase()
}

async function submitMerge() {
  const source = normalizeUscc(mergeState.sourceUscc)
  const target = normalizeUscc(mergeState.targetUscc)
  if (!usccPattern.test(source) || !usccPattern.test(target)) {
    ElMessage.warning('源与目标 USCC 均须为 18 位大写字母或数字')
    return
  }
  if (source === target) {
    ElMessage.warning('源 USCC 与目标 USCC 不能相同')
    return
  }
  if (!mergeState.reason.trim()) {
    ElMessage.warning('请填写合并原因')
    return
  }
  if (!mergeState.confirmed) {
    ElMessage.warning('请勾选「已核对两家公司为重复主体」')
    return
  }
  try {
    await ElMessageBox.confirm(
      `确认将 ${source} 合并到 ${target}？合并后点评、核验与别名迁移至主档，旧 USCC 将 301 跳转，sitemap 剔除。`,
      '二次确认合并',
      { confirmButtonText: '确认合并', cancelButtonText: '取消', type: 'warning' }
    )
  } catch {
    return
  }
  mergeState.submitting = true
  try {
    await companyApi.merge({
      sourceUscc: source,
      targetUscc: target,
      reason: mergeState.reason.trim(),
      ticketId: mergeState.ticketId.trim() || undefined
    })
    ElMessage.success('公司合并完成')
    mergeState.sourceUscc = ''
    mergeState.targetUscc = ''
    mergeState.reason = ''
    mergeState.ticketId = ''
    mergeState.confirmed = false
    await loadCompanies()
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    mergeState.submitting = false
  }
}

onMounted(loadCompanies)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>公司管理</h2>
        <p>以 USCC 为锚维护公司档案；候选池处理用户上报，重复主体必须合并而非新建。</p>
      </div>
      <el-tag size="small" effect="plain">档案 {{ total }} 家</el-tag>
    </div>

    <el-tabs v-model="tab" class="company-tabs" @tab-change="onTabChange">
      <el-tab-pane label="公司档案" name="companies">
        <div class="panel">
          <div class="filter-bar">
            <el-input v-model="filters.keyword" clearable placeholder="搜索名称 / 简称 / USCC" @keyup.enter="searchCompanies" />
            <el-select v-model="filters.status" clearable placeholder="状态">
              <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-input v-model="filters.city" clearable placeholder="城市" style="width: 130px" />
            <el-input v-model="filters.industry" clearable placeholder="行业" style="width: 170px" />
            <el-button type="primary" :icon="Search" @click="searchCompanies">查询</el-button>
            <el-button :icon="Refresh" @click="resetCompanyFilters">重置</el-button>
          </div>
        </div>

        <div class="panel">
          <el-table v-loading="loading" :data="rows" size="small" border row-key="uscc">
            <el-table-column prop="uscc" label="USCC" width="190">
              <template #default="{ row }"><span class="mono">{{ row.uscc }}</span></template>
            </el-table-column>
            <el-table-column prop="name" label="公司名称" min-width="200" show-overflow-tooltip />
            <el-table-column prop="shortName" label="简称" width="120" show-overflow-tooltip />
            <el-table-column prop="city" label="城市" width="84" />
            <el-table-column prop="industry" label="行业" width="150" show-overflow-tooltip />
            <el-table-column label="来源" width="96">
              <template #default="{ row }">{{ labelOf(COMPANY_SOURCE_LABEL, row.source) }}</template>
            </el-table-column>
            <el-table-column label="评价" width="112">
              <template #default="{ row }">
                {{ row.reviewCount }} 条
                <div class="muted">
                  {{ row.scoreVisible ? `${fmtScore(row.avgScore)} 分` : '评价不足，暂不评分' }}
                </div>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
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
              @current-change="companyPageChange"
              @size-change="companySizeChange"
            />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="候选池" name="candidates">
        <div class="panel">
          <div class="filter-bar">
            <el-select v-model="candidateStatus" placeholder="候选状态" style="width: 150px" @change="loadCandidates">
              <el-option v-for="item in candidateStatusOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
            <el-button :icon="Refresh" @click="loadCandidates">刷新</el-button>
            <span class="grow" />
            <span class="muted">用户上报未命中的公司不会直接建档，须匹配或补录后生效。</span>
          </div>
        </div>

        <div class="panel">
          <el-table v-loading="candidateLoading" :data="candidates" size="small" border row-key="id">
            <el-table-column label="上报时间" width="140">
              <template #default="{ row }">{{ fmtDateTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column prop="name" label="候选名称" min-width="190" show-overflow-tooltip />
            <el-table-column prop="city" label="城市" width="84" />
            <el-table-column prop="industry" label="行业" width="140" show-overflow-tooltip />
            <el-table-column label="上报用户" width="140">
              <template #default="{ row }"><span class="mono">{{ row.reporterUserId || '—' }}</span></template>
            </el-table-column>
            <el-table-column label="匹配 USCC" width="190">
              <template #default="{ row }"><span class="mono">{{ row.matchedUscc || '—' }}</span></template>
            </el-table-column>
            <el-table-column label="状态" width="86">
              <template #default="{ row }">{{ labelOf(CANDIDATE_STATUS_LABEL, row.status) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="200" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" :disabled="row.status !== 'PENDING'" @click="openResolve(row, 'match')">
                  匹配
                </el-button>
                <el-button link type="success" :disabled="row.status !== 'PENDING'" @click="openResolve(row, 'create')">
                  补录
                </el-button>
                <el-button link type="danger" :disabled="row.status !== 'PENDING'" @click="openResolve(row, 'reject')">
                  驳回
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <div class="pager">
            <el-pagination
              background
              layout="total, sizes, prev, pager, next"
              :total="candidateTotal"
              :page-size="candidateSize"
              :current-page="candidatePage"
              :page-sizes="[10, 20, 50, 100]"
              @current-change="candidatePageChange"
              @size-change="candidateSizeChange"
            />
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="合并工具" name="merge">
        <div class="panel merge-panel">
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            title="合并不可撤销"
            description="合并会把源公司的点评、核验与别名迁移到目标主档，源 USCC 写入 301 跳转并从 sitemap 剔除。请先确认两家公司确为同一主体。"
            style="margin-bottom: 16px"
          />
          <el-form label-width="110px" label-position="left" style="max-width: 620px">
            <el-form-item label="源 USCC" required>
              <el-input
                v-model="mergeState.sourceUscc"
                maxlength="18"
                placeholder="18 位统一社会信用代码"
                class="mono"
                @input="mergeState.sourceUscc = normalizeUscc(mergeState.sourceUscc)"
              />
            </el-form-item>
            <el-form-item label="目标 USCC" required>
              <el-input
                v-model="mergeState.targetUscc"
                maxlength="18"
                placeholder="18 位统一社会信用代码"
                class="mono"
                @input="mergeState.targetUscc = normalizeUscc(mergeState.targetUscc)"
              />
            </el-form-item>
            <el-form-item label="合并原因" required>
              <el-input
                v-model="mergeState.reason"
                type="textarea"
                :rows="3"
                maxlength="500"
                show-word-limit
                placeholder="记录重复主体的判断依据"
              />
            </el-form-item>
            <el-form-item label="关联工单">
              <el-input v-model="mergeState.ticketId" maxlength="64" placeholder="选填，投诉或审核工单号" />
            </el-form-item>
            <el-form-item>
              <el-checkbox v-model="mergeState.confirmed">已核对两家公司为重复主体</el-checkbox>
            </el-form-item>
            <el-form-item>
              <el-button type="danger" :loading="mergeState.submitting" @click="submitMerge">执行合并</el-button>
            </el-form-item>
          </el-form>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="editState.visible" :title="editTitle" width="520px" destroy-on-close>
      <el-form label-width="86px" label-position="left">
        <el-form-item label="USCC">
          <el-input :model-value="editState.uscc" disabled class="mono" />
        </el-form-item>
        <el-form-item label="公司名称" required>
          <el-input v-model="editState.name" maxlength="255" />
        </el-form-item>
        <el-form-item label="公司简称">
          <el-input v-model="editState.shortName" maxlength="255" />
        </el-form-item>
        <el-form-item label="城市">
          <el-input v-model="editState.city" maxlength="64" />
        </el-form-item>
        <el-form-item label="行业">
          <el-input v-model="editState.industry" maxlength="64" />
        </el-form-item>
        <el-form-item label="公司类型">
          <el-input v-model="editState.companyType" maxlength="64" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="editState.status" style="width: 180px" disabled>
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <span class="legend">档案状态不在列表返回中，需以专用流程变更。</span>
        </el-form-item>
        <el-form-item label="变更原因">
          <el-input v-model="editState.reason" maxlength="40" placeholder="选填，写入审计" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editState.note" type="textarea" :rows="2" maxlength="500" placeholder="选填" />
        </el-form-item>
        <el-form-item label="关联工单">
          <el-input v-model="editState.ticketId" maxlength="64" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editState.visible = false">取消</el-button>
        <el-button type="primary" :loading="editState.submitting" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="resolveState.visible"
      :title="resolveState.mode === 'match' ? '匹配现有公司' : resolveState.mode === 'create' ? '补录公司' : '驳回候选'"
      width="520px"
      destroy-on-close
    >
      <el-form label-width="86px" label-position="left">
        <el-form-item v-if="resolveState.mode === 'match'" label="目标 USCC" required>
          <el-input
            v-model="resolveState.uscc"
            maxlength="18"
            class="mono"
            placeholder="已存在的公司 USCC"
            @input="resolveState.uscc = normalizeUscc(resolveState.uscc)"
          />
        </el-form-item>
        <template v-if="resolveState.mode === 'create'">
          <el-form-item label="公司名称" required>
            <el-input v-model="resolveState.name" maxlength="255" />
          </el-form-item>
          <el-form-item label="城市">
            <el-input v-model="resolveState.city" maxlength="64" />
          </el-form-item>
          <el-form-item label="行业">
            <el-input v-model="resolveState.industry" maxlength="64" />
          </el-form-item>
        </template>
        <el-form-item :label="resolveState.mode === 'reject' ? '驳回原因' : '备注'" :required="resolveState.mode === 'reject'">
          <el-input
            v-model="resolveState.note"
            type="textarea"
            :rows="3"
            maxlength="500"
            :placeholder="resolveState.mode === 'reject' ? '说明为何不建档' : '选填'"
          />
        </el-form-item>
        <el-form-item label="关联工单">
          <el-input v-model="resolveState.ticketId" maxlength="64" placeholder="选填" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveState.visible = false">取消</el-button>
        <el-button
          :type="resolveState.mode === 'reject' ? 'danger' : 'primary'"
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
.company-tabs :deep(.el-tabs__header) {
  margin-bottom: 14px;
}

.merge-panel {
  max-width: 820px;
}
</style>
