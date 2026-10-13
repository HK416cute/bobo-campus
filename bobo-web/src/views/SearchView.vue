<template>
  <div class="bobo-page">
    <div class="bobo-container">
      <!-- 搜索条 + 筛选 -->
      <div class="bobo-card filter">
        <!-- 自然语言检索：解析出的槽位会回填到下面的筛选条，用户看得到"平台理解成了什么"并可以改 -->
        <div class="nl-row">
          <el-input
            v-model="nlQuery"
            placeholder="用一句话描述你想要什么，例如：500 以内能当面交易的山地车"
            clearable
            :disabled="nlLoading"
            @keyup.enter="runNlSearch"
          >
            <template #prefix>
              <el-icon><MagicStick /></el-icon>
            </template>
            <template #append>
              <el-button :loading="nlLoading" @click="runNlSearch">
                {{ nlLoading ? '解析中…' : '智能搜索' }}
              </el-button>
            </template>
          </el-input>
          <span v-if="nlExplanation" class="nl-explain">
            {{ nlExplanation }}
            <el-button link type="primary" size="small" @click="clearNl">清除</el-button>
          </span>
        </div>

        <div class="row">
          <el-input
            v-model="query.keyword"
            placeholder="搜索商品关键词"
            clearable
            style="max-width: 420px"
            @keyup.enter="search()"
          >
            <template #append>
              <el-button :icon="Search" @click="search()" />
            </template>
          </el-input>

          <el-select v-model="query.categoryId" placeholder="全部分类" clearable style="width: 140px" @change="search()">
            <el-option
              v-for="category in categories"
              :key="category.categoryId"
              :label="category.name"
              :value="category.categoryId"
            />
          </el-select>

          <el-select v-model="query.conditionLevel" placeholder="成色" clearable style="width: 130px" @change="search()">
            <el-option v-for="level in conditions" :key="level" :label="level" :value="level" />
          </el-select>

          <el-select v-model="query.tradeType" placeholder="交易方式" clearable style="width: 130px" @change="search()">
            <el-option label="都支持" :value="0" />
            <el-option label="仅当面交易" :value="1" />
            <el-option label="仅邮寄" :value="2" />
          </el-select>

          <el-input-number
            v-model="query.minPrice"
            :min="0"
            :controls="false"
            placeholder="最低价"
            style="width: 96px"
            @change="search()"
          />
          <span class="dash">-</span>
          <el-input-number
            v-model="query.maxPrice"
            :min="0"
            :controls="false"
            placeholder="最高价"
            style="width: 96px"
            @change="search()"
          />

          <el-button text @click="reset">重置</el-button>
        </div>
      </div>

      <!-- 结果 -->
      <div class="bobo-section-title">
        <span>
          搜索结果
          <em v-if="query.keyword">「{{ query.keyword }}」</em>
          <span class="bobo-muted total">共 {{ total }} 件</span>
        </span>
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

      <EmptyState v-else text="没有找到符合条件的闲置，换个关键词试试" />

      <div v-if="items.length" class="load-more">
        <el-button v-if="hasMore" :loading="loading" text @click="loadMore">
          {{ loading ? '加载中…' : '加载更多' }}
        </el-button>
        <span v-else class="bobo-muted">已经到底啦</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { MagicStick, Search } from '@element-plus/icons-vue'
import { aiApi, categoryApi, itemApi } from '@/api'
import ItemCard from '@/components/ItemCard.vue'
import SkeletonGrid from '@/components/SkeletonGrid.vue'
import EmptyState from '@/components/EmptyState.vue'
import { CONDITION_OPTIONS } from '@/utils/format'

defineOptions({ name: 'SearchView' })

const route = useRoute()
const router = useRouter()

const conditions = CONDITION_OPTIONS
const sortOptions = [
  { label: '最新', value: 'new' },
  { label: '最想要', value: 'want' },
  { label: '最多浏览', value: 'hot' },
  { label: '价格从低到高', value: 'priceAsc' },
  { label: '价格从高到低', value: 'priceDesc' }
]

