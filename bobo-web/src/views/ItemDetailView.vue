<template>
  <div v-loading="loading" class="bobo-page">
    <div v-if="item" class="bobo-container">
      <!-- 面包屑 -->
      <el-breadcrumb class="crumb" separator="/">
        <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
        <el-breadcrumb-item :to="{ path: '/search', query: { categoryId: item.categoryId } }">
          {{ item.categoryName || '分类' }}
        </el-breadcrumb-item>
        <el-breadcrumb-item>商品详情</el-breadcrumb-item>
      </el-breadcrumb>

      <div class="detail">
        <!-- 左：图集 + 描述 -->
        <div class="left">
          <div class="bobo-card gallery">
            <div class="main-image">
              <img :src="currentImage" :alt="item.title" @click="preview" />
              <span v-if="item.status === 2" class="sold">已售出</span>
              <span v-else-if="item.status === 3" class="sold off">已下架</span>
            </div>
            <div v-if="images.length > 1" class="thumbs">
              <img
                v-for="(url, index) in images"
                :key="url"
                :src="url"
                :class="{ active: index === activeIndex }"
                alt=""
                @mouseenter="activeIndex = index"
                @click="activeIndex = index"
              />
            </div>
          </div>

          <div class="bobo-card">
            <h3 class="block-title">宝贝描述</h3>
            <p class="description">{{ item.description || '卖家很懒，没有写描述，可以私信问问详情～' }}</p>
            <div class="attrs">
              <span>成色：{{ item.conditionLevel || '-' }}</span>
              <span>交易方式：{{ TRADE_TYPE[item.tradeType] || '都支持' }}</span>
              <span>校区：{{ item.campus || '-' }}</span>
              <span v-if="item.location">交易地点：{{ item.location }}</span>
              <span>浏览量：{{ item.viewCount }}</span>
            </div>
            <el-alert
              v-if="item.status === 4 && item.rejectReason"
              type="error"
              :closable="false"
              show-icon
              :title="`审核未通过：${item.rejectReason}`"
            />
          </div>
        </div>

        <!-- 右：价格、卖家、操作 -->
        <div class="right">
          <div class="bobo-card">
            <div class="price-line">
              <span class="price"><small>¥</small>{{ priceParts(item.price).int }}<small>.{{ priceParts(item.price).decimal }}</small></span>
              <span v-if="item.originalPrice" class="original">原价 ¥{{ money(item.originalPrice) }}</span>
              <el-tag :type="ITEM_STATUS[item.status]?.type" effect="light" round>
                {{ ITEM_STATUS[item.status]?.text }}
              </el-tag>
            </div>

            <h1 class="title">{{ item.title }}</h1>

            <div class="tags">
              <span class="bobo-tag">{{ item.conditionLevel }}</span>
              <span class="bobo-tag info">{{ TRADE_TYPE[item.tradeType] }}</span>
              <span v-if="item.campus" class="bobo-tag info">{{ item.campus }}</span>
              <span class="bobo-tag">{{ item.wantCount }} 人想要</span>
            </div>

            <!-- 卖家卡片 -->
            <div class="seller" @click="$router.push(`/user/${item.sellerId}`)">
              <img :src="avatarOf({ nickname: item.sellerNickname, avatar: item.sellerAvatar })" alt="" />
              <div class="seller-info">
                <div class="line1">
                  <strong>{{ item.sellerNickname || '同学' }}</strong>
                  <span v-if="item.sellerVerifyStatus === 2" class="bobo-tag success">学生已认证</span>
                </div>
                <div class="line2 bobo-muted">
                  {{ item.sellerCampus || '未填写校区' }}
                  <span v-if="item.sellerCollege"> · {{ item.sellerCollege }}</span>
                </div>
              </div>
              <div class="credit">
                <span class="score">{{ item.sellerCreditScore ?? 100 }}</span>
                <span class="label">信用分</span>
              </div>
            </div>

            <!-- 操作区 -->
            <div class="actions">
              <template v-if="isOwner">
                <el-button v-if="item.status !== 2" type="primary" class="bobo-btn-primary" @click="$router.push(`/item/${item.itemId}/edit`)">
                  编辑商品
                </el-button>
                <el-button v-if="item.status === 1" @click="offShelf">下架</el-button>
                <el-button v-if="item.status === 3 || item.status === 4" @click="onShelf">重新上架</el-button>
                <el-button type="danger" plain @click="removeItem">删除</el-button>
              </template>

              <template v-else>
                <el-button
                  class="want-btn"
                  :class="{ active: favorited }"
                  @click="toggleFavorite"
                >
                  <el-icon><Star /></el-icon>
                  {{ favorited ? '已想要' : '想要' }}
                </el-button>
                <el-button :icon="ChatDotRound" @click="chat">聊一聊</el-button>
                <el-button
                  type="primary"
                  class="bobo-btn-primary buy"
                  :disabled="item.status !== 1"
                  @click="openOrder"
                >
                  {{ item.status === 1 ? '立即购买' : '不可购买' }}
                </el-button>
              </template>
            </div>

            <p class="safe-tip">
              <el-icon><InfoFilled /></el-icon>
              交易请在校内公共区域当面验货，平台不收取任何手续费
            </p>
          </div>

          <!-- 卖家其他宝贝 -->
          <div v-if="item.sellerOtherItems?.length" class="bobo-card">
            <h3 class="block-title">TA 还在卖</h3>
            <div class="mini-grid">
              <div
                v-for="other in item.sellerOtherItems"
                :key="other.itemId"
                class="mini"
                @click="$router.push(`/item/${other.itemId}`)"
              >
                <img :src="coverOf(other)" alt="" />
                <p class="ellipsis-2">{{ other.title }}</p>
                <span class="bobo-price">{{ money(other.price) }}</span>
              </div>
            </div>
            <el-button text @click="$router.push(`/user/${item.sellerId}`)">查看 TA 的全部闲置 →</el-button>
          </div>
        </div>
      </div>
    </div>

    <!-- 下单弹窗 -->
    <el-dialog v-model="orderVisible" title="确认交易信息" width="520px">
      <el-form :model="orderForm" label-width="92px">
        <el-form-item label="交易方式">
          <el-radio-group v-model="orderForm.tradeType">
            <el-radio :value="1" :disabled="item?.tradeType === 2">当面交易</el-radio>
            <el-radio :value="2" :disabled="item?.tradeType === 1">邮寄</el-radio>
          </el-radio-group>
        </el-form-item>

        <template v-if="orderForm.tradeType === 1">
          <el-form-item label="交易地点">
            <el-input v-model="orderForm.tradePlace" :placeholder="item?.location || '如：三食堂门口'" maxlength="60" />
          </el-form-item>
          <el-form-item label="约定时间">
            <el-date-picker
              v-model="orderForm.tradeTime"
              type="datetime"
              value-format="YYYY-MM-DD HH:mm:ss"
              placeholder="选填，例如明天下午三点"
              style="width: 100%"
            />
          </el-form-item>
        </template>

        <template v-else>
          <el-form-item label="收货地址">
            <el-select v-model="orderForm.addressId" placeholder="请选择收货地址" style="width: 100%">
              <el-option
                v-for="address in addresses"
                :key="address.addressId"
                :label="`${address.receiver} ${address.phone} ${address.detail}`"
                :value="address.addressId"
              />
            </el-select>
            <el-button text type="primary" @click="$router.push('/me/addresses')">没有地址？去添加</el-button>
          </el-form-item>
        </template>

        <el-form-item label="给卖家留言">
          <el-input v-model="orderForm.remark" type="textarea" :rows="2" maxlength="200" show-word-limit />
        </el-form-item>

        <el-form-item label="订单金额">
          <span class="bobo-price total">{{ money(item?.price) }}</span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="orderVisible = false">再想想</el-button>
        <el-button type="primary" class="bobo-btn-primary" :loading="submitting" @click="submitOrder">
          确认下单
        </el-button>
      </template>
    </el-dialog>

    <el-image-viewer
      v-if="viewerVisible"
      :url-list="images"
      :initial-index="activeIndex"
      @close="viewerVisible = false"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, InfoFilled, Star } from '@element-plus/icons-vue'
