<template>
  <div class="bobo-page">
    <div class="bobo-container narrow">
      <header class="page-head">
        <div>
          <h1>我的撮合</h1>
          <p class="bobo-muted">
            买家 Agent 与卖家 Agent 多轮协商价格，达成后仍需买卖双方各自确认；系统不会自动成交。
          </p>
        </div>
        <el-button :icon="ChatLineRound" @click="$router.push('/qa')">规则问答</el-button>
      </header>

      <el-tabs v-model="activeTab" class="match-tabs" @tab-change="load">
        <el-tab-pane label="我发起的（买家）" name="buyer" />
        <el-tab-pane label="别人找我谈（卖家）" name="seller" />
      </el-tabs>

      <div v-loading="loading" class="list">
        <div v-if="!loading && !list.length" class="empty">
          <el-icon :size="40"><Connection /></el-icon>
          <p v-if="activeTab === 'buyer'">
            还没有发起过撮合。去商品详情页点「发起智能撮合」，让买家 Agent 帮你谈价。
          </p>
          <p v-else>还没有买家找你撮合。商品被发起协商后会出现在这里。</p>
          <el-button type="primary" plain @click="$router.push('/')">去逛逛</el-button>
        </div>

        <div
          v-for="match in list"
          :key="match.matchId"
          class="bobo-card match-card"
          @click="openDetail(match.matchId)"
        >
          <img :src="coverOf({ cover: match.itemCover })" class="cover" alt="" />
          <div class="main">
            <div class="title-line">
              <strong class="ellipsis">{{ match.itemTitle }}</strong>
              <el-tag :type="statusType(match.status)" effect="light" round size="small">
                {{ match.statusText }}
              </el-tag>
            </div>
            <div class="meta">
              <span>商品价 ¥{{ money(match.itemPrice) }}</span>
              <span v-if="match.agreedPrice" class="agreed">建议成交价 ¥{{ money(match.agreedPrice) }}</span>
              <span>协商 {{ match.rounds }} 轮</span>
              <span>{{ formatTime(match.createTime) }}</span>
            </div>
            <p class="summary">{{ match.summary || match.lastMessage || '协商进行中…' }}</p>
            <div class="parties">
              <span v-if="activeTab === 'buyer'">卖家：{{ match.sellerNickname }}</span>
              <span v-else>买家：{{ match.buyerNickname }}</span>
              <span v-if="match.awaitingMyConfirm" class="waiting">等待你确认</span>
              <span v-else-if="match.status === 2" class="ok">双方已确认</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- 详情抽屉：完整协商过程 + 确认 -->
    <el-drawer v-model="drawerVisible" title="撮合详情" size="640px">
      <div v-loading="detailLoading" class="detail">
        <template v-if="detail">
          <div class="detail-head">
            <img :src="coverOf({ cover: detail.itemCover })" alt="" />
            <div>
              <strong>{{ detail.itemTitle }}</strong>
              <div class="meta">
                <span>商品价 ¥{{ money(detail.itemPrice) }}</span>
                <span v-if="detail.agreedPrice" class="agreed">
                  建议成交价 ¥{{ money(detail.agreedPrice) }}
                </span>
              </div>
            </div>
            <el-tag :type="statusType(detail.status)" effect="light" round>{{ detail.statusText }}</el-tag>
          </div>

          <el-alert
            v-if="detail.summary"
            :type="detail.status === 2 ? 'success' : (detail.status === 3 ? 'warning' : 'info')"
            :closable="false"
            show-icon
            :title="detail.summary"
            class="summary-alert"
          />

          <div class="facts">
            <div><span>买家</span>{{ detail.buyerNickname }}</div>
            <div><span>卖家</span>{{ detail.sellerNickname }}</div>
            <div><span>预算</span>{{ budgetText(detail) }}</div>
            <div v-if="detail.tradeType"><span>方式</span>{{ detail.tradeType === 1 ? '当面交易' : '邮寄' }}</div>
            <div v-if="detail.preferredTime"><span>期望时间</span>{{ detail.preferredTime }}</div>
            <div v-if="detail.preferredPlace"><span>期望地点</span>{{ detail.preferredPlace }}</div>
          </div>
          <p v-if="detail.demandText" class="demand">买家诉求：{{ detail.demandText }}</p>

          <div class="section-title">协商过程</div>
          <el-timeline class="timeline">
            <el-timeline-item
              v-for="message in detail.messages"
              :key="message.messageId"
              :timestamp="`${message.senderText} · ${message.actionText}${message.price ? ' ¥' + money(message.price) : ''}`"
              :color="message.sender === 'buyer_agent' ? '#409eff' : (message.sender === 'seller_agent' ? '#e6a23c' : '#909399')"
              placement="top"
            >
              <p class="message">{{ message.content }}</p>
              <el-tag v-if="message.source === 'rule'" size="small" type="info" effect="plain">
                规则生成
              </el-tag>
            </el-timeline-item>
          </el-timeline>

          <div v-if="detail.awaitingMyConfirm" class="confirm-box">
            <el-input
              v-model="confirmNote"
              type="textarea"
              :rows="2"
              maxlength="255"
              show-word-limit
              placeholder="给对方的留言（选填），例如「周六下午三点，三食堂门口」"
            />
            <div class="confirm-actions">
              <el-button type="danger" plain :loading="confirming" @click="confirm(false)">拒绝</el-button>
              <el-button type="primary" class="bobo-btn-primary" :loading="confirming" @click="confirm(true)">
                接受 ¥{{ money(detail.agreedPrice) }}
              </el-button>
            </div>
            <p class="confirm-hint">双方都确认后，买家才能按撮合价下单；你现在的确认只是表态。</p>
          </div>

          <div v-else-if="detail.canPlaceOrder" class="confirm-box">
            <el-button type="primary" class="bobo-btn-primary" @click="placeOrder">
              按 ¥{{ money(detail.agreedPrice) }} 去下单
            </el-button>
            <p class="confirm-hint">下单页会使用撮合价，且撮合单一经下单即失效。</p>
          </div>

          <div v-else-if="detail.status === 0 || detail.status === 1" class="cancel-row">
            <el-button link type="danger" @click="cancel">取消这次撮合</el-button>
          </div>
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatLineRound, Connection } from '@element-plus/icons-vue'
import { aiApi } from '@/api'
import { coverOf, formatTime, money } from '@/utils/format'

