<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Refresh, Search } from '@element-plus/icons-vue'
import { dashboardApi } from '../api'
import type { DashboardResponse, HealthRedLine, MetricView } from '../api/types'

const loading = ref(false)
const data = ref<DashboardResponse | null>(null)
const dateRange = ref<[string, string] | null>(null)

const filters = reactive({
  city: '',
  industry: '',
  refresh: false
})

const cityOptions = ['上海', '深圳', '杭州', '北京', '广州', '成都']
const industryOptions = ['IT 人力外包（驻场派遣）', 'IT 项目外包']

const groups = computed(() => {
  if (!data.value) return []
  return [
    { key: 'supply', title: '供给', items: data.value.supply },
    { key: 'coverage', title: '覆盖', items: data.value.coverage },
    { key: 'quality', title: '质量', items: data.value.quality },
    { key: 'demand', title: '需求', items: data.value.demand },
    { key: 'ecology', title: '生态', items: data.value.ecology }
  ]
})

const redLines = computed<HealthRedLine[]>(() => {
  if (!data.value) return []
  return data.value.healthRedLines?.length ? data.value.healthRedLines : data.value.redLines ?? []
})

const maxFunnel = computed(() => {
  if (!data.value?.funnel?.length) return 1
  return Math.max(1, ...data.value.funnel.map((stage) => stage.count))
})

const definitionList = computed(() => {
  if (!data.value?.metricDefinitions) return []
  return Object.entries(data.value.metricDefinitions)
})

function displayValue(metric: MetricView): string {
  const value = metric.value
  if (metric.unit === '%' || metric.unit === '‰') {
    return Number(value).toFixed(1)
  }
  if (Number.isInteger(value)) return String(value)
  return Number(value).toFixed(1)
}

function ratioText(metric: MetricView): string {
  if (metric.numerator === undefined || metric.denominator === undefined) return ''
  return `${metric.numerator} / ${metric.denominator}`
}

async function load() {
  loading.value = true
  try {
    data.value = await dashboardApi.get({
      from: dateRange.value?.[0],
      to: dateRange.value?.[1],
      city: filters.city || undefined,
      industry: filters.industry || undefined,
      refresh: filters.refresh || undefined
    })
  } catch {
    // 错误提示由请求层统一展示
  } finally {
    loading.value = false
    filters.refresh = false
  }
}

