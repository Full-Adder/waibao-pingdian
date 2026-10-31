<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { isSuperAdmin } from '../auth'
import { userApi } from '../api'
import type { AdminUserDetail, AdminUserListItem } from '../api/types'
import {
  PROOF_TYPE_LABEL,
  REALNAME_STATUS_LABEL,
  REVIEW_STATUS_LABEL,
  USER_STATUS_LABEL,
  VERIFICATION_STATUS_LABEL,
  fmtDateTime,
  fmtDate,
  labelOf
} from '../utils/labels'

const loading = ref(false)
const rows = ref<AdminUserListItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const filters = reactive({ keyword: '', status: '' })
const statusOptions = Object.entries(USER_STATUS_LABEL).map(([value, label]) => ({ value, label }))

const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref<AdminUserDetail | null>(null)

const banState = reactive({
  visible: false,
  reason: '',
  submitting: false,
  target: null as AdminUserDetail | AdminUserListItem | null
})

const contributionState = reactive({
  visible: false,
  delta: 0,
  reason: '',
  refId: '',
  submitting: false,
  target: null as AdminUserDetail | AdminUserListItem | null
})

const banActionText = computed(() => {
  const target = banState.target
  return target && target.status === 'BANNED' ? '解封' : '封禁'
})

async function load() {
  loading.value = true
  try {
    const result = await userApi.list({
      keyword: filters.keyword.trim() || undefined,
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
  filters.keyword = ''
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

async function openDetail(row: AdminUserListItem) {
  drawerVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await userApi.detail(row.id)
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    detailLoading.value = false
  }
}

function openBan(target: AdminUserDetail | AdminUserListItem) {
  banState.target = target
  banState.reason = ''
  banState.visible = true
}

async function submitBan() {
  if (!banState.target) return
  if (!banState.reason.trim()) {
    ElMessage.warning('请填写封禁 / 解封原因')
    return
  }
  banState.submitting = true
  try {
    await userApi.ban(banState.target.id, banState.target.status !== 'BANNED', banState.reason.trim())
    ElMessage.success(banState.target.status === 'BANNED' ? '已解封该用户' : '已封禁该用户')
    banState.visible = false
    await load()
    if (detail.value?.id === banState.target.id) {
      detail.value = await userApi.detail(banState.target.id)
    }
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    banState.submitting = false
  }
}

function openContribution(target: AdminUserDetail | AdminUserListItem) {
  contributionState.target = target
  contributionState.delta = 0
  contributionState.reason = ''
  contributionState.refId = ''
  contributionState.visible = true
}

async function submitContribution() {
  if (!contributionState.target) return
  if (!Number.isInteger(contributionState.delta) || contributionState.delta === 0) {
    ElMessage.warning('请输入非零整数调整值（-1000 ~ 1000）')
    return
  }
  if (!contributionState.reason.trim()) {
    ElMessage.warning('请填写调整原因')
    return
  }
  contributionState.submitting = true
  try {
    await userApi.adjustContribution(
      contributionState.target.id,
      contributionState.delta,
      contributionState.reason.trim(),
      contributionState.refId.trim() || undefined
    )
    ElMessage.success('贡献值已调整')
    contributionState.visible = false
    await load()
    if (detail.value?.id === contributionState.target.id) {
      detail.value = await userApi.detail(contributionState.target.id)
    }
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    contributionState.submitting = false
  }
}

function phoneText(row: { phoneMasked?: string; phone?: string }): string {
  return row.phoneMasked || row.phone || '—'
}

onMounted(load)
</script>

<template>
  <div class="page">
    <div class="page-head">
      <div>
        <h2>用户管理</h2>
        <p>手机号或匿名别名搜索；封禁、解封与贡献值调整仅超级管理员可操作，全部留痕。</p>
      </div>
      <el-tag size="small" :type="isSuperAdmin() ? 'success' : 'info'" effect="plain">
        {{ isSuperAdmin() ? '超级管理员模式' : '只读模式' }}
      </el-tag>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-input v-model="filters.keyword" clearable placeholder="手机号 / 匿名别名" style="width: 220px" @keyup.enter="search" />
        <el-select v-model="filters.status" clearable placeholder="用户状态">
          <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="search">查询</el-button>
        <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
        <span class="grow" />
        <span class="muted">手机号仅展示掩码。</span>
      </div>
    </div>

    <div class="panel">
      <el-table v-loading="loading" :data="rows" size="small" border row-key="id">
        <el-table-column label="用户" min-width="150">
          <template #default="{ row }">
            <div>{{ row.alias || '匿名' }}</div>
            <div class="muted mono">{{ phoneText(row) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="实名" width="100">
          <template #default="{ row }">{{ labelOf(REALNAME_STATUS_LABEL, row.realnameStatus) }}</template>
        </el-table-column>
        <el-table-column prop="contributionScore" label="贡献值" width="80" align="right" />
        <el-table-column label="核验 / 评价" width="110">
          <template #default="{ row }">
            {{ row.approvedVerificationCount }} / {{ row.publishedReviewCount }}
          </template>
        </el-table-column>
        <el-table-column label="注册时间" width="140">
          <template #default="{ row }">{{ fmtDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="84">
          <template #default="{ row }">
            <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : row.status === 'BANNED' ? 'danger' : 'info'">
              {{ labelOf(USER_STATUS_LABEL, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button
              link
              :type="row.status === 'BANNED' ? 'success' : 'danger'"
              :disabled="!isSuperAdmin()"
              @click="openBan(row)"
            >
              {{ row.status === 'BANNED' ? '解封' : '封禁' }}
            </el-button>
            <el-button link type="warning" :disabled="!isSuperAdmin()" @click="openContribution(row)">
              调整贡献值
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

    <el-drawer v-model="drawerVisible" size="620px" title="用户详情">
      <div v-loading="detailLoading">
        <template v-if="detail">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="用户 ID">
              <span class="mono">{{ detail.id }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag size="small" :type="detail.status === 'ACTIVE' ? 'success' : detail.status === 'BANNED' ? 'danger' : 'info'">
                {{ labelOf(USER_STATUS_LABEL, detail.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="手机号">{{ phoneText(detail) }}</el-descriptions-item>
            <el-descriptions-item label="匿名别名">{{ detail.alias || '—' }}</el-descriptions-item>
            <el-descriptions-item label="实名状态">{{ labelOf(REALNAME_STATUS_LABEL, detail.realnameStatus) }}</el-descriptions-item>
            <el-descriptions-item label="贡献值">{{ detail.contributionScore }}</el-descriptions-item>
            <el-descriptions-item label="核验通过">{{ detail.approvedVerificationCount }} 次</el-descriptions-item>
            <el-descriptions-item label="已发布评价">{{ detail.publishedReviewCount }} 条</el-descriptions-item>
            <el-descriptions-item label="注册时间" :span="2">{{ fmtDateTime(detail.createdAt) }}</el-descriptions-item>
          </el-descriptions>

          <div class="detail-block" style="margin-top: 16px">
            <h4>评价状态分布</h4>
            <div class="tag-list">
              <el-tag
                v-for="(count, status) in detail.reviewStatusCounts"
                :key="status"
                size="small"
                effect="plain"
              >
                {{ labelOf(REVIEW_STATUS_LABEL, status) }} {{ count }}
              </el-tag>
              <span v-if="!Object.keys(detail.reviewStatusCounts || {}).length" class="muted">暂无评价</span>
            </div>
          </div>

          <div class="detail-block">
            <h4>核验记录</h4>
            <el-table v-if="detail.verifications?.length" :data="detail.verifications" size="small" border>
              <el-table-column label="类型" width="100">
                <template #default="{ row }">{{ labelOf(PROOF_TYPE_LABEL, row.proofType) }}</template>
              </el-table-column>
              <el-table-column label="公司" min-width="150" show-overflow-tooltip>
                <template #default="{ row }">{{ row.companyUscc }}</template>
              </el-table-column>
              <el-table-column label="周期" width="160">
                <template #default="{ row }">{{ row.periodStart }} ~ {{ row.periodEnd }}</template>
              </el-table-column>
              <el-table-column label="状态" width="84">
                <template #default="{ row }">{{ labelOf(VERIFICATION_STATUS_LABEL, row.status) }}</template>
              </el-table-column>
              <el-table-column label="审核时间" width="110">
                <template #default="{ row }">{{ row.reviewedAt ? fmtDate(row.reviewedAt) : '—' }}</template>
              </el-table-column>
            </el-table>
            <div v-else class="muted">暂无核验记录</div>
          </div>

          <div v-if="isSuperAdmin()" class="detail-block action-block">
            <el-button :type="detail.status === 'BANNED' ? 'success' : 'danger'" @click="openBan(detail)">
              {{ detail.status === 'BANNED' ? '解封用户' : '封禁用户' }}
            </el-button>
            <el-button type="warning" plain @click="openContribution(detail)">调整贡献值</el-button>
            <div class="kv-note">封禁会清除当前会话；贡献值调整范围 -1000 ~ 1000，写入审计日志。</div>
          </div>
        </template>
        <el-empty v-else-if="!detailLoading" description="未获取到用户详情" />
      </div>
    </el-drawer>

    <el-dialog v-model="banState.visible" :title="`${banActionText}用户`" width="480px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item :label="`${banActionText}原因`" required>
          <el-input
            v-model="banState.reason"
            type="textarea"
            :rows="3"
            maxlength="500"
            placeholder="填写原因，写入审计日志"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="banState.visible = false">取消</el-button>
        <el-button
          :type="banActionText === '解封' ? 'success' : 'danger'"
          :loading="banState.submitting"
          @click="submitBan"
        >
          确认{{ banActionText }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="contributionState.visible" title="调整贡献值" width="480px" destroy-on-close>
      <el-form label-width="84px" label-position="left">
        <el-form-item label="调整值" required>
          <el-input-number v-model="contributionState.delta" :min="-1000" :max="1000" :step="10" />
          <span class="legend">负数扣减，最多扣至 0。</span>
        </el-form-item>
        <el-form-item label="原因" required>
          <el-input v-model="contributionState.reason" maxlength="500" placeholder="例如：有效举报奖励" />
        </el-form-item>
        <el-form-item label="幂等标识">
          <el-input v-model="contributionState.refId" maxlength="64" placeholder="选填，重复提交时用于幂等" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="contributionState.visible = false">取消</el-button>
        <el-button type="primary" :loading="contributionState.submitting" @click="submitContribution">
          确认调整
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.action-block {
  padding-top: 14px;
  border-top: 1px dashed var(--rule);
}
</style>
