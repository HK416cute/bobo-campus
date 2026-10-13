<template>
  <div class="dashboard">
    <!-- 关键指标：一行 6 个，窄屏自动换行 -->
    <div class="stat-grid">
      <div v-for="card in statCards" :key="card.key" class="bobo-card stat-card">
        <div class="stat-icon" :style="{ background: card.bg, color: card.color }">
          <el-icon :size="20"><component :is="card.icon" /></el-icon>
        </div>
        <div class="stat-body">
          <div class="stat-label bobo-muted">{{ card.label }}</div>
          <div class="stat-value">
            <span>{{ card.value }}</span>
            <span v-if="card.suffix" class="suffix">{{ card.suffix }}</span>
          </div>
          <div v-if="card.extra" class="stat-extra bobo-muted">{{ card.extra }}</div>
        </div>
      </div>
    </div>

    <!-- 待办：直接跳到对应处理页 -->
    <div class="bobo-section-title">待处理事项</div>
    <div class="todo-grid">
      <div class="bobo-card todo-card" @click="router.push('/admin/reports')">
        <el-icon :size="22" class="todo-icon danger"><Warning /></el-icon>
        <div class="todo-body">
          <div class="todo-value">{{ pending.reportPending }}</div>
          <div class="todo-label bobo-muted">待处理举报</div>
        </div>
        <el-icon><ArrowRight /></el-icon>
      </div>
      <div class="bobo-card todo-card" @click="router.push('/admin/items')">
        <el-icon :size="22" class="todo-icon warn"><Goods /></el-icon>
        <div class="todo-body">
          <div class="todo-value">{{ pending.itemPending }}</div>
          <div class="todo-label bobo-muted">待审核商品</div>
        </div>
        <el-icon><ArrowRight /></el-icon>
      </div>
      <div class="bobo-card todo-card" @click="router.push('/admin/orders')">
        <el-icon :size="22" class="todo-icon info"><RefreshLeft /></el-icon>
        <div class="todo-body">
          <div class="todo-value">{{ pending.refunding }}</div>
          <div class="todo-label bobo-muted">退款中订单</div>
        </div>
        <el-icon><ArrowRight /></el-icon>
      </div>
    </div>

    <!-- 趋势 -->
    <div class="bobo-card chart-card">
      <div class="chart-head">
        <div class="bobo-section-title no-margin">交易趋势</div>
        <!-- 2.7 的 el-radio-button 用 label 同时承担取值与文案，所以这里只给 label -->
        <el-radio-group v-model="trendDays" size="small" @change="loadTrend">
          <el-radio-button :label="7">近 7 天</el-radio-button>
          <el-radio-button :label="14">近 14 天</el-radio-button>
          <el-radio-button :label="30">近 30 天</el-radio-button>
        </el-radio-group>
      </div>
      <div ref="trendRef" v-loading="trendLoading" class="chart chart-trend"></div>
    </div>

    <div class="chart-row">
      <div class="bobo-card chart-card">
        <div class="bobo-section-title no-margin">商品状态分布</div>
        <div ref="statusRef" v-loading="statusLoading" class="chart chart-small"></div>
      </div>
      <div class="bobo-card chart-card">
        <div class="bobo-section-title no-margin">分类商品分布</div>
        <div ref="categoryRef" v-loading="categoryLoading" class="chart chart-small"></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import * as echarts from 'echarts'
import { adminApi } from '@/api'

/**
 * 数据看板。
 * 四个接口各管一块，所以拆成独立 loading，某个图表挂了不影响其它面板。
 */
const router = useRouter()

const stat = ref({})
const statLoading = ref(false)
const pending = reactive({ reportPending: 0, itemPending: 0, refunding: 0 })

const trendDays = ref(7)
const trendLoading = ref(false)
const statusLoading = ref(false)
const categoryLoading = ref(false)

const trendRef = ref(null)
const statusRef = ref(null)
const categoryRef = ref(null)

// echarts 实例放在普通变量里即可：它们不参与渲染
let trendChart = null
let statusChart = null
let categoryChart = null