const categories = ref([])
const items = ref([])
const total = ref(0)
const loading = ref(false)

const query = reactive({
  keyword: '',
  categoryId: null,
  conditionLevel: null,
  tradeType: null,
  minPrice: null,
  maxPrice: null,
  status: 1,
  sortBy: 'new',
  pageNum: 1,
  pageSize: 16
})

const hasMore = computed(() => items.value.length < total.value)

onMounted(async () => {
  applyRouteQuery()
  categories.value = await categoryApi.list().catch(() => [])
  load(true)
})

// 首页的搜索框、分类跳转都走 query，这里统一监听
watch(
  () => route.query,
  () => {
    if (route.name !== 'search') {
      return
    }
    applyRouteQuery()
    load(true)
  }
)

function applyRouteQuery() {
  query.keyword = route.query.keyword ? String(route.query.keyword) : ''
  query.categoryId = route.query.categoryId ? Number(route.query.categoryId) : null
  query.minPrice = route.query.minPrice ? Number(route.query.minPrice) : null
  query.maxPrice = route.query.maxPrice ? Number(route.query.maxPrice) : null
  query.sortBy = route.query.sortBy ? String(route.query.sortBy) : 'new'
}

async function load(reset = false) {
  if (loading.value) {
    return
  }
  loading.value = true
  if (reset) {
    query.pageNum = 1
  }
  try {
    const params = { ...query }
    // 空值不传给后端，避免出现 categoryId= 这样的空参数
    Object.keys(params).forEach((key) => {
      if (params[key] === null || params[key] === '' || params[key] === undefined) {
        delete params[key]
      }
    })
    const data = await itemApi.page(params)
    items.value = reset ? data.records : [...items.value, ...data.records]
    total.value = data.total
  } catch (e) {
    // 提示由 http.js 处理
  } finally {
    loading.value = false
  }
}

function search() {
  // 用户手动触发的检索（点按钮 / 回车 / 改筛选）都算"动过手"，
  // 用于让采纳反馈有意义：没动过就不上报（见 recordNlFeedback）
  nlDirty.value = true
  // 同步到地址栏，方便分享与回退
  const nextQuery = {}
  if (query.keyword) nextQuery.keyword = query.keyword
  if (query.categoryId) nextQuery.categoryId = query.categoryId
  if (query.minPrice) nextQuery.minPrice = query.minPrice
  if (query.maxPrice) nextQuery.maxPrice = query.maxPrice
  if (query.sortBy !== 'new') nextQuery.sortBy = query.sortBy
  router.push({ path: '/search', query: nextQuery })
  load(true)
}

function changeSort(sortBy) {
  nlDirty.value = true
  query.sortBy = sortBy
  load(true)
}

function loadMore() {
  query.pageNum += 1
  load()
}

function reset() {
  nlDirty.value = true
  query.keyword = ''
  query.categoryId = null
  query.conditionLevel = null
  query.tradeType = null
  query.minPrice = null
  query.maxPrice = null
  query.sortBy = 'new'
  router.push({ path: '/search' })
  load(true)
}

// ------------------------------------------------------------------ 自然语言检索

const nlQuery = ref('')
const nlLoading = ref(false)
const nlExplanation = ref('')
/** 解析出的原始槽位快照，用于判断用户有没有手动改（= 是否采纳） */
const nlSnapshot = ref(null)
/** 用户是否在解析之后动过筛选条件——只有"动过"才有反馈价值 */
const nlDirty = ref(false)

/**
 * 一句话检索：把自然语言交给后端解析成结构化槽位，回填到筛选条后走**原有的**查询逻辑。
 *
 * 这样设计有两个好处：
 * 1. 结果可解释——用户能看到"平台理解成了什么"，理解错了可以手动改筛选条；
 * 2. 不引入第二套检索链路——解析只是"帮用户填表"，后续分页/排序/筛选全走原逻辑，
 *    因此 AI 挂掉时这个入口退化成普通搜索，而不是整个搜索页不可用。
 */
