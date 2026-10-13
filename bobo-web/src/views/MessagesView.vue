<template>
  <div class="bobo-page">
    <div class="bobo-container narrow">
      <div class="bobo-card">
        <div class="head">
          <h2 class="page-title">我的消息</h2>
          <el-radio-group v-model="role" size="small" @change="load">
            <el-radio-button value="all">全部</el-radio-button>
            <el-radio-button value="buyer">我咨询的</el-radio-button>
            <el-radio-button value="seller">买家咨询我</el-radio-button>
          </el-radio-group>
        </div>

        <div v-loading="loading" class="list">
          <div
            v-for="conversation in conversations"
            :key="conversation.conversationId"
            class="conversation"
            @click="$router.push(`/chat/${conversation.conversationId}`)"
          >
            <el-badge :value="conversation.unread" :hidden="!conversation.unread" :max="99">
              <img class="avatar" :src="avatarOf({ nickname: conversation.peerNickname, avatar: conversation.peerAvatar })" alt="" />
            </el-badge>

            <div class="body">
              <div class="line1">
                <strong class="ellipsis">{{ conversation.peerNickname || '同学' }}</strong>
                <span class="time bobo-muted">{{ fromNow(conversation.lastTime) }}</span>
              </div>
              <p class="last ellipsis">{{ conversation.lastMessage || '打个招呼吧～' }}</p>
              <div class="item-line">
                <img :src="coverOf({ cover: conversation.itemCover })" alt="" />
                <span class="ellipsis">{{ conversation.itemTitle }}</span>
                <span class="bobo-price">{{ money(conversation.itemPrice) }}</span>
                <span v-if="conversation.itemStatus === 2" class="bobo-tag info">已售</span>
              </div>
            </div>
          </div>

          <EmptyState v-if="!loading && !conversations.length" text="还没有私信，去商品详情点「聊一聊」开始沟通" />
        </div>

        <div v-if="total > conversations.length" class="load-more">
          <el-button text :loading="loading" @click="loadMore">加载更多</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { messageApi } from '@/api'
import EmptyState from '@/components/EmptyState.vue'
import { avatarOf, coverOf, fromNow, money } from '@/utils/format'

const role = ref('all')
const conversations = ref([])
const total = ref(0)
const loading = ref(false)
const pageNum = ref(1)

onMounted(() => load())

async function load() {
  loading.value = true
  pageNum.value = 1
  try {
    const data = await messageApi.conversations({ role: role.value, pageNum: 1, pageSize: 20 })
    conversations.value = data.records || []
    total.value = data.total || 0
  } catch (e) {
    conversations.value = []
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  loading.value = true
  pageNum.value += 1
  try {
    const data = await messageApi.conversations({ role: role.value, pageNum: pageNum.value, pageSize: 20 })
    conversations.value = [...conversations.value, ...(data.records || [])]
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.narrow {
  max-width: 760px;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  flex-wrap: wrap;
  gap: 10px;
}

.page-title {
  margin: 0;
  font-size: 20px;
}

.conversation {
  display: flex;
  gap: 12px;
  padding: 14px 4px;
  border-bottom: 1px solid var(--bobo-border);
  cursor: pointer;
}

.conversation:hover {
  background: #fafbfc;
}

.avatar {
  width: 48px;
  height: 48px;
  border-radius: 50%;
  object-fit: cover;
}

.body {
  flex: 1;
  min-width: 0;
}

.line1 {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.time {
  font-size: 12px;
  flex-shrink: 0;
}

.last {
  margin: 4px 0 6px;
  font-size: 13px;
  color: var(--bobo-text-secondary);
}

.item-line {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 8px;
  border-radius: 8px;
  background: var(--bobo-bg);
  font-size: 12px;
}

.item-line img {
  width: 26px;
  height: 26px;
  border-radius: 4px;
  object-fit: cover;
}

.item-line .ellipsis {
  flex: 1;
  min-width: 0;
}

.load-more {
  text-align: center;
  padding-top: 12px;
}
</style>
