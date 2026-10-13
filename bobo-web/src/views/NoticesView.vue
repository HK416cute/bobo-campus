<template>
  <div class="bobo-page">
    <div class="bobo-container narrow">
      <div class="bobo-card">
        <div class="head">
          <h2 class="page-title">消息通知</h2>
          <el-button text :disabled="!userStore.unreadNotice" @click="readAll">全部标为已读</el-button>
        </div>

        <el-tabs v-model="type" @tab-change="reload">
          <el-tab-pane label="全部" name="all" />
          <el-tab-pane label="系统" name="system" />
          <el-tab-pane label="订单" name="order" />
          <el-tab-pane label="审核" name="audit" />
          <el-tab-pane label="私信" name="chat" />
        </el-tabs>

        <div v-loading="loading" class="list">
          <div
            v-for="notice in notices"
            :key="notice.noticeId"
            class="notice"
            :class="{ unread: notice.isRead === 0 }"
            @click="open(notice)"
          >
            <div class="dot" :class="{ on: notice.isRead === 0 }"></div>
            <div class="body">
              <div class="line1">
                <strong>{{ notice.title }}</strong>
                <el-tag size="small" effect="plain" :type="typeTag(notice.type)">{{ typeText(notice.type) }}</el-tag>
                <span class="time bobo-muted">{{ fromNow(notice.createTime) }}</span>
              </div>
              <p class="content">{{ notice.content }}</p>
            </div>
          </div>

          <EmptyState v-if="!loading && !notices.length" text="暂时没有通知" />
        </div>

        <div v-if="total > notices.length" class="load-more">
          <el-button text :loading="loading" @click="loadMore">加载更多</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { noticeApi } from '@/api'
import { useUserStore } from '@/stores/user'
import EmptyState from '@/components/EmptyState.vue'
import { fromNow } from '@/utils/format'

const router = useRouter()
const userStore = useUserStore()

const type = ref('all')
const notices = ref([])
const total = ref(0)
const pageNum = ref(1)
const loading = ref(false)

onMounted(reload)

async function reload() {
  pageNum.value = 1
  await load()
}

async function load() {
  loading.value = true
  try {
    const data = await noticeApi.page({
      type: type.value === 'all' ? undefined : type.value,
      pageNum: pageNum.value,
      pageSize: 12
    })
    notices.value = pageNum.value === 1 ? data.records : [...notices.value, ...data.records]
    total.value = data.total
  } catch (e) {
    notices.value = []
  } finally {
    loading.value = false
  }
}

function loadMore() {
  pageNum.value += 1
  load()
}

/** 点击通知：标记已读，并按类型跳转到对应页面 */
async function open(notice) {
  if (notice.isRead === 0) {
    await noticeApi.read(notice.noticeId).catch(() => {})
    notice.isRead = 1
    userStore.refreshBadges()
  }
  if (notice.type === 'order' && notice.bizId) {
    router.push(`/order/${notice.bizId}`)
  } else if (notice.type === 'audit' && notice.bizId) {
    router.push(`/item/${notice.bizId}`)
  } else if (notice.type === 'chat' && notice.bizId) {
    router.push(`/chat/${notice.bizId}`)
  }
}

async function readAll() {
  await noticeApi.readAll()
  notices.value.forEach((n) => {
    n.isRead = 1
  })
  userStore.refreshBadges()
  ElMessage.success('已全部标为已读')
}

function typeText(value) {
  return { system: '系统', order: '订单', audit: '审核', chat: '私信' }[value] || '通知'
}

function typeTag(value) {
  return { system: 'info', order: 'success', audit: 'warning', chat: 'primary' }[value] || 'info'
}
</script>

<style scoped>
.narrow {
  max-width: 820px;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.page-title {
  margin: 0;
  font-size: 20px;
}

.notice {
  display: flex;
  gap: 10px;
  padding: 14px 8px;
  border-bottom: 1px solid var(--bobo-border);
  cursor: pointer;
}

.notice:hover {
  background: #fafbfc;
}

.notice.unread {
  background: #fffdf3;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-top: 6px;
  background: transparent;
  flex-shrink: 0;
}

.dot.on {
  background: var(--bobo-accent);
}

.body {
  flex: 1;
  min-width: 0;
}

.line1 {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.line1 .time {
  margin-left: auto;
  font-size: 12px;
}

.content {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--bobo-text-secondary);
  line-height: 1.6;
}

.load-more {
  text-align: center;
  padding: 14px 0;
}
</style>