function resetFilters() {
  dateRange.value = null
  filters.city = ''
  filters.industry = ''
  load()
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="page-head">
      <div>
        <h2>数据看板</h2>
        <p>
          按日更新，时间窗口为业务时区 Asia/Shanghai 的自然日；指标口径与 PRD 17.1 逐条一致。
        </p>
      </div>
      <div class="legend" v-if="data?.window">
        当前窗口：{{ data.window.from }} ~ {{ data.window.to }}
        <span v-if="data.window.city"> · {{ data.window.city }}</span>
        <span v-if="data.window.industry"> · {{ data.window.industry }}</span>
      </div>
    </div>

    <div class="panel">
      <div class="filter-bar">
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 260px"
        />
        <el-select v-model="filters.city" clearable filterable allow-create placeholder="城市">
          <el-option v-for="city in cityOptions" :key="city" :label="city" :value="city" />
        </el-select>
        <el-select v-model="filters.industry" clearable filterable allow-create placeholder="行业">
          <el-option v-for="industry in industryOptions" :key="industry" :label="industry" :value="industry" />
        </el-select>
        <el-button type="primary" :icon="Search" @click="load">查询</el-button>
        <el-button :icon="Refresh" @click="resetFilters">重置</el-button>
        <el-checkbox v-model="filters.refresh" @change="load">跳过缓存刷新</el-checkbox>
        <span class="grow" />
        <span class="muted">默认最近 7 天；UV 与复访率基于全站访问事件。</span>
      </div>
    </div>

    <div v-if="data" class="panel northstar">
      <div>
        <div class="northstar-label">北极星指标 · 周有效口碑公司数</div>
        <div class="northstar-value">
          {{ displayValue(data.northstar) }}<small>{{ data.northstar.unit }}</small>
        </div>
        <div class="northstar-def">{{ data.northstar.definition || data.northstar.formula }}</div>
      </div>
      <el-tooltip :content="data.northstar.formula || data.northstar.definition || ''" placement="top">
        <el-tag effect="plain" type="success">口径可核对</el-tag>
      </el-tooltip>
    </div>

    <div v-for="group in groups" :key="group.key" class="group-block">
      <div class="table-title">
        <strong>{{ group.title }}</strong>
      </div>
      <div class="metric-grid">
        <el-tooltip
          v-for="metric in group.items"
          :key="metric.key"
          :content="metric.definition || metric.formula || metric.label"
          placement="top"
        >
          <div class="metric-card">
            <div class="label">{{ metric.label }}</div>
            <div class="value">{{ displayValue(metric) }}<small>{{ metric.unit }}</small></div>
            <div class="foot">{{ ratioText(metric) || metric.key }}</div>
          </div>
        </el-tooltip>
      </div>
    </div>

    <div class="dashboard-row">
      <div class="panel funnel-panel">
        <div class="table-title"><strong>供给漏斗 · 注册 → 实名 → 核验 → 首评</strong></div>
        <div v-for="stage in data?.funnel ?? []" :key="stage.stage" class="funnel-row">
          <div class="stage">{{ stage.stage }}</div>
          <div class="bar-wrap">
            <div class="bar" :style="{ width: `${(stage.count / maxFunnel) * 100}%` }" />
          </div>
          <div class="count">
            {{ stage.count }} 人
            <span v-if="stage.conversionFromPrevious !== undefined && stage.conversionFromPrevious !== null">
              · 转化 {{ stage.conversionFromPrevious.toFixed(1) }}%
            </span>
          </div>
        </div>
        <div class="legend">漏斗按当前窗口内新注册用户计算，相邻转化率为相对上一阶段。</div>
      </div>

      <div class="panel">
        <div class="table-title"><strong>三条健康度红线</strong></div>
        <div
          v-for="line in redLines"
          :key="line.key"
          class="redline"
          :class="{ triggered: line.triggered }"
        >
          <div class="dot" />
          <div class="meta">
            <strong>{{ line.label }}</strong>
            <p>
              当前 {{ line.value.toFixed(1) }}% · 阈值 {{ line.comparison }} {{ line.threshold }}%
              <el-tag v-if="line.triggered" size="small" type="danger" style="margin-left: 6px">已触发</el-tag>
              <el-tag v-else size="small" type="success" style="margin-left: 6px">正常</el-tag>
            </p>
            <p v-if="line.triggered">动作：{{ line.suggestion }}</p>
          </div>
        </div>
      </div>
    </div>

    <div class="panel">
      <el-collapse>
        <el-collapse-item title="指标口径说明（与服务端定义一致）" name="definitions">
          <div v-for="[key, text] in definitionList" :key="key" class="definition-row">
            <span class="mono">{{ key }}</span>
            <span>{{ text }}</span>
          </div>
        </el-collapse-item>
      </el-collapse>
    </div>
  </div>
</template>

<style scoped>
.northstar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  border-color: var(--brand);
  background: linear-gradient(120deg, #ffffff 0%, #eef5f3 100%);
}

.northstar-label {
  color: var(--muted);
  font-size: 12.5px;
}

.northstar-value {
  font-size: 40px;
  font-weight: 700;
  line-height: 1.2;
  letter-spacing: -0.02em;
  font-variant-numeric: tabular-nums;
}

.northstar-value small {
  font-size: 13px;
  font-weight: 500;
  color: var(--muted);
  margin-left: 4px;
}

.northstar-def {
  color: var(--muted);
  font-size: 12px;
  margin-top: 2px;
}

.group-block {
  margin-bottom: 14px;
}

.dashboard-row {
  display: grid;
  grid-template-columns: 1.15fr 1fr;
  gap: 14px;
}

.funnel-panel .funnel-row:first-of-type .bar {
  background: #0e4e4a;
}

.definition-row {
  display: flex;
  gap: 12px;
  padding: 4px 0;
  font-size: 12px;
  color: var(--muted);
}

.definition-row .mono {
  min-width: 220px;
  color: var(--ink);
}

@media (max-width: 1100px) {
  .dashboard-row {
    grid-template-columns: 1fr;
  }
}
</style>
