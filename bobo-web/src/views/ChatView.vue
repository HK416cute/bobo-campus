<template>
  <div class="bobo-page">
    <div class="bobo-container">
      <div class="chat-wrap">
        <!-- 左：聊天区 -->
        <div class="bobo-card chat">
          <header class="chat-head">
            <el-button text :icon="ArrowLeft" @click="$router.push('/messages')">返回</el-button>
            <img class="peer" :src="avatarOf(peer)" alt="" />
            <div class="peer-info">
              <strong>{{ peer?.nickname || '同学' }}</strong>
              <span class="bobo-muted">{{ peer?.campus || '' }}</span>
            </div>
            <el-button v-if="peer?.userId" text @click="$router.push(`/user/${peer.userId}`)">看 TA 的主页</el-button>
          </header>

          <div ref="listRef" v-loading="loading" class="messages">
            <div v-if="hasMore" class="history">
              <el-button text :loading="loadingMore" @click="loadMore">查看更早的消息</el-button>
            </div>

            <div
              v-for="message in orderedMessages"
              :key="message.messageId"
              class="message"
              :class="{ mine: message.fromId === userStore.userId }"
            >
              <img class="avatar" :src="avatarOf({ nickname: message.fromNickname, avatar: message.fromAvatar })" alt="" />
              <div class="bubble">
                <p>{{ message.content }}</p>
                <span class="time">{{ formatTime(message.createTime) }}</span>
              </div>
            </div>
          </div>

          <footer class="editor">
            <el-input
              v-model="draft"
              type="textarea"
              :rows="2"
              resize="none"
              maxlength="500"
              placeholder="输入消息，Enter 发送，Shift + Enter 换行"
              @keydown.enter.exact.prevent="send"
            />
            <el-button type="primary" class="bobo-btn-primary" :loading="sending" @click="send">发送</el-button>
          </footer>
        </div>

        <!-- 右：商品卡片与快捷入口 -->
        <aside class="bobo-card side">
          <div v-if="conversation" class="item-box" @click="$router.push(`/item/${conversation.itemId}`)">
            <img :src="coverOf({ cover: conversation.itemCover })" alt="" />
            <p class="ellipsis-2">{{ conversation.itemTitle }}</p>
            <span class="bobo-price">{{ money(conversation.itemPrice) }}</span>
            <el-tag v-if="conversation.itemStatus === 2" type="info" size="small" round>已售出</el-tag>
            <el-tag v-else-if="conversation.itemStatus === 1" type="success" size="small" round>在售</el-tag>
            <el-tag v-else type="warning" size="small" round>已下架</el-tag>
          </div>

          <el-button
            type="primary"
            class="bobo-btn-primary buy"
            :disabled="conversation?.itemStatus !== 1"
            @click="$router.push(`/item/${conversation.itemId}`)"
          >
            查看详情 / 下单
          </el-button>

          <el-divider />

          <div class="tips">
            <h4>交易提醒</h4>
            <p>· 约在校内公共区域见面验货，避免单独去陌生场所</p>
            <p>· 不要脱离平台私下转账，出现问题难以追溯</p>
            <p>· 收到货确认无误后再确认收货</p>
          </div>
        </aside>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft } from '@element-plus/icons-vue'
import { messageApi } from '@/api'
import { useUserStore } from '@/stores/user'
import { avatarOf, coverOf, formatTime, money } from '@/utils/format'

const route = useRoute()
const userStore = useUserStore()

const conversation = ref(null)
const peer = ref(null)
const messages = ref([])
const total = ref(0)
const pageNum = ref(1)
const loading = ref(false)
const loadingMore = ref(false)
const sending = ref(false)
const draft = ref('')
const listRef = ref(null)

/** 接口按时间倒序返回（最新在前），渲染时反转成聊天习惯的「旧→新」 */
const orderedMessages = computed(() => [...messages.value].reverse())
const hasMore = computed(() => messages.value.length < total.value)

let timer = null

onMounted(async () => {
  await enter()
  // 轮询代替 WebSocket：校园场景并发低，8 秒一次足够，实现与运维都更简单
  timer = setInterval(pollNew, 8000)
})

onBeforeUnmount(() => {
  if (timer) {
    clearInterval(timer)
  }
})

