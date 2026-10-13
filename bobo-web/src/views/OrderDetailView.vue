<template>
  <div v-loading="loading" class="bobo-page">
    <div v-if="order" class="bobo-container narrow">
      <div class="bobo-card">
        <div class="head">
          <div>
            <h2 class="page-title">订单详情</h2>
            <p class="bobo-muted">订单号：{{ order.orderNo }}</p>
          </div>
          <el-tag :type="ORDER_STATUS[order.status]?.type" size="large" round>
            {{ order.statusText || ORDER_STATUS[order.status]?.text }}
          </el-tag>
        </div>

        <!-- 商品 -->
        <div class="goods" @click="$router.push(`/item/${order.itemId}`)">
          <img :src="coverOf({ cover: order.itemCover })" alt="" />
          <div class="goods-info">
            <p class="title ellipsis-2">{{ order.itemTitle }}</p>
            <span class="bobo-muted">数量 × {{ order.quantity }}</span>
          </div>
          <span class="bobo-price amount">{{ money(order.price) }}</span>
        </div>

        <!-- 交易信息 -->
        <el-descriptions :column="1" border class="desc">
          <el-descriptions-item label="交易方式">
            {{ order.tradeType === 2 ? '邮寄' : '当面交易' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="order.tradeType === 1" label="交易地点">
            {{ order.tradePlace || '未约定' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="order.tradeType === 1" label="约定时间">
            {{ formatTime(order.tradeTime) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="order.tradeType === 2" label="收货信息">
            {{ order.receiverInfo || '-' }}
          </el-descriptions-item>
          <el-descriptions-item v-if="order.logisticsNo" label="物流信息">
            {{ order.logisticsCompany || '快递' }}：{{ order.logisticsNo }}
          </el-descriptions-item>
          <el-descriptions-item label="买家">
            {{ order.buyerNickname || '-' }}
            <span class="bobo-muted">{{ maskPhone(order.buyerPhone) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="卖家">
            {{ order.sellerNickname || '-' }}
            <span class="bobo-muted">{{ maskPhone(order.sellerPhone) }}</span>
          </el-descriptions-item>
          <el-descriptions-item v-if="order.remark" label="买家留言">{{ order.remark }}</el-descriptions-item>
          <el-descriptions-item v-if="order.cancelReason" label="取消/退款原因">
            {{ order.cancelReason }}
          </el-descriptions-item>
          <el-descriptions-item label="下单时间">{{ formatTime(order.createTime, true) }}</el-descriptions-item>
          <el-descriptions-item v-if="order.payTime" label="支付时间">
            {{ formatTime(order.payTime, true) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="order.deliverTime" label="发货时间">
            {{ formatTime(order.deliverTime, true) }}
          </el-descriptions-item>
          <el-descriptions-item v-if="order.finishTime" label="完成时间">
            {{ formatTime(order.finishTime, true) }}
          </el-descriptions-item>
        </el-descriptions>

        <div class="total">
          <span>订单总额</span>
          <span class="bobo-price big">{{ money(order.totalAmount) }}</span>
        </div>

        <!-- 操作 -->
        <div class="actions">
          <el-button @click="$router.push('/orders')">返回列表</el-button>
          <el-button v-if="isBuyer && order.status === 0" @click="cancel">取消订单</el-button>
          <el-button v-if="isBuyer && order.status === 0" type="primary" class="bobo-btn-primary" @click="pay">去支付</el-button>
          <el-button v-if="isBuyer && [1, 2, 3].includes(order.status)" @click="refund">申请退款</el-button>
          <el-button v-if="isBuyer && order.status === 2" type="primary" class="bobo-btn-primary" @click="confirm">确认收货</el-button>
          <el-button v-if="isSeller && order.status === 1" type="primary" class="bobo-btn-primary" @click="deliverVisible = true">去发货</el-button>
          <el-button v-if="isSeller && order.status === 6" type="primary" class="bobo-btn-primary" @click="agreeRefund">同意退款</el-button>
          <el-button v-if="isSeller && order.status === 0" @click="cancel">关闭订单</el-button>
          <el-button v-if="order.status === 3 || order.status === 4" type="primary" class="bobo-btn-primary" @click="openReview">
            {{ order.reviewed ? '查看/补充评价' : '评价对方' }}
          </el-button>
        </div>
      </div>

      <!-- 评价弹窗 -->
      <el-dialog v-model="reviewVisible" title="评价本次交易" width="460px">
        <el-form label-width="80px">
          <el-form-item label="评分">
            <el-rate v-model="reviewForm.score" show-text :texts="['很差', '较差', '一般', '满意', '非常满意']" />
          </el-form-item>
          <el-form-item label="评价内容">
            <el-input
              v-model="reviewForm.content"
              type="textarea"
              :rows="4"
              maxlength="500"
              show-word-limit
              placeholder="说说商品与描述是否一致、沟通是否顺畅…"
            />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="reviewVisible = false">取消</el-button>
          <el-button type="primary" class="bobo-btn-primary" :loading="submitting" @click="submitReview">提交评价</el-button>
        </template>
      </el-dialog>

      <!-- 发货弹窗 -->
      <el-dialog v-model="deliverVisible" title="填写物流信息" width="440px">
        <el-form label-width="80px">
          <el-form-item label="快递公司">
            <el-input v-model="deliverForm.logisticsCompany" placeholder="如：顺丰 / 中通" maxlength="32" />
          </el-form-item>
          <el-form-item label="快递单号">
            <el-input v-model="deliverForm.logisticsNo" placeholder="请填写运单号" maxlength="40" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="deliverVisible = false">取消</el-button>
          <el-button type="primary" class="bobo-btn-primary" :loading="submitting" @click="deliver">确认发货</el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orderApi, reviewApi } from '@/api'
import { ORDER_STATUS, coverOf, formatTime, maskPhone, money } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const submitting = ref(false)
const order = ref(null)
const reviewVisible = ref(false)
const deliverVisible = ref(false)

const reviewForm = reactive({ score: 5, content: '' })
const deliverForm = reactive({ logisticsCompany: '', logisticsNo: '' })

const isBuyer = computed(() => order.value?.myRole === 'buyer')
const isSeller = computed(() => order.value?.myRole === 'seller')

onMounted(load)

watch(
  () => route.params.id,
  () => {
    if (route.name === 'order-detail') {
      load()
    }
  }
)

async function load() {
  loading.value = true
  try {
    order.value = await orderApi.detail(route.params.id)
  } catch (e) {
    order.value = null
  } finally {
    loading.value = false
  }
}

async function pay() {
  await ElMessageBox.confirm(
    `这是校园平台内的模拟支付，用于推进订单流程。确认支付 ¥${money(order.value.totalAmount)}？`,
    '确认支付',
    { type: 'info' }
  )
  await orderApi.pay(order.value.orderId)
  ElMessage.success('支付成功')
  load()
}

async function confirm() {
  await ElMessageBox.confirm('请确认已经收到商品且与描述一致', '确认收货', { type: 'warning' })
  await orderApi.confirm(order.value.orderId)
  ElMessage.success('已确认收货，别忘了评价对方')
  load()
}

async function cancel() {
  const { value } = await ElMessageBox.prompt('请填写取消原因', '取消订单', {
    inputValidator: (v) => (v && v.trim() ? true : '请填写原因')
  })
  await orderApi.cancel(order.value.orderId, { reason: value })
  ElMessage.success('订单已取消')
  load()
}

async function refund() {
  const { value } = await ElMessageBox.prompt('请填写退款原因，卖家同意后完成退款', '申请退款', {
    inputValidator: (v) => (v && v.trim() ? true : '请填写原因')
  })
  await orderApi.refund(order.value.orderId, { reason: value })
  ElMessage.success('退款申请已提交')
  load()
}

async function agreeRefund() {
  await ElMessageBox.confirm('同意退款后商品将恢复在售，确定？', '同意退款', { type: 'warning' })
  await orderApi.agreeRefund(order.value.orderId)
  ElMessage.success('已同意退款')
  load()
}

async function deliver() {
  if (!deliverForm.logisticsNo.trim()) {
    ElMessage.warning('请填写快递单号')
    return
  }
  submitting.value = true
  try {
    await orderApi.deliver(order.value.orderId, { ...deliverForm })
    deliverVisible.value = false
    ElMessage.success('已发货')
    load()
  } finally {
    submitting.value = false
  }
}

function openReview() {
  if (order.value.reviewed) {
    ElMessage.info('你已经评价过这笔订单了')
    return
  }
  if (order.value.status === 4) {
    ElMessage.info('订单已完成，无法再次评价')
    return
  }
  reviewForm.score = 5
  reviewForm.content = ''
  reviewVisible.value = true
}

async function submitReview() {
  submitting.value = true
  try {
    await reviewApi.submit({
      orderId: order.value.orderId,
      score: reviewForm.score,
      content: reviewForm.content
    })
    reviewVisible.value = false
    ElMessage.success('评价成功，感谢你的反馈')
    load()
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.narrow {
  max-width: 820px;
}

.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 16px;
}

.page-title {
  margin: 0 0 4px;
  font-size: 20px;
}

.goods {
  display: flex;
  gap: 12px;
  padding: 12px;
  background: var(--bobo-bg);
  border-radius: var(--bobo-radius);
  cursor: pointer;
}

.goods img {
  width: 80px;
  height: 80px;
  border-radius: 8px;
  object-fit: cover;
}

.goods-info {
  flex: 1;
  min-width: 0;
}

.goods .title {
  margin: 0 0 6px;
  font-size: 14px;
  line-height: 20px;
}

.goods .amount {
  font-size: 18px;
}

.desc {
  margin: 16px 0;
}

.total {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  padding: 12px 0;
  border-top: 1px dashed var(--bobo-border);
}

.total .big {
  font-size: 24px;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 8px;
}
</style>
