<template>
  <div class="bobo-page">
    <div class="bobo-container">
      <div class="bobo-card">
        <div class="head">
          <el-radio-group v-model="role" @change="reload">
            <el-radio-button value="buy">我买到的</el-radio-button>
            <el-radio-button value="sell">我卖出的</el-radio-button>
          </el-radio-group>
          <el-input
            v-model="query.keyword"
            placeholder="搜索订单号 / 商品名"
            clearable
            style="max-width: 240px"
            @keyup.enter="reload"
          >
            <template #append>
              <el-button :icon="Search" @click="reload" />
            </template>
          </el-input>
        </div>

        <el-tabs v-model="statusTab" @tab-change="reload">
          <el-tab-pane label="全部" name="all" />
          <el-tab-pane label="待付款" name="0" />
          <el-tab-pane label="待发货" name="1" />
          <el-tab-pane label="待收货" name="2" />
          <el-tab-pane label="待评价" name="3" />
          <el-tab-pane label="已完成" name="4" />
          <el-tab-pane label="退款/取消" name="closed" />
        </el-tabs>

        <div v-loading="loading">
          <div v-for="order in orders" :key="order.orderId" class="order">
            <div class="order-head">
              <span class="bobo-muted">订单号：{{ order.orderNo }}</span>
              <span class="bobo-muted">{{ formatTime(order.createTime) }}</span>
              <el-tag :type="ORDER_STATUS[order.status]?.type" size="small" round>
                {{ order.statusText || ORDER_STATUS[order.status]?.text }}
              </el-tag>
            </div>

            <div class="order-body" @click="$router.push(`/order/${order.orderId}`)">
              <img :src="coverOf({ cover: order.itemCover })" alt="" />
              <div class="info">
                <p class="title ellipsis-2">{{ order.itemTitle }}</p>
                <div class="meta bobo-muted">
                  <span>{{ order.tradeType === 2 ? '邮寄' : '当面交易' }}</span>
                  <span>× {{ order.quantity }}</span>
                  <span v-if="role === 'buy'">卖家：{{ order.sellerNickname }}</span>
                  <span v-else>买家：{{ order.buyerNickname }}</span>
                </div>
              </div>
              <div class="amount">
                <span class="bobo-price">{{ money(order.totalAmount) }}</span>
              </div>
            </div>

            <div class="order-actions" @click.stop>
              <el-button size="small" @click="$router.push(`/order/${order.orderId}`)">订单详情</el-button>
              <el-button v-if="role === 'buy' && order.status === 0" size="small" @click="cancel(order)">取消订单</el-button>
              <el-button v-if="role === 'buy' && order.status === 0" size="small" type="primary" class="bobo-btn-primary" @click="pay(order)">去支付</el-button>
              <el-button v-if="role === 'buy' && [1, 2, 3].includes(order.status)" size="small" @click="refund(order)">申请退款</el-button>
              <el-button v-if="role === 'buy' && order.status === 2" size="small" type="primary" class="bobo-btn-primary" @click="confirm(order)">确认收货</el-button>
              <el-button v-if="order.status === 3 || order.status === 4" size="small" @click="$router.push(`/order/${order.orderId}`)">
                {{ order.reviewed ? '查看评价' : '去评价' }}
              </el-button>
              <el-button v-if="role === 'sell' && order.status === 1" size="small" @click="$router.push(`/order/${order.orderId}`)">去发货</el-button>
              <el-button v-if="role === 'sell' && order.status === 6" size="small" type="primary" class="bobo-btn-primary" @click="agreeRefund(order)">同意退款</el-button>
              <el-button v-if="role === 'sell' && order.status === 0" size="small" @click="cancel(order)">关闭订单</el-button>
            </div>
          </div>

          <EmptyState v-if="!loading && !orders.length" :text="role === 'buy' ? '还没有买到的宝贝，去逛逛吧' : '还没有卖出的宝贝'" />

          <div v-if="total > orders.length" class="load-more">
            <el-button text :loading="loading" @click="loadMore">加载更多</el-button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { orderApi } from '@/api'