watch(
  () => route.params.id,
  async () => {
    if (route.name === 'chat') {
      await enter()
    }
  }
)

async function enter() {
  loading.value = true
  try {
    const data = await messageApi.enter(route.params.id)
    conversation.value = data
    peer.value = {
      userId: data.peerId,
      nickname: data.peerNickname,
      avatar: data.peerAvatar,
      campus: data.peerCampus
    }
    pageNum.value = 1
    const page = await messageApi.messages(route.params.id, { pageNum: 1, pageSize: 20 })
    messages.value = page.records || []
    total.value = page.total || 0
    userStore.refreshBadges()
    await scrollToBottom()
  } catch (e) {
    conversation.value = null
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  loadingMore.value = true
  try {
    pageNum.value += 1
    const page = await messageApi.messages(route.params.id, { pageNum: pageNum.value, pageSize: 20 })
    messages.value = [...messages.value, ...(page.records || [])]
  } finally {
    loadingMore.value = false
  }
}

async function pollNew() {
  try {
    const page = await messageApi.messages(route.params.id, { pageNum: 1, pageSize: 20 })
    const incoming = page.records || []
    // 只处理比本地更新的消息，避免打断正在输入的用户
    const knownIds = new Set(messages.value.map((m) => m.messageId))
    const fresh = incoming.filter((m) => !knownIds.has(m.messageId))
    if (fresh.length) {
      messages.value = [...fresh, ...messages.value]
      total.value = page.total || total.value
      await messageApi.markRead(route.params.id).catch(() => {})
      userStore.refreshBadges()
      await scrollToBottom()
    }
  } catch (e) {
    // 轮询失败静默处理，下一轮会重试
  }
}

async function send() {
  const content = draft.value.trim()
  if (!content) {
    return
  }
  sending.value = true
  try {
    const message = await messageApi.send({ conversationId: route.params.id, content })
    messages.value = [message, ...messages.value]
    total.value += 1
    draft.value = ''
    await scrollToBottom()
  } catch (e) {
    ElMessage.error('发送失败，请重试')
  } finally {
    sending.value = false
  }
}

async function scrollToBottom() {
  await nextTick()
  const el = listRef.value
  if (el) {
    el.scrollTop = el.scrollHeight
  }
}
</script>

<style scoped>
.chat-wrap {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: 16px;
  align-items: start;
}

.chat {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 140px);
  min-height: 480px;
  padding: 0;
}

.chat-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--bobo-border);
}

.chat-head .peer {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  object-fit: cover;
}

.peer-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  font-size: 13px;
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
  background: var(--bobo-bg);
}

.history {
  text-align: center;
  margin-bottom: 8px;
}

.message {
  display: flex;
  gap: 8px;
  margin-bottom: 14px;
}

.message.mine {
  flex-direction: row-reverse;
}

.message .avatar {
  width: 34px;
  height: 34px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}

.bubble {
  max-width: 70%;
  background: #fff;
  border-radius: 12px;
  padding: 8px 12px;
  box-shadow: var(--bobo-shadow);
}

.message.mine .bubble {
  background: var(--bobo-primary-light);
}

.bubble p {
  margin: 0;
  line-height: 1.55;
  word-break: break-word;
  white-space: pre-wrap;
}

.bubble .time {
  display: block;
  margin-top: 4px;
  font-size: 11px;
  color: var(--bobo-text-placeholder);
  text-align: right;
}

.editor {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  padding: 12px;
  border-top: 1px solid var(--bobo-border);
}

.side .item-box {
  cursor: pointer;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.side .item-box img {
  width: 100%;
  aspect-ratio: 1 / 1;
  object-fit: cover;
  border-radius: var(--bobo-radius);
}

.side .item-box p {
  margin: 0;
  font-size: 13px;
  line-height: 18px;
}

.side .buy {
  width: 100%;
  margin-top: 12px;
}

.tips h4 {
  margin: 0 0 8px;
  font-size: 14px;
}

.tips p {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--bobo-text-secondary);
  line-height: 1.6;
}

@media (max-width: 900px) {
  .chat-wrap {
    grid-template-columns: 1fr;
  }

  .side {
    display: none;
  }

  .chat {
    height: calc(100vh - 180px);
  }
}
</style>