const statCards = computed(() => {
  const d = stat.value || {}
  return [
    {
      key: 'user',
      label: '用户总数',
      value: num(d.userTotal),
      icon: 'User',
      bg: '#fff8d6',
      color: '#8a6d00',
      extra: `今日新增 ${num(d.userToday)}`
    },
    {
      key: 'item',
      label: '商品总数',
      value: num(d.itemTotal),
      icon: 'Goods',
      bg: '#eef1f6',
      color: '#4a4d57',
      extra: `在售 ${num(d.itemOnSale)} · 已售 ${num(d.itemSold)}`
    },
    {
      key: 'pending',
      label: '待审核商品',
      value: num(d.itemPending),
      icon: 'Stamp',
      bg: '#fff1f0',
      color: '#ff4d4f',
      extra: '需要尽快处理'
    },
    {
      key: 'order',
      label: '订单总数',
      value: num(d.orderTotal),
      icon: 'List',
      bg: '#e8f8f0',
      color: '#17b26a',
      extra: `今日新增 ${num(d.orderToday)}`
    },
    {
      key: 'gmv',
      label: '累计成交额',
      value: num(d.gmv),
      suffix: '元',
      icon: 'Wallet',
      bg: '#fff8d6',
      color: '#8a6d00',
      extra: '含已支付订单'
    },
    {
      key: 'review',
      label: '平均评分',
      value: d.avgScore === null || d.avgScore === undefined ? '-' : Number(d.avgScore).toFixed(1),
      suffix: '分',
      icon: 'Star',
      bg: '#fff8d6',
      color: '#ff9f0a',
      extra: `评价 ${num(d.reviewTotal)} 条 · 收藏 ${num(d.favoriteTotal)}`
    }
  ]
})

function num(value) {
  if (value === null || value === undefined || value === '') {
    return 0
  }
  return value
}

async function loadStat() {
  statLoading.value = true
  try {
    stat.value = await adminApi.dashboard()
  } catch (e) {
    // 错误提示由 http.js 统一处理
  } finally {
    statLoading.value = false
  }
}

async function loadPending() {
  try {
    const data = await adminApi.pending()
    Object.assign(pending, {
      reportPending: data?.reportPending || 0,
      itemPending: data?.itemPending || 0,
      refunding: data?.refunding || 0
    })
  } catch (e) {
    // 待办数字失败不影响看板其它部分
  }
}

async function loadTrend() {
  trendLoading.value = true
  try {
    const list = await adminApi.trend(trendDays.value)
    renderTrend(list || [])
  } catch (e) {
    // 错误提示由 http.js 统一处理
  } finally {
    trendLoading.value = false
  }
}

async function loadStatus() {
  statusLoading.value = true
  try {
    const list = await adminApi.statusDistribution()
    renderStatus(list || [])
  } catch (e) {
    // 错误提示由 http.js 统一处理
  } finally {
    statusLoading.value = false
  }
}

async function loadCategory() {
  categoryLoading.value = true
  try {
    const list = await adminApi.categoryDistribution()
    renderCategory(list || [])
  } catch (e) {
    // 错误提示由 http.js 统一处理
  } finally {
    categoryLoading.value = false
  }
}

function renderTrend(list) {
  if (!trendChart) {
    return
  }
  trendChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { data: ['订单数', '成交额'], right: 0, top: 0 },
    grid: { left: 8, right: 8, bottom: 0, top: 36, containLabel: true },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: list.map((it) => it.day)
    },
    // 订单数是「笔」、成交额是「元」，量级差太多，必须双 Y 轴
    yAxis: [
      { type: 'value', name: '订单数', minInterval: 1, splitLine: { lineStyle: { color: '#eceef2' } } },
      { type: 'value', name: '成交额', splitLine: { show: false } }
    ],
    series: [
      {
        name: '订单数',
        type: 'line',
        smooth: true,
        yAxisIndex: 0,
        itemStyle: { color: '#ffb400' },
        areaStyle: { color: 'rgba(255, 209, 0, 0.18)' },
        data: list.map((it) => Number(it.orderCount) || 0)
      },
      {
        name: '成交额',
        type: 'line',
        smooth: true,
        yAxisIndex: 1,
        itemStyle: { color: '#ff4d4f' },
        data: list.map((it) => Number(it.amount) || 0)
      }
    ]
  })
}

