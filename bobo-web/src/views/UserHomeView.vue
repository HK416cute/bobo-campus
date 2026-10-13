<template>
  <div v-loading="loading" class="bobo-page">
    <div v-if="profile" class="bobo-container">
      <!-- 资料头 -->
      <div class="bobo-card header">
        <img class="avatar" :src="avatarOf(profile)" alt="" />
        <div class="info">
          <div class="line1">
            <h2>{{ profile.nickname }}</h2>
            <span v-if="profile.verifyStatus === 2" class="bobo-tag success">学生已认证</span>
            <span v-if="profile.role === 'admin'" class="bobo-tag">官方账号</span>
          </div>
          <p class="bobo-muted">
            {{ profile.campus || '未设置校区' }}
            <span v-if="profile.college"> · {{ profile.college }}</span>
            <span v-if="profile.grade"> · {{ profile.grade }} 级</span>
            <span> · 加入于 {{ formatTime(profile.createTime).slice(0, 10) }}</span>
          </p>
          <p class="bio">{{ profile.bio || '这个人很懒，什么都没写～' }}</p>
        </div>

        <div class="metrics">
          <div class="metric">
            <span class="num">{{ score.avgScore || 0 }}</span>
            <span class="label">平均评分</span>
          </div>
          <div class="metric">
            <span class="num">{{ profile.creditScore ?? 100 }}</span>
            <span class="label">信用分</span>
          </div>
          <div class="metric">
            <span class="num">{{ onSaleCount }}</span>
            <span class="label">在售</span>
          </div>
          <div class="metric">
            <span class="num">{{ soldCount }}</span>
            <span class="label">已售</span>
          </div>
        </div>

        <el-button
          v-if="!isMe"
          type="primary"
          class="bobo-btn-primary"
          :icon="ChatDotRound"
          @click="chatWithSeller"
        >
          打个招呼
        </el-button>
      </div>

      <div class="body">
        <!-- 在售商品 -->
        <div class="main">
          <div class="bobo-section-title">
            <span>TA 的闲置</span>
            <span class="bobo-muted">共 {{ total }} 件</span>
          </div>

          <div v-if="items.length" class="grid">
            <ItemCard v-for="item in items" :key="item.itemId" :item="item" />
          </div>
          <EmptyState v-else text="TA 还没有在售的闲置" />

          <div v-if="total > items.length" class="load-more">
            <el-button text :loading="loadingItems" @click="loadMore">加载更多</el-button>
          </div>
        </div>

        <!-- 评价 -->
        <aside class="side bobo-card">
          <h3 class="side-title">收到的评价（{{ reviews.length }}）</h3>
          <div v-if="reviews.length" class="reviews">
            <div v-for="review in reviews" :key="review.reviewId" class="review">
              <img :src="avatarOf({ nickname: review.fromNickname, avatar: review.fromAvatar })" alt="" />
              <div class="review-body">
                <div class="line1">
                  <strong>{{ review.fromNickname }}</strong>
                  <el-rate :model-value="review.score" disabled size="small" />
                </div>
                <p class="content">{{ review.content || '此用户没有填写评价内容' }}</p>
                <span class="bobo-muted time">{{ fromNow(review.createTime) }}</span>
              </div>
            </div>
          </div>
          <EmptyState v-else text="还没有收到评价" />
        </aside>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ChatDotRound } from '@element-plus/icons-vue'
import { messageApi, userApi } from '@/api'
import { useUserStore } from '@/stores/user'
import ItemCard from '@/components/ItemCard.vue'
import EmptyState from '@/components/EmptyState.vue'
import { avatarOf, formatTime, fromNow } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(true)
const loadingItems = ref(false)
const profile = ref(null)
const reviews = ref([])
const items = ref([])
const total = ref(0)
const pageNum = ref(1)
const score = ref({})
const onSaleCount = ref(0)
const soldCount = ref(0)

const isMe = computed(() => userStore.userId && userStore.userId === profile.value?.userId)

onMounted(load)

watch(
  () => route.params.id,
  () => {
    if (route.name === 'user-home') {
      load()
    }
  }
)

async function load() {
  loading.value = true
  pageNum.value = 1
  try {
    const data = await userApi.profile(route.params.id)
    profile.value = data.user
    score.value = data.scoreSummary || {}
    onSaleCount.value = data.onSaleCount || 0
    soldCount.value = data.soldCount || 0
    reviews.value = await userApi.reviews(route.params.id, 10).catch(() => [])
    const page = await userApi.items(route.params.id, { pageNum: 1, pageSize: 12 })
    items.value = page.records || []
    total.value = page.total || 0
  } catch (e) {
    profile.value = null
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  loadingItems.value = true
  try {
    pageNum.value += 1
    const page = await userApi.items(route.params.id, { pageNum: pageNum.value, pageSize: 12 })
    items.value = [...items.value, ...(page.records || [])]
  } finally {
    loadingItems.value = false
  }
}

/** 从主页发起聊天需要先有商品上下文，这里取 TA 第一件在售商品 */
async function chatWithSeller() {
  if (!userStore.isLogin) {
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  const first = items.value[0]
  if (!first) {
    ElMessage.info('TA 目前没有在售的宝贝，暂时无法发起聊天')
    return
  }
  try {
    const conversation = await messageApi.open({ itemId: first.itemId })
    router.push(`/chat/${conversation.conversationId}`)
  } catch (e) {
    // 提示由 http.js 处理
  }
}
</script>

<style scoped>
.header {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
}

.header .avatar {
  width: 76px;
  height: 76px;
  border-radius: 50%;
  object-fit: cover;
}

.header .info {
  flex: 1;
  min-width: 220px;
}

.header .line1 {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header h2 {
  margin: 0;
  font-size: 20px;
}

.header p {
  margin: 6px 0 0;
  font-size: 13px;
}

.header .bio {
  color: var(--bobo-text-regular);
}

.metrics {
  display: flex;
  gap: 22px;
  text-align: center;
}

.metric .num {
  display: block;
  font-size: 20px;
  font-weight: 700;
}

.metric .label {
  font-size: 12px;
  color: var(--bobo-text-secondary);
}

.body {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 320px;
  gap: 16px;
  margin-top: 16px;
  align-items: start;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(178px, 1fr));
  gap: 14px;
}

.load-more {
  text-align: center;
  padding: 18px 0;
}

.side-title {
  margin: 0 0 12px;
  font-size: 15px;
}

.review {
  display: flex;
  gap: 10px;
  padding: 10px 0;
  border-bottom: 1px solid var(--bobo-border);
}

.review img {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  object-fit: cover;
}

.review-body {
  flex: 1;
  min-width: 0;
}

.review-body .line1 {
  display: flex;
  align-items: center;
  gap: 8px;
  justify-content: space-between;
}

.review-body .content {
  margin: 4px 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--bobo-text-regular);
}

.review-body .time {
  font-size: 11px;
}

@media (max-width: 900px) {
  .body {
    grid-template-columns: 1fr;
  }

  .grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
  }

  .metrics {
    gap: 14px;
  }
}
</style>
