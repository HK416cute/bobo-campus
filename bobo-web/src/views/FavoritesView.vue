<template>
  <div class="bobo-page">
    <div class="bobo-container">
      <div class="bobo-section-title">
        <span>我的想要</span>
        <span class="bobo-muted">共 {{ total }} 件</span>
      </div>

      <SkeletonGrid v-if="loading && !items.length" :count="8" />

      <div v-else-if="items.length" class="grid">
        <ItemCard v-for="item in items" :key="item.itemId" :item="item" show-seller>
          <template #actions>
            <el-button size="small" text type="danger" @click="remove(item)">取消想要</el-button>
          </template>
        </ItemCard>
      </div>

      <EmptyState v-else text="还没有想要的宝贝，看到喜欢的点个「想要」吧">
        <el-button class="bobo-btn-primary" @click="$router.push('/')">去逛逛</el-button>
      </EmptyState>

      <div v-if="total > items.length" class="load-more">
        <el-button text :loading="loading" @click="loadMore">加载更多</el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { favoriteApi } from '@/api'
import ItemCard from '@/components/ItemCard.vue'
import SkeletonGrid from '@/components/SkeletonGrid.vue'
import EmptyState from '@/components/EmptyState.vue'

defineOptions({ name: 'FavoritesView' })

const items = ref([])
const total = ref(0)
const pageNum = ref(1)
const loading = ref(false)

onMounted(() => load(true))

async function load(reset = false) {
  if (loading.value) {
    return
  }
  loading.value = true
  try {
    const data = await favoriteApi.page({ pageNum: pageNum.value, pageSize: 16 })
    items.value = reset ? data.records : [...items.value, ...data.records]
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

async function remove(item) {
  try {
    await favoriteApi.toggle(item.itemId)
    ElMessage.success('已取消想要')
    items.value = items.value.filter((i) => i.itemId !== item.itemId)
    total.value = Math.max(0, total.value - 1)
  } catch (e) {
    // 提示由 http.js 处理
  }
}
</script>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(178px, 1fr));
  gap: 14px;
}

.load-more {
  text-align: center;
  padding: 20px 0;
}

@media (max-width: 768px) {
  .grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
  }
}
</style>