function renderStatus(list) {
  if (!statusChart) {
    return
  }
  statusChart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}：{c} 件（{d}%）' },
    legend: { bottom: 0, icon: 'circle' },
    color: ['#ffb400', '#17b26a', '#8a8f99', '#b6bac2', '#ff4d4f', '#5b8ff9'],
    series: [
      {
        type: 'pie',
        radius: ['42%', '66%'],
        center: ['50%', '44%'],
        avoidLabelOverlap: true,
        label: { formatter: '{b}\n{c}' },
        data: list.map((it) => ({ name: it.name, value: Number(it.total) || 0 }))
      }
    ]
  })
}

function renderCategory(list) {
  if (!categoryChart) {
    return
  }
  // 横向柱图从下往上读更自然，所以倒序
  const sorted = [...list].sort((a, b) => (Number(a.total) || 0) - (Number(b.total) || 0))
  categoryChart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 8, right: 24, bottom: 0, top: 12, containLabel: true },
    xAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: '#eceef2' } } },
    yAxis: {
      type: 'category',
      data: sorted.map((it) => it.categoryName),
      axisTick: { show: false }
    },
    series: [
      {
        name: '商品数',
        type: 'bar',
        barMaxWidth: 16,
        itemStyle: { color: '#ffb400', borderRadius: [0, 6, 6, 0] },
        label: { show: true, position: 'right' },
        data: sorted.map((it) => Number(it.total) || 0)
      }
    ]
  })
}

function resizeCharts() {
  trendChart?.resize()
  statusChart?.resize()
  categoryChart?.resize()
}

onMounted(async () => {
  await nextTick()
  trendChart = echarts.init(trendRef.value)
  statusChart = echarts.init(statusRef.value)
  categoryChart = echarts.init(categoryRef.value)

  window.addEventListener('resize', resizeCharts)

  loadStat()
  loadPending()
  loadTrend()
  loadStatus()
  loadCategory()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', resizeCharts)
  trendChart?.dispose()
  statusChart?.dispose()
  categoryChart?.dispose()
  trendChart = null
  statusChart = null
  categoryChart = null
})
</script>

<style scoped>
.stat-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px;
}

.stat-card + .stat-card {
  margin-top: 0;
}

.stat-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border-radius: 50%;
  flex: 0 0 38px;
}

.stat-body {
  min-width: 0;
}

.stat-label {
  font-size: 12px;
  white-space: nowrap;
}

.stat-value {
  font-size: 20px;
  font-weight: 700;
  color: var(--bobo-text);
  line-height: 1.3;
  word-break: break-all;
}

.stat-value .suffix {
  margin-left: 2px;
  font-size: 12px;
  font-weight: 400;
  color: var(--bobo-text-secondary);
}

.stat-extra {
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.todo-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.todo-card {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  transition: box-shadow 0.2s ease, transform 0.2s ease;
}

.todo-card + .todo-card {
  margin-top: 0;
}

.todo-card:hover {
  box-shadow: var(--bobo-shadow-hover);
  transform: translateY(-2px);
}

.todo-icon.danger {
  color: var(--bobo-accent);
}

.todo-icon.warn {
  color: var(--bobo-warning);
}

.todo-icon.info {
  color: var(--bobo-text-regular);
}

.todo-body {
  flex: 1;
}

.todo-value {
  font-size: 22px;
  font-weight: 700;
}

.todo-label {
  font-size: 12px;
}

.chart-card {
  margin-top: 16px;
}

.chart-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.no-margin {
  margin: 0;
}

.chart {
  width: 100%;
}

.chart-trend {
  height: 320px;
  margin-top: 8px;
}

.chart-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.chart-small {
  height: 300px;
  margin-top: 8px;
}

@media (max-width: 1200px) {
  .stat-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .stat-grid,
  .todo-grid,
  .chart-row {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 640px) {
  .stat-grid,
  .todo-grid,
  .chart-row {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
