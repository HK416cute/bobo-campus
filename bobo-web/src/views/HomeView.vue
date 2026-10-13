<template>
  <div class="bobo-page">
    <div class="bobo-container">
      <!-- 顶部横幅：校园闲置的主视觉 + 搜索入口 -->
      <section class="hero">
        <div class="hero-text">
          <h1>同校闲置，当面交易更放心</h1>
          <p>发布闲置只要 30 秒，支持当面验货与邮寄，全程站内留痕</p>
          <div class="hero-search">
            <el-input
              v-model="keyword"
              size="large"
              placeholder="搜一搜：考研资料 / 蓝牙耳机 / 宿舍小冰箱"
              clearable
              @keyup.enter="goSearch()"
            >
              <template #append>
                <el-button type="primary" :icon="Search" @click="goSearch()">搜索</el-button>
              </template>
            </el-input>
          </div>
          <div class="hot-words">
            <span>热门：</span>
            <a v-for="word in hotWords" :key="word" @click="goSearch(word)">{{ word }}</a>
          </div>
        </div>
        <img class="hero-art" :src="DEFAULT_COVER" alt="" />
      </section>

      <!-- 全站公告 -->
      <el-alert
        v-for="notice in notices"
        :key="notice.noticeId"
        class="notice"
        type="warning"
        :closable="false"
        show-icon
      >
        <template #title>
          <strong>{{ notice.title }}</strong>
          <span class="notice-content">{{ notice.content }}</span>
        </template>
      </el-alert>

      <!-- 分类导航 -->
      <section class="categories">
        <div
          v-for="category in categories"
          :key="category.categoryId"
          class="category"
          :class="{ active: activeCategory === category.categoryId }"
          @click="selectCategory(category.categoryId)"
        >
          <span class="icon">{{ category.icon || '📦' }}</span>
          <span class="name">{{ category.name }}</span>
          <span class="count">{{ category.itemCount || 0 }}</span>
        </div>
      </section>

      <!-- 商品瀑布流 -->
      <section>
        <div class="bobo-section-title">
          <span>{{ currentCategoryName || '最新上架' }}</span>
          <div class="sort">
            <span
              v-for="option in sortOptions"
              :key="option.value"
              :class="{ active: query.sortBy === option.value }"
              @click="changeSort(option.value)"
            >
              {{ option.label }}
            </span>
          </div>
        </div>

        <SkeletonGrid v-if="loading && !items.length" :count="10" />

        <div v-else-if="items.length" class="grid">
          <ItemCard v-for="item in items" :key="item.itemId" :item="item" show-seller show-original />
        </div>

        <EmptyState v-else text="这个分类下还没有闲置，去发布一件吧">
          <el-button class="bobo-btn-primary" @click="$router.push('/publish')">发布闲置</el-button>
        </EmptyState>

        <div v-if="items.length" class="load-more">
          <el-button v-if="hasMore" :loading="loading" text @click="loadMore">
            {{ loading ? '加载中…' : '加载更多' }}
          </el-button>
          <span v-else class="bobo-muted">已经到底啦，共 {{ total }} 件闲置</span>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onActivated, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { categoryApi, itemApi, noticeApi } from '@/api'
import ItemCard from '@/components/ItemCard.vue'
import SkeletonGrid from '@/components/SkeletonGrid.vue'
import EmptyState from '@/components/EmptyState.vue'
import { DEFAULT_COVER } from '@/utils/format'

defineOptions({ name: 'HomeView' })

const router = useRouter()

const hotWords = ['考研资料', '蓝牙耳机', '宿舍小冰箱', '山地车', '机械键盘']
const sortOptions = [
  { label: '最新', value: 'new' },
  { label: '最想要', value: 'want' },
  { label: '最多浏览', value: 'hot' },
  { label: '价格从低到高', value: 'priceAsc' },
  { label: '价格从高到低', value: 'priceDesc' }
]

const categories = ref([])
const notices = ref([])
const items = ref([])
const total = ref(0)
const loading = ref(false)
const activeCategory = ref(null)

const query = reactive({
  status: 1,
  sortBy: 'new',
  pageNum: 1,
  pageSize: 16
})

const hasMore = computed(() => items.value.length < total.value)
const currentCategoryName = computed(
  () => categories.value.find((c) => c.categoryId === activeCategory.value)?.name || ''
)

onMounted(async () => {
  await Promise.all([loadCategories(), loadNotices()])
  await loadItems(true)
})

