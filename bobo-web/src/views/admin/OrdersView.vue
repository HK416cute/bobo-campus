<template>
  <div class="orders-view">
    <div class="bobo-card filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索订单号 / 商品标题 / 买家卖家"
        clearable
        class="filter-item keyword"
        @keyup.enter="search"
        @clear="search"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="query.status" class="filter-item" placeholder="全部状态" @change="search">
        <el-option label="全部状态" :value="''" />
        <el-option v-for="(label, value) in STATUS_MAP" :key="value" :label="label" :value="Number(value)" />
      </el-select>

      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>

    <div class="bobo-card">
      <div class="bobo-section-title no-margin">订单列表</div>

      <el-table v-loading="loading" :data="list" border stripe row-key="orderId">
        <el-table-column label="订单号" width="170" prop="orderNo" show-overflow-tooltip />

        <el-table-column label="商品" min-width="220">
          <template #default="{ row }">
            <div class="item-cell">
              <el-image :src="row.itemCover || DEFAULT_COVER" fit="cover" class="cover">
                <template #error>
                  <div class="cover-fallback"><el-icon><Picture /></el-icon></div>
                </template>
              </el-image>
              <div class="item-info">
                <div class="ellipsis">{{ row.itemTitle || '-' }}</div>
                <div class="bobo-muted sub">数量 × {{ row.quantity || 1 }}</div>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="买家" width="120">
          <template #default="{ row }">
            <div class="ellipsis">{{ row.buyerNickname || '-' }}</div>
            <div class="bobo-muted sub">{{ row.buyerPhone || '-' }}</div>
          </template>
        </el-table-column>

        <el-table-column label="卖家" width="120">
          <template #default="{ row }">
            <div class="ellipsis">{{ row.sellerNickname || '-' }}</div>
            <div class="bobo-muted sub">{{ row.sellerPhone || '-' }}</div>
          </template>
        </el-table-column>

        <el-table-column label="金额" width="110">
          <template #default="{ row }">
            <span class="bobo-price">{{ row.totalAmount ?? row.price }}</span>
          </template>
        </el-table-column>

        <el-table-column label="交易方式" width="100" align="center">
          <template #default="{ row }">{{ row.tradeType === 2 ? '邮寄' : '当面交易' }}</template>
        </el-table-column>

        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <span class="bobo-tag" :class="statusClass(row.status)">
              {{ row.statusText || STATUS_MAP[row.status] || '未知' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">查看详情</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="bobo-empty">暂无订单数据</div>
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        class="pager"
        @size-change="search"
        @current-change="loadList"
      />
    </div>

    <el-drawer v-model="drawerVisible" title="订单详情" size="520px">
      <div v-loading="detailLoading" class="detail">
        <template v-if="detail">
          <div class="detail-hero">
            <el-image :src="detail.itemCover || DEFAULT_COVER" fit="cover" class="hero-cover">
              <template #error>
                <div class="cover-fallback"><el-icon><Picture /></el-icon></div>
              </template>
            </el-image>
            <div class="hero-info">
              <div class="hero-title">{{ detail.itemTitle || '-' }}</div>
              <div class="bobo-price hero-price">{{ detail.totalAmount ?? detail.price }}</div>
              <span class="bobo-tag" :class="statusClass(detail.status)">
                {{ detail.statusText || STATUS_MAP[detail.status] || '未知' }}
              </span>
            </div>
          </div>

          <el-descriptions title="订单信息" :column="1" border class="detail-block">
            <el-descriptions-item label="订单号">{{ detail.orderNo || '-' }}</el-descriptions-item>
            <el-descriptions-item label="商品编号">{{ detail.itemId || '-' }}</el-descriptions-item>
            <el-descriptions-item label="单价 / 数量">
              ¥{{ detail.price ?? '-' }} × {{ detail.quantity || 1 }}
            </el-descriptions-item>
            <el-descriptions-item label="订单金额">
              <span class="bobo-price">{{ detail.totalAmount ?? '-' }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="交易方式">
              {{ detail.tradeType === 2 ? '邮寄' : '当面交易' }}
            </el-descriptions-item>
            <el-descriptions-item label="买家">
              {{ detail.buyerNickname || '-' }}（{{ detail.buyerPhone || '无手机号' }}）
            </el-descriptions-item>
            <el-descriptions-item label="卖家">
              {{ detail.sellerNickname || '-' }}（{{ detail.sellerPhone || '无手机号' }}）
            </el-descriptions-item>
            <el-descriptions-item label="收货信息">{{ detail.receiverInfo || '-' }}</el-descriptions-item>
            <el-descriptions-item label="交易地点">{{ detail.tradePlace || '-' }}</el-descriptions-item>
            <el-descriptions-item label="约定时间">{{ formatTime(detail.tradeTime) }}</el-descriptions-item>
            <el-descriptions-item label="物流公司">{{ detail.logisticsCompany || '-' }}</el-descriptions-item>
            <el-descriptions-item label="物流单号">{{ detail.logisticsNo || '-' }}</el-descriptions-item>
            <el-descriptions-item label="取消原因">{{ detail.cancelReason || '-' }}</el-descriptions-item>
            <el-descriptions-item label="买家备注">{{ detail.remark || '-' }}</el-descriptions-item>
          </el-descriptions>

          <div class="detail-block">
            <div class="block-title">时间轴</div>
            <el-timeline>
              <el-timeline-item :timestamp="formatTime(detail.createTime)" type="primary">
                订单创建
              </el-timeline-item>
              <el-timeline-item v-if="detail.payTime" :timestamp="formatTime(detail.payTime)" type="success">
                买家付款
              </el-timeline-item>
              <el-timeline-item v-if="detail.deliverTime" :timestamp="formatTime(detail.deliverTime)">
                卖家发货
              </el-timeline-item>
              <el-timeline-item v-if="detail.finishTime" :timestamp="formatTime(detail.finishTime)" type="success">
                交易完成
              </el-timeline-item>
            </el-timeline>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { adminApi } from '@/api'

/**
 * 订单管理。后台只做查询与追溯，改状态的操作都在买家/卖家侧，避免越权改单。
 */
const STATUS_MAP = {
  0: '待付款',
  1: '待发货',
  2: '待收货',
  3: '待评价',
  4: '已完成',
  5: '已取消',
  6: '退款中',
  7: '已退款'
}

const DEFAULT_COVER = '/img/img-default-cover.svg'

const loading = ref(false)
const list = ref([])
const total = ref(0)

const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  role: 'all',
  keyword: '',
  status: ''
})

function statusClass(status) {
  if (status === 4) {
    return 'success'
  }
  if (status === 5 || status === 6 || status === 7) {
    return 'danger'
  }
  if (status === 0) {
    return ''
  }
  return 'info'
}

function formatTime(value) {
  return value || '-'
}

async function loadList() {
  loading.value = true
  try {
    const params = { ...query }
    if (params.status === '' || params.status === null) {
      delete params.status
    }
    const data = await adminApi.orders(params)
    list.value = data?.records || []
    total.value = data?.total || 0
  } catch (e) {
    // 错误提示由 http.js 统一处理
  } finally {
    loading.value = false
  }
}

function search() {
  query.pageNum = 1
  loadList()
}

function reset() {
  query.keyword = ''
  query.status = ''
  query.pageNum = 1
  loadList()
}

async function openDetail(row) {
  drawerVisible.value = true
  detail.value = row
  detailLoading.value = true
  try {
    // 列表字段不全，抽屉里再拉一次详情
    const data = await adminApi.orderDetail(row.orderId)
    detail.value = data || row
  } catch (e) {
    // 详情失败时退化展示列表行数据
  } finally {
    detailLoading.value = false
  }
}

onMounted(loadList)
</script>

<style scoped>
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.filter-item {
  width: 160px;
}

.filter-item.keyword {
  width: 280px;
}

.no-margin {
  margin: 0 0 12px;
}

.item-cell {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.cover {
  width: 46px;
  height: 46px;
  border-radius: 8px;
  flex: 0 0 46px;
}

.cover-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
  height: 100%;
  background: var(--bobo-bg);
  color: var(--bobo-text-placeholder);
}

.item-info {
  min-width: 0;
}

.sub {
  font-size: 12px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.detail {
  min-height: 200px;
}

.detail-hero {
  display: flex;
  gap: 12px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--bobo-border);
}

.hero-cover {
  width: 88px;
  height: 88px;
  border-radius: var(--bobo-radius);
  flex: 0 0 88px;
  background: var(--bobo-bg);
}

.hero-info {
  min-width: 0;
}

.hero-title {
  font-weight: 600;
  margin-bottom: 6px;
}

.hero-price {
  font-size: 18px;
  margin-bottom: 6px;
}

.detail-block {
  margin-top: 16px;
}

.block-title {
  margin-bottom: 12px;
  font-weight: 700;
}
</style>
