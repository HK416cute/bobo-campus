<template>
  <div class="bobo-page">
    <div class="bobo-container">
      <div class="bobo-card">
        <div class="head">
          <h2 class="page-title">我的发布</h2>
          <el-button type="primary" class="bobo-btn-primary" :icon="Plus" @click="$router.push('/publish')">
            发布闲置
          </el-button>
        </div>

        <el-tabs v-model="statusTab" @tab-change="reload">
          <el-tab-pane label="全部" name="all" />
          <el-tab-pane label="待审核" name="0" />
          <el-tab-pane label="在售" name="1" />
          <el-tab-pane label="已售" name="2" />
          <el-tab-pane label="已下架" name="3" />
          <el-tab-pane label="未通过" name="4" />
        </el-tabs>

        <div v-loading="loading" class="list">
          <div v-for="item in items" :key="item.itemId" class="row">
            <img :src="coverOf(item)" alt="" @click="$router.push(`/item/${item.itemId}`)" />

            <div class="info" @click="$router.push(`/item/${item.itemId}`)">
              <p class="title ellipsis-2">{{ item.title }}</p>
              <div class="meta">
                <span class="bobo-price">{{ money(item.price) }}</span>
                <el-tag :type="ITEM_STATUS[item.status]?.type" size="small" round>
                  {{ ITEM_STATUS[item.status]?.text }}
                </el-tag>
                <span class="bobo-muted">{{ item.viewCount }} 浏览 · {{ item.wantCount }} 想要</span>
              </div>
              <p v-if="item.status === 4 && item.rejectReason" class="reject">
                驳回原因：{{ item.rejectReason }}
              </p>
            </div>

            <div class="actions">
              <el-button size="small" @click="$router.push(`/item/${item.itemId}/edit`)">编辑</el-button>
              <el-button v-if="item.status === 1" size="small" @click="offShelf(item)">下架</el-button>
              <el-button v-if="[3, 4].includes(item.status)" size="small" type="primary" class="bobo-btn-primary" @click="onShelf(item)">
                重新上架
              </el-button>
              <el-button size="small" type="danger" plain @click="remove(item)">删除</el-button>
            </div>
          </div>

          <EmptyState v-if="!loading && !items.length" text="还没有发布过闲置，把用不上的东西换成生活费吧" />
        </div>

        <div v-if="total > items.length" class="load-more">
          <el-button text :loading="loading" @click="loadMore">加载更多</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { itemApi } from '@/api'
import EmptyState from '@/components/EmptyState.vue'
import { ITEM_STATUS, coverOf, money } from '@/utils/format'

const statusTab = ref('all')
const items = ref([])
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
    const data = await itemApi.mine({
      status: statusTab.value === 'all' ? undefined : Number(statusTab.value),
      pageNum: pageNum.value,
      pageSize: 10
    })
    items.value = pageNum.value === 1 ? data.records : [...items.value, ...data.records]
    total.value = data.total
  } catch (e) {
    items.value = []
  } finally {
    loading.value = false
  }
}

function loadMore() {
  pageNum.value += 1
  load()
}

async function offShelf(item) {
  await ElMessageBox.confirm('下架后买家看不到这件宝贝，确定下架？', '提示', { type: 'warning' })
  await itemApi.offShelf(item.itemId)
  ElMessage.success('已下架')
  reload()
}

async function onShelf(item) {
  await itemApi.onShelf(item.itemId)
  ElMessage.success('已提交上架申请，等待审核')
  reload()
}

async function remove(item) {
  await ElMessageBox.confirm('删除后不可恢复，确定删除？', '删除确认', { type: 'warning' })
  await itemApi.remove(item.itemId)
  ElMessage.success('已删除')
  reload()
}
</script>

<style scoped>
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

.row {
  display: flex;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid var(--bobo-border);
  align-items: center;
}

.row img {
  width: 84px;
  height: 84px;
  border-radius: 8px;
  object-fit: cover;
  cursor: pointer;
}

.info {
  flex: 1;
  min-width: 0;
  cursor: pointer;
}

.info .title {
  margin: 0 0 8px;
  font-size: 14px;
  line-height: 20px;
}

.info .meta {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  flex-wrap: wrap;
}

.info .meta .bobo-price {
  font-size: 16px;
}

.reject {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--bobo-accent);
}

.actions {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.load-more {
  text-align: center;
  padding: 16px 0;
}

@media (max-width: 768px) {
  .row {
    flex-wrap: wrap;
  }

  .actions {
    flex-direction: row;
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