import EmptyState from '@/components/EmptyState.vue'
import { ORDER_STATUS, coverOf, formatTime, money } from '@/utils/format'

const role = ref('buy')
const statusTab = ref('all')
const query = reactive({ keyword: '' })
const orders = ref([])
const total = ref(0)
const pageNum = ref(1)
const loading = ref(false)

onMounted(reload)

function statusParam() {
  if (statusTab.value === 'all') {
    return null
  }
  // 「退款/取消」是多个状态的合集，前端不做多选筛选，直接取后端全部再自行过滤
  if (statusTab.value === 'closed') {
    return null
  }
  return Number(statusTab.value)
}

async function reload() {
  pageNum.value = 1
  await load()
}

async function load() {
  loading.value = true
  try {
    const data = await orderApi.page({
      role: role.value,
      keyword: query.keyword || undefined,
      status: statusParam(),
      pageNum: pageNum.value,
      pageSize: 10
    })
    let records = data.records || []
    if (statusTab.value === 'closed') {
      records = records.filter((o) => [5, 6, 7].includes(o.status))
    }
    orders.value = pageNum.value === 1 ? records : [...orders.value, ...records]
    total.value = data.total || 0
  } catch (e) {
    orders.value = []
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  pageNum.value += 1
  await load()
}

async function pay(order) {
  await ElMessageBox.confirm(
    `这是校园平台内的模拟支付，用于推进订单流程。确认支付 ¥${money(order.totalAmount)}？`,
    '确认支付',
    { type: 'info' }
  )
  await orderApi.pay(order.orderId)
  ElMessage.success('支付成功')
  reload()
}

async function confirm(order) {
  await ElMessageBox.confirm('请确认已经收到商品且与描述一致', '确认收货', { type: 'warning' })
  await orderApi.confirm(order.orderId)
  ElMessage.success('已确认收货')
  reload()
}

async function cancel(order) {
  const { value } = await ElMessageBox.prompt('请填写取消原因', '取消订单', {
    inputPlaceholder: '如：临时不需要了',
    inputValidator: (v) => (v && v.trim() ? true : '请填写原因')
  })
  await orderApi.cancel(order.orderId, { reason: value })
  ElMessage.success('订单已取消')
  reload()
}

async function refund(order) {
  const { value } = await ElMessageBox.prompt('请填写退款原因，卖家同意后将完成退款', '申请退款', {
    inputPlaceholder: '如：商品与描述不符',
    inputValidator: (v) => (v && v.trim() ? true : '请填写原因')
  })
  await orderApi.refund(order.orderId, { reason: value })
  ElMessage.success('退款申请已提交')
  reload()
}

async function agreeRefund(order) {
  await ElMessageBox.confirm('同意退款后商品将恢复在售，确定？', '同意退款', { type: 'warning' })
  await orderApi.agreeRefund(order.orderId)
  ElMessage.success('已同意退款')
  reload()
}
</script>

<style scoped>
.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 8px;
}

.order {
  border: 1px solid var(--bobo-border);
  border-radius: var(--bobo-radius);
  padding: 12px;
  margin-bottom: 12px;
}

.order-head {
  display: flex;
  align-items: center;
  gap: 14px;
  font-size: 12px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.order-head .el-tag {
  margin-left: auto;
}

.order-body {
  display: flex;
  gap: 12px;
  cursor: pointer;
}

.order-body img {
  width: 72px;
  height: 72px;
  border-radius: 8px;
  object-fit: cover;
}

.order-body .info {
  flex: 1;
  min-width: 0;
}

.order-body .title {
  margin: 0 0 6px;
  font-size: 14px;
  line-height: 20px;
}

.order-body .meta {
  display: flex;
  gap: 12px;
  font-size: 12px;
  flex-wrap: wrap;
}

.order-body .amount {
  text-align: right;
  font-size: 18px;
}

.order-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
  flex-wrap: wrap;
}

.load-more {
  text-align: center;
  padding: 10px 0;
}
</style>
