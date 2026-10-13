<template>
  <div class="reviews-view">
    <div class="bobo-card filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索评价内容 / 商品标题"
        clearable
        class="filter-item keyword"
        @keyup.enter="search"
        @clear="search"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="query.status" class="filter-item" placeholder="全部状态" @change="search">
        <el-option label="全部状态" :value="''" />
        <el-option label="显示中" :value="0" />
        <el-option label="已隐藏" :value="1" />
      </el-select>

      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>

    <div class="bobo-card">
      <div class="bobo-section-title no-margin">评价列表</div>

      <el-table v-loading="loading" :data="list" border stripe row-key="reviewId">
        <el-table-column label="商品" min-width="180">
          <template #default="{ row }">
            <a class="item-title ellipsis" :href="`/item/${row.itemId}`" target="_blank" rel="noopener">
              {{ row.itemTitle || '-' }}
            </a>
            <div class="bobo-muted sub">订单 {{ row.orderId || '-' }}</div>
          </template>
        </el-table-column>

        <el-table-column label="评价人" width="130">
          <template #default="{ row }">
            <div class="user-cell">
              <el-avatar :size="26" :src="row.fromAvatar || ''">{{ (row.fromNickname || '用').slice(0, 1) }}</el-avatar>
              <span class="ellipsis">{{ row.fromNickname || '-' }}</span>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="被评价人" width="120" prop="toNickname" show-overflow-tooltip />

        <el-table-column label="角色" width="90" align="center">
          <template #default="{ row }">
            <span class="bobo-tag" :class="row.role === 'seller' ? '' : 'info'">
              {{ row.role === 'seller' ? '卖家' : '买家' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="评分" width="150">
          <template #default="{ row }">
            <el-rate :model-value="Number(row.score) || 0" disabled show-score score-template="{value} 分" />
          </template>
        </el-table-column>

        <el-table-column label="评价内容" min-width="220">
          <template #default="{ row }">
            <div class="content ellipsis-2">{{ row.content || '（未填写文字评价）' }}</div>
          </template>
        </el-table-column>

        <el-table-column label="评价时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <span class="bobo-tag" :class="row.status === 1 ? 'danger' : 'success'">
              {{ row.status === 1 ? '已隐藏' : '显示中' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 0" link type="warning" @click="hide(row)">隐藏</el-button>
            <el-button v-else link type="success" @click="show(row)">显示</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="bobo-empty">暂无评价数据</div>
        </template>
      </el-table>

      <el-pagination
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        class="pager"
        @size-change="search"
        @current-change="loadList"
      />
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'

/**
 * 评价管理。隐藏比删除更常用（先止血再决定是否删除），所以两个动作都给运营留着。
 */
const loading = ref(false)
const list = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  status: ''
})

function formatTime(value) {
  return value || '-'
}

async function loadList() {
  loading.value = true
  try {
    const params = { ...query }
    if (params.status === '' || params.status === null) {
      delete params.status
    }
    const data = await adminApi.reviews(params)
    list.value = data?.records || []
    total.value = data?.total || 0
  } catch (e) {
    // 错误提示由 http.js 统一处理
  } finally {
    loading.value = false
  }
}

function search() {
  query.pageNum = 1
  loadList()
}

function reset() {
  query.keyword = ''
  query.status = ''
  query.pageNum = 1
  loadList()
}

async function hide(row) {
  try {
    await ElMessageBox.confirm('确定隐藏该条评价吗？隐藏后前台不再展示。', '隐藏评价', { type: 'warning' })
  } catch (e) {
    return
  }

  try {
    await adminApi.hideReview(row.reviewId)
    ElMessage.success('评价已隐藏')
    loadList()
  } catch (e) {
    // 错误提示由 http.js 统一处理
  }
}

async function show(row) {
  try {
    await adminApi.showReview(row.reviewId)
    ElMessage.success('评价已恢复显示')
    loadList()
  } catch (e) {
    // 错误提示由 http.js 统一处理
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm('确定删除该条评价吗？删除后不可恢复。', '删除评价', {
      type: 'warning',
      confirmButtonText: '确定删除'
    })
  } catch (e) {
    return
  }

  try {
    await adminApi.removeReview(row.reviewId)
    ElMessage.success('评价已删除')
    loadList()
  } catch (e) {
    // 错误提示由 http.js 统一处理
  }
}

onMounted(loadList)
</script>

<style scoped>
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.filter-item {
  width: 160px;
}

.filter-item.keyword {
  width: 280px;
}

.no-margin {
  margin: 0 0 12px;
}

.item-title {
  display: block;
  font-weight: 600;
  color: var(--bobo-text);
}

.item-title:hover {
  color: var(--bobo-accent);
}

.sub {
  font-size: 12px;
}

.user-cell {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.content {
  color: var(--bobo-text-regular);
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}
</style>