// 从商品详情返回首页时刷新列表，保证「已售」状态是最新的
onActivated(() => {
  if (items.value.length) {
    loadItems(true)
  }
})

async function loadCategories() {
  try {
    categories.value = await categoryApi.list()
  } catch (e) {
    categories.value = []
  }
}

async function loadNotices() {
  try {
    notices.value = await noticeApi.broadcast(2)
  } catch (e) {
    notices.value = []
  }
}

async function loadItems(reset = false) {
  if (loading.value) {
    return
  }
  loading.value = true
  if (reset) {
    query.pageNum = 1
  }
  try {
    const data = await itemApi.page({
      ...query,
      categoryId: activeCategory.value || undefined
    })
    items.value = reset ? data.records : [...items.value, ...data.records]
    total.value = data.total
  } catch (e) {
    // 提示已由 http.js 处理
  } finally {
    loading.value = false
  }
}

function loadMore() {
  query.pageNum += 1
  loadItems()
}

function selectCategory(categoryId) {
  activeCategory.value = activeCategory.value === categoryId ? null : categoryId
  loadItems(true)
}

function changeSort(sortBy) {
  query.sortBy = sortBy
  loadItems(true)
}

function goSearch(word) {
  const text = word || keyword.value.trim()
  router.push({ path: '/search', query: text ? { keyword: text } : {} })
}
</script>

<style scoped>
.hero {
  display: flex;
  align-items: center;
  gap: 24px;
  padding: 28px 32px;
  border-radius: var(--bobo-radius-lg);
  background: linear-gradient(135deg, #fffce8 0%, #ffeaa0 55%, #ffd100 100%);
  margin-bottom: 16px;
}

.hero-text {
  flex: 1;
  min-width: 0;
}

.hero-text h1 {
  margin: 0 0 8px;
  font-size: 26px;
  line-height: 1.3;
}

.hero-text p {
  margin: 0 0 16px;
  color: #6b5a1f;
  font-size: 14px;
}

.hero-search {
  max-width: 520px;
}

.hero-search :deep(.el-input__wrapper) {
  border-radius: 999px 0 0 999px;
}

.hero-search :deep(.el-input-group__append) {
  border-radius: 0 999px 999px 0;
  overflow: hidden;
}

.hot-words {
  margin-top: 10px;
  font-size: 12px;
  color: #6b5a1f;
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.hot-words a {
  cursor: pointer;
  text-decoration: underline;
}

.hero-art {
  width: 190px;
  height: 190px;
  border-radius: 20px;
  object-fit: cover;
  box-shadow: 0 10px 24px rgba(31, 33, 41, 0.12);
  flex-shrink: 0;
}

.notice {
  margin-bottom: 10px;
  border-radius: var(--bobo-radius);
}

.notice-content {
  margin-left: 10px;
  color: #8a6d00;
  font-weight: 400;
}

.categories {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(112px, 1fr));
  gap: 10px;
  margin: 16px 0 8px;
}

.category {
  background: #fff;
  border-radius: var(--bobo-radius);
  padding: 12px 8px;
  text-align: center;
  cursor: pointer;
  border: 1px solid transparent;
  transition: all 0.15s ease;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.category:hover {
  transform: translateY(-2px);
  box-shadow: var(--bobo-shadow);
}

.category.active {
  border-color: var(--bobo-primary-deep);
  background: var(--bobo-primary-light);
}

.category .icon {
  font-size: 22px;
}

.category .name {
  font-size: 13px;
  font-weight: 600;
}

.category .count {
  font-size: 11px;
  color: var(--bobo-text-secondary);
}

.sort {
  display: flex;
  gap: 14px;
  font-size: 13px;
  font-weight: 400;
  color: var(--bobo-text-secondary);
}

.sort span {
  cursor: pointer;
}

.sort span.active {
  color: var(--bobo-accent);
  font-weight: 600;
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(178px, 1fr));
  gap: 14px;
}

.load-more {
  text-align: center;
  padding: 22px 0;
  font-size: 13px;
}

@media (max-width: 768px) {
  .hero {
    padding: 18px;
  }

  .hero-text h1 {
    font-size: 20px;
  }

  .hero-art {
    display: none;
  }

  .categories {
    grid-template-columns: repeat(auto-fill, minmax(86px, 1fr));
  }

  .grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
  }

  .sort {
    gap: 10px;
    font-size: 12px;
  }
}
</style>