import { addressApi, favoriteApi, itemApi, messageApi, orderApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { ITEM_STATUS, TRADE_TYPE, avatarOf, coverOf, money, priceParts } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(true)
const item = ref(null)
const favorited = ref(false)
const activeIndex = ref(0)
const viewerVisible = ref(false)

const orderVisible = ref(false)
const submitting = ref(false)
const addresses = ref([])
const orderForm = reactive({
  tradeType: 1,
  addressId: null,
  tradePlace: '',
  tradeTime: null,
  remark: ''
})

const images = computed(() => {
  const list = item.value?.images?.length ? item.value.images : [coverOf(item.value)]
  return list
})

const currentImage = computed(() => images.value[activeIndex.value] || images.value[0])
const isOwner = computed(() => userStore.userId && userStore.userId === item.value?.sellerId)

onMounted(load)

watch(
  () => route.params.id,
  () => {
    if (route.name === 'item-detail') {
      load()
    }
  }
)

async function load() {
  loading.value = true
  activeIndex.value = 0
  try {
    userStore.fetchUser()
    item.value = await itemApi.detail(route.params.id)
    favorited.value = !!item.value.favorited
    orderForm.tradeType = item.value.tradeType === 2 ? 2 : 1
    orderForm.tradePlace = item.value.location || ''
    if (!isOwner.value) {
      favoriteApi.check(route.params.id).then((res) => {
        favorited.value = !!res.favorited
      }).catch(() => {})
    }
  } catch (e) {
    item.value = null
  } finally {
    loading.value = false
  }
}

function preview() {
  viewerVisible.value = true
}

async function toggleFavorite() {
  if (!userStore.isLogin) {
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  try {
    const res = await favoriteApi.toggle(item.value.itemId)
    favorited.value = res.favorited
    item.value.wantCount = res.wantCount
    ElMessage.success(res.favorited ? '已加入「我的想要」' : '已取消想要')
  } catch (e) {
    // 提示由 http.js 处理
  }
}

async function chat() {
  if (!userStore.isLogin) {
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  try {
    const conversation = await messageApi.open({ itemId: item.value.itemId })
    router.push(`/chat/${conversation.conversationId}`)
  } catch (e) {
    // 提示由 http.js 处理
  }
}

async function openOrder() {
  if (!userStore.isLogin) {
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  orderVisible.value = true
  if (orderForm.tradeType === 2 && !addresses.value.length) {
    addresses.value = await addressApi.list().catch(() => [])
    const preferred = addresses.value.find((a) => a.isDefault === 1) || addresses.value[0]
    if (preferred) {
      orderForm.addressId = preferred.addressId
    }
  }
}

// 切换交易方式时按需拉取地址
watch(
  () => orderForm.tradeType,
  async (type) => {
    if (type === 2 && !addresses.value.length) {
      addresses.value = await addressApi.list().catch(() => [])
      const preferred = addresses.value.find((a) => a.isDefault === 1) || addresses.value[0]
      if (preferred) {
        orderForm.addressId = preferred.addressId
      }
    }
  }
)

async function submitOrder() {
  if (orderForm.tradeType === 2 && !orderForm.addressId) {
    ElMessage.warning('请选择收货地址')
    return
  }
  submitting.value = true
  try {
    const res = await orderApi.create({
      itemId: item.value.itemId,
      tradeType: orderForm.tradeType,
      addressId: orderForm.addressId,
      tradePlace: orderForm.tradePlace,
      tradeTime: orderForm.tradeTime,
      remark: orderForm.remark
    })
    orderVisible.value = false
    ElMessage.success('下单成功，请尽快完成支付')
    router.push(`/order/${res.orderId}`)
  } catch (e) {
    // 提示由 http.js 处理
  } finally {
    submitting.value = false
  }
}

async function offShelf() {
  await ElMessageBox.confirm('下架后买家将无法看到这件宝贝，确定下架？', '提示', { type: 'warning' })
  await itemApi.offShelf(item.value.itemId)
  ElMessage.success('已下架')
  load()
}

async function onShelf() {
  await itemApi.onShelf(item.value.itemId)
  ElMessage.success('已提交上架申请，等待管理员审核')
  load()
}

async function removeItem() {
  await ElMessageBox.confirm('删除后不可恢复，确定删除？', '删除确认', { type: 'warning' })
  await itemApi.remove(item.value.itemId)
  ElMessage.success('已删除')
  router.push('/my-items')
}
</script>

<style scoped>
.crumb {
  margin-bottom: 14px;
}

.detail {
  display: grid;
  grid-template-columns: minmax(0, 1.15fr) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.gallery .main-image {
  position: relative;
  width: 100%;
  aspect-ratio: 1 / 1;
  border-radius: var(--bobo-radius);
  overflow: hidden;
  background: var(--bobo-bg);
  cursor: zoom-in;
}

.gallery .main-image img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.sold {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(31, 33, 41, 0.55);
  color: #fff;
  font-size: 28px;
  font-weight: 700;
  letter-spacing: 4px;
}

.sold.off {
  background: rgba(31, 33, 41, 0.4);
  font-size: 22px;
}

.thumbs {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  overflow-x: auto;
}

.thumbs img {
  width: 64px;
  height: 64px;
  object-fit: cover;
  border-radius: 8px;
  border: 2px solid transparent;
  cursor: pointer;
  flex-shrink: 0;
}

.thumbs img.active {
  border-color: var(--bobo-primary-deep);
}

.block-title {
  margin: 0 0 12px;
  font-size: 15px;
}

.description {
  margin: 0 0 14px;
  white-space: pre-wrap;
  line-height: 1.75;
  color: var(--bobo-text-regular);
}

.attrs {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  font-size: 13px;
  color: var(--bobo-text-secondary);
  margin-bottom: 10px;
}

.price-line {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 10px;
}

.price {
  color: var(--bobo-accent);
  font-size: 30px;
  font-weight: 800;
  line-height: 1;
}

.price small {
  font-size: 15px;
}

.original {
  color: var(--bobo-text-placeholder);
  text-decoration: line-through;
  font-size: 13px;
}

.title {
  margin: 0 0 12px;
  font-size: 19px;
  line-height: 1.45;
}

.tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 16px;
}

.seller {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px;
  border-radius: var(--bobo-radius);
  background: var(--bobo-bg);
  cursor: pointer;
}

.seller img {
  width: 46px;
  height: 46px;
  border-radius: 50%;
  object-fit: cover;
}

.seller-info {
  flex: 1;
  min-width: 0;
}

.seller-info .line1 {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 15px;
}

.seller-info .line2 {
  font-size: 12px;
  margin-top: 2px;
}

.credit {
  text-align: center;
}

.credit .score {
  display: block;
  font-size: 18px;
  font-weight: 700;
  color: var(--bobo-success);
}

.credit .label {
  font-size: 11px;
  color: var(--bobo-text-secondary);
}

.actions {
  display: flex;
  gap: 10px;
  margin: 16px 0 12px;
  flex-wrap: wrap;
}

.actions .want-btn.active {
  border-color: var(--bobo-accent);
  color: var(--bobo-accent);
}

.actions .buy {
  flex: 1;
  min-width: 140px;
}

.safe-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  font-size: 12px;
  color: var(--bobo-text-secondary);
}

.mini-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
  margin-bottom: 8px;
}

.mini {
  cursor: pointer;
}

.mini img {
  width: 100%;
  aspect-ratio: 1 / 1;
  object-fit: cover;
  border-radius: 8px;
}

.mini p {
  margin: 6px 0 2px;
  font-size: 12px;
  line-height: 16px;
}

.total {
  font-size: 20px;
}

@media (max-width: 900px) {
  .detail {
    grid-template-columns: 1fr;
  }

  .mini-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}
</style>