const route = useRoute()
const router = useRouter()

const activeTab = ref('buyer')
const list = ref([])
const loading = ref(false)
const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)
const confirmNote = ref('')
const confirming = ref(false)

onMounted(async () => {
  await load()
  const id = route.query.id
  if (id) {
    openDetail(id)
  }
})

async function load() {
  loading.value = true
  try {
    list.value = await aiApi.matches(activeTab.value)
  } catch (e) {
    list.value = []
  } finally {
    loading.value = false
  }
}

async function openDetail(matchId) {
  drawerVisible.value = true
  detailLoading.value = true
  detail.value = null
  confirmNote.value = ''
  try {
    detail.value = await aiApi.matchDetail(matchId)
  } catch (e) {
    drawerVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

async function confirm(accepted) {
  if (!detail.value) {
    return
  }
  confirming.value = true
  try {
    detail.value = await aiApi.confirmMatch(detail.value.matchId, {
      accepted,
      note: confirmNote.value
    })
    ElMessage.success(accepted ? '已确认，等待对方确认' : '已拒绝本次撮合')
    await load()
  } catch (e) {
    // http.js 已提示
  } finally {
    confirming.value = false
  }
}

async function cancel() {
  if (!detail.value) {
    return
  }
  try {
    await ElMessageBox.confirm('取消后需要重新发起协商，确定取消吗？', '取消撮合', {
      type: 'warning',
      confirmButtonText: '确定取消',
      cancelButtonText: '再想想'
    })
  } catch (e) {
    return
  }
  try {
    await aiApi.cancelMatch(detail.value.matchId)
    ElMessage.success('已取消')
    drawerVisible.value = false
    await load()
  } catch (e) {
    // http.js 已提示
  }
}

function placeOrder() {
  if (!detail.value) {
    return
  }
  drawerVisible.value = false
  router.push({ path: `/item/${detail.value.itemId}`, query: { matchId: detail.value.matchId } })
}

function statusType(status) {
  return {
    0: 'warning',
    1: 'primary',
    2: 'success',
    3: 'info',
    4: 'info',
    5: 'info',
    6: 'success'
  }[status] || 'info'
}

function budgetText(match) {
  if (!match.budgetMin && !match.budgetMax) {
    return '未填写'
  }
  return `${match.budgetMin ? '¥' + money(match.budgetMin) : '不限'} ~ ${match.budgetMax ? '¥' + money(match.budgetMax) : '不限'}`
}
</script>

<style scoped>
.narrow {
  max-width: 980px;
}

.page-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 10px;
}

.page-head h1 {
  margin: 0 0 6px;
  font-size: 22px;
}

.page-head p {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
}

.match-tabs {
  margin-bottom: 4px;
}

.empty {
  padding: 60px 0;
  text-align: center;
  color: var(--el-text-color-secondary);
}

.empty p {
  margin: 10px 0 16px;
  line-height: 1.7;
}

.match-card {
  display: flex;
  gap: 14px;
  padding: 14px;
  margin-bottom: 12px;
  cursor: pointer;
  transition: transform 0.15s ease, box-shadow 0.15s ease;
}

.match-card:hover {
  transform: translateY(-1px);
  box-shadow: 0 8px 22px rgba(31, 33, 41, 0.08);
}

.match-card .cover {
  width: 92px;
  height: 92px;
  border-radius: 8px;
  object-fit: cover;
  flex: none;
  background: var(--el-fill-color-light);
}

.match-card .main {
  min-width: 0;
  flex: 1;
}

.title-line {
  display: flex;
  align-items: center;
  gap: 10px;
}

.title-line strong {
  flex: 1;
  min-width: 0;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.agreed {
  color: var(--el-color-danger);
  font-weight: 600;
}

.summary {
  margin: 8px 0 6px;
  font-size: 13px;
  line-height: 1.6;
  color: var(--el-text-color-regular);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.parties {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.parties .waiting {
  color: var(--el-color-warning);
  font-weight: 600;
}

.parties .ok {
  color: var(--el-color-success);
  font-weight: 600;
}

.detail-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.detail-head img {
  width: 64px;
  height: 64px;
  border-radius: 8px;
  object-fit: cover;
  background: var(--el-fill-color-light);
}

.detail-head > div {
  flex: 1;
  min-width: 0;
}

.summary-alert {
  margin-top: 12px;
}

.facts {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px 16px;
  margin-top: 14px;
  font-size: 13px;
}

.facts span {
  display: inline-block;
  min-width: 64px;
  color: var(--el-text-color-secondary);
}

.demand {
  margin: 10px 0 0;
  padding: 8px 10px;
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
  font-size: 13px;
  line-height: 1.7;
}

.section-title {
  margin: 20px 0 10px;
  font-size: 14px;
  font-weight: 600;
}

.timeline {
  padding-left: 2px;
}

.message {
  margin: 0 0 6px;
  font-size: 13px;
  line-height: 1.7;
}

.confirm-box {
  margin-top: 20px;
  padding: 14px;
  border: 1px dashed var(--el-color-primary-light-5);
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
}

.confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 10px;
}

.confirm-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.cancel-row {
  margin-top: 18px;
  text-align: center;
}

@media (max-width: 640px) {
  .page-head {
    flex-direction: column;
  }

  .match-card .cover {
    width: 72px;
    height: 72px;
  }

  .facts {
    grid-template-columns: 1fr;
  }
}
</style>