async function runNlSearch() {
  const text = nlQuery.value.trim()
  if (!text) {
    return
  }
  nlLoading.value = true
  try {
    const slots = await aiApi.parseSearch(text)
    applySlots(slots)
    nlDirty.value = false
    nlExplanation.value = slots.source === 'rule'
      ? `规则解析：${slots.explanation || '未启用模型'}`
      : `AI 理解：${slots.explanation || '已按你的描述筛选'}`
    nlSnapshot.value = {
      text,
      slots: {
        keyword: query.keyword,
        categoryId: query.categoryId,
        minPrice: query.minPrice,
        maxPrice: query.maxPrice,
        conditionLevel: query.conditionLevel,
        tradeType: query.tradeType
      },
      logId: slots.logId || null
    }
    // 直接用已回填好的 query 查询，不走 search()——否则会把"AI 自己改的"记成用户改动
    query.pageNum = 1
    load(true)
  } catch (e) {
    // 提示由 http.js 处理
  } finally {
    nlLoading.value = false
  }
}

/** 槽位回填：只覆盖被解析出来的字段，其余保持用户原有选择 */
function applySlots(slots) {
  if (slots.keyword) query.keyword = slots.keyword
  if (slots.categoryId) query.categoryId = slots.categoryId
  if (slots.minPrice != null) query.minPrice = Number(slots.minPrice)
  if (slots.maxPrice != null) query.maxPrice = Number(slots.maxPrice)
  if (slots.conditionLevel) query.conditionLevel = slots.conditionLevel
  if (slots.tradeType != null) query.tradeType = slots.tradeType
  if (slots.sortBy) query.sortBy = slots.sortBy
}

/**
 * 检索改写反馈：
 * <b>只在用户解析之后手动改过筛选条件时才上报</b>。
 * 如果"没改"也算采纳，这个指标就恒等于 100%——那是最典型的自欺指标。
 * 因此用 {@link nlDirty} 标记"用户确实动过手"，动过才对比最终条件与解析结果。
 */
function recordNlFeedback() {
  const snapshot = nlSnapshot.value
  if (!snapshot || !nlDirty.value) {
    return
  }
  const now = {
    keyword: query.keyword,
    categoryId: query.categoryId,
    minPrice: query.minPrice,
    maxPrice: query.maxPrice,
    conditionLevel: query.conditionLevel,
    tradeType: query.tradeType
  }
  const accepted = JSON.stringify(now) === JSON.stringify(snapshot.slots)
  aiApi
    .feedback({
      suggestionType: 'search_query',
      accepted,
      suggestedValue: snapshot.text,
      finalValue: query.keyword || snapshot.text,
      logId: snapshot.logId
    })
    .catch(() => {})
  // 一条改写只上报一次，避免同一快照重复计数
  nlSnapshot.value = null
  nlDirty.value = false
}

function clearNl() {
  nlQuery.value = ''
  nlExplanation.value = ''
  nlSnapshot.value = null
  nlDirty.value = false
}

// 离开页面时补一次反馈：只有在用户确实手动调整过筛选条件时才会上报
onBeforeUnmount(() => {
  recordNlFeedback()
})
</script>

<style scoped>
.filter .row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

/* 自然语言检索行：与下方结构化筛选条视觉上分层，强调"说一句话"与"改条件"的关系 */
.nl-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 12px;
  padding-bottom: 12px;
  border-bottom: 1px dashed var(--el-border-color);
}

.nl-explain {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--el-color-primary);
}

.filter .dash {
  color: var(--bobo-text-placeholder);
}

.total {
  margin-left: 10px;
  font-size: 13px;
  font-weight: 400;
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
  .grid {
    grid-template-columns: repeat(2, 1fr);
    gap: 10px;
  }
}
</style>
