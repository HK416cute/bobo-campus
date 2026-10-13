<template>
  <div class="items-view">
    <div class="bobo-card filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索商品标题 / 描述"
        clearable
        class="filter-item keyword"
        @keyup.enter="search"
        @clear="search"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="query.status" class="filter-item" placeholder="全部状态" @change="search">
        <el-option label="全部状态" :value="''" />
        <el-option v-for="(label, value) in STATUS_MAP" :key="value" :label="label" :value="Number(value)" />
      </el-select>

      <el-select v-model="query.categoryId" class="filter-item" placeholder="全部分类" clearable @change="search">
        <el-option label="全部分类" :value="''" />
        <el-option v-for="cat in categories" :key="cat.categoryId" :label="cat.name" :value="cat.categoryId" />
      </el-select>

      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>

    <div class="bobo-card">
      <!--
        待审核提示：默认列表是「全部状态」，待审核商品混在里面不显眼，
        这里显式提示并提供一键筛选，避免管理员漏掉需要处理的商品。
      -->
      <el-alert
        v-if="pendingCount > 0 && query.status !== 0"
        class="pending-alert"
        type="warning"
        :closable="false"
        show-icon
      >
        <template #title>
          有 <strong>{{ pendingCount }}</strong> 件商品等待审核
          <el-button link type="primary" class="pending-link" @click="onlyPending">只看待审核</el-button>
        </template>
      </el-alert>

      <div class="table-bar">
        <div class="bobo-section-title no-margin">商品列表</div>
        <div class="table-actions">
          <span v-if="selection.length" class="bobo-muted">已选 {{ selection.length }} 项</span>
          <el-button type="success" :disabled="!selection.length" @click="batchPass">批量通过审核</el-button>
          <el-button type="warning" :disabled="!selection.length" @click="batchOff">批量下架</el-button>
        </div>
      </div>

      <el-table
        v-loading="loading"
        :data="list"
        border
        stripe
        row-key="itemId"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="46" reserve-selection />

        <el-table-column label="封面" width="86">
          <template #default="{ row }">
            <el-image
              :src="row.cover || DEFAULT_COVER"
              :preview-src-list="[row.cover || DEFAULT_COVER]"
              preview-teleported
              fit="cover"
              class="cover"
            >
              <template #error>
                <div class="cover-fallback"><el-icon><Picture /></el-icon></div>
              </template>
            </el-image>
          </template>
        </el-table-column>

        <el-table-column label="标题" min-width="200">
          <template #default="{ row }">
            <div class="title-cell">
              <a class="item-title ellipsis" :href="`/item/${row.itemId}`" target="_blank" rel="noopener">
                {{ row.title }}
              </a>
              <div class="bobo-muted sub">
                <span>{{ row.tradeType === 2 ? '邮寄' : '当面交易' }}</span>
                <span v-if="row.conditionLevel"> · {{ row.conditionLevel }} 成新</span>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="分类" width="110" prop="categoryName" show-overflow-tooltip />

        <el-table-column label="价格" width="110">
          <template #default="{ row }">
            <span class="bobo-price">{{ row.price }}</span>
            <div v-if="row.originalPrice" class="bobo-muted origin">原价 ¥{{ row.originalPrice }}</div>
          </template>
        </el-table-column>

        <el-table-column label="卖家" width="130">
          <template #default="{ row }">
            <div class="ellipsis">{{ row.sellerNickname || '-' }}</div>
            <div class="bobo-muted sub">{{ row.campus || row.sellerCampus || '-' }}</div>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <span class="bobo-tag" :class="statusClass(row.status)">{{ STATUS_MAP[row.status] || '未知' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="浏览/想要" width="106">
          <template #default="{ row }">
            <span>{{ row.viewCount || 0 }} / {{ row.wantCount || 0 }}</span>
          </template>
        </el-table-column>

        <el-table-column label="发布时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 0" link type="success" @click="openAudit(row, true)">通过</el-button>
            <el-button v-if="row.status === 0" link type="danger" @click="openAudit(row, false)">驳回</el-button>
            <el-button v-if="row.status === 1" link type="warning" @click="offShelf(row)">下架</el-button>
            <el-button v-if="row.status === 3 || row.status === 4" link type="primary" @click="onShelf(row)">恢复上架</el-button>
            <el-button link type="danger" @click="removeItem(row)">删除</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="bobo-empty">暂无商品数据</div>
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

    <!-- 审核弹窗：通过/驳回共用，按 pass 切换是否需要填原因 -->
    <el-dialog v-model="auditVisible" :title="auditPass ? '审核通过' : '驳回商品'" width="460px">
      <div class="audit-tip">
        商品：<strong>{{ auditRow?.title }}</strong>
      </div>
      <el-form ref="auditFormRef" :model="auditForm" :rules="auditRules" label-width="70px">
        <el-form-item v-if="!auditPass" label="驳回原因" prop="reason">
          <el-input
            v-model="auditForm.reason"
            type="textarea"
            :rows="3"
            maxlength="100"
            show-word-limit
            placeholder="请填写驳回原因，会展示给卖家"
          />
        </el-form-item>
        <el-form-item v-else label="提示">
          <span class="bobo-muted">通过后商品立即上架，请确认商品信息合规。</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="auditVisible = false">取消</el-button>
        <el-button type="primary" :loading="auditLoading" @click="submitAudit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'
// 占位图必须走 withBase：站点部署在子路径下时，硬编码 '/img/…' 会指向站点根的 404
import { DEFAULT_COVER } from '@/utils/format'

/**
 * 商品管理。
 * 运营日常就是「审待审核的商品」，所以默认把 status 锁在 0，进页面即可处理。
 */
const STATUS_MAP = {
  0: '待审核',
  1: '在售',
  2: '已售',
  3: '已下架',
  4: '已驳回'
}

const loading = ref(false)
const list = ref([])
const total = ref(0)
const categories = ref([])
const selection = ref([])
/** 待审核商品数（顶部提示用） */
const pendingCount = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  /**
   * 默认「全部状态」而不是「待审核」。
   * 之前默认筛待审核：库里没有待审核商品时，管理员一进来就是一张空表，
   * 很容易被误判成页面坏了（实际是筛选条件生效）。默认看全部，
   * 待审核商品在列表里以醒目的状态标签 + 顶部筛选提示呈现，更容易发现。
   */
  status: '',
  categoryId: ''
})

const auditVisible = ref(false)
const auditLoading = ref(false)
const auditPass = ref(true)
const auditRow = ref(null)
const auditFormRef = ref(null)
const auditForm = reactive({ reason: '' })
const auditRules = {
  reason: [{ required: true, message: '请填写驳回原因', trigger: 'blur' }]
}

function statusClass(status) {
  if (status === 1) {
    return 'success'
  }
  if (status === 0) {
    return ''
  }
  if (status === 3 || status === 4) {
    return 'danger'
  }
  return 'info'
}

function formatTime(value) {
  return value || '-'
}

async function loadCategories() {
  try {
    categories.value = await adminApi.categories()
  } catch (e) {
    // 分类只用于筛选，失败时退化成不筛选
  }
}

/** 待审核数量：既用于顶部提示，也用于审核/批量操作后的刷新 */
async function loadPendingCount() {
  try {
    const data = await adminApi.pending()
    pendingCount.value = data?.itemPending || 0
  } catch (e) {
    pendingCount.value = 0
  }
}

async function loadList() {
  loading.value = true
  try {
    const params = { ...query }
    if (params.categoryId === '') {
      delete params.categoryId
    }
    if (params.status === '' || params.status === null) {
      delete params.status
    }
    const data = await adminApi.items(params)
    list.value = data?.records || []
    total.value = data?.total || 0
    // 列表每次刷新后同步待审核数量：审核通过/驳回/上架下架都会改变它，
    // 放在这里可以避免每个操作函数都记得手动刷新一次
    loadPendingCount()
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
  // 与初始状态保持一致：重置回「全部状态」，否则重置后会莫名只剩待审核
  query.status = ''
  query.categoryId = ''
  query.pageNum = 1
  loadList()
}

/** 顶部提示里的「只看待审核」快捷入口 */
function onlyPending() {
  query.status = 0
  query.pageNum = 1
  loadList()
}

function onSelectionChange(rows) {
  selection.value = rows
}

function openAudit(row, pass) {
  auditRow.value = row
  auditPass.value = pass
  auditForm.reason = ''
  auditVisible.value = true
}

async function submitAudit() {
  if (!auditPass.value && auditFormRef.value) {
    try {
      await auditFormRef.value.validate()
    } catch (e) {
      return
    }
  }
  auditLoading.value = true
  try {
    await adminApi.auditItem(auditRow.value.itemId, {
      pass: auditPass.value,
      reason: auditPass.value ? '' : auditForm.reason
    })
    ElMessage.success(auditPass.value ? '审核已通过' : '已驳回该商品')
    auditVisible.value = false
    loadList()
  } catch (e) {
    // 错误提示由 http.js 统一处理
  } finally {
    auditLoading.value = false
  }
}

async function offShelf(row) {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt('请填写下架原因（会通知卖家）', '下架商品', {
      confirmButtonText: '确定下架',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：疑似假冒品牌',
      inputValidator: (val) => (val && val.trim() ? true : '下架原因不能为空'),
      type: 'warning'
    })
    reason = value
  } catch (e) {
    return
  }

  try {
    await adminApi.offShelfItem(row.itemId, reason)
    ElMessage.success('商品已下架')
    loadList()
  } catch (e) {
    // 错误提示由 http.js 统一处理
  }
}

async function onShelf(row) {
  try {
    await adminApi.onShelfItem(row.itemId)
    ElMessage.success('商品已恢复上架')
    loadList()
  } catch (e) {
    // 错误提示由 http.js 统一处理
  }
}

async function removeItem(row) {
  try {
    await ElMessageBox.confirm(`确定删除商品「${row.title}」吗？删除后不可恢复。`, '删除商品', {
      type: 'warning',
      confirmButtonText: '确定删除'
    })
  } catch (e) {
    return
  }

  try {
    await adminApi.removeItem(row.itemId)
    ElMessage.success('商品已删除')
    loadList()
  } catch (e) {
    // 错误提示由 http.js 统一处理
  }
}

async function batchPass() {
  const ids = selection.value.map((row) => row.itemId)
  try {
    await ElMessageBox.confirm(`确定批量通过 ${ids.length} 件商品的审核吗？`, '批量审核', { type: 'warning' })
  } catch (e) {
    return
  }

  loading.value = true
  try {
    await adminApi.batchAudit(ids)
    ElMessage.success('批量审核已完成')
    loadList()
  } catch (e) {
    // 错误提示由 http.js 统一处理
  } finally {
    loading.value = false
  }
}

async function batchOff() {
  const ids = selection.value.map((row) => row.itemId)
  try {
    await ElMessageBox.confirm(`确定批量下架 ${ids.length} 件商品吗？`, '批量下架', { type: 'warning' })
  } catch (e) {
    return
  }

  loading.value = true
  try {
    await adminApi.batchOffShelf(ids)
    ElMessage.success('批量下架已完成')
    loadList()
  } catch (e) {
    // 错误提示由 http.js 统一处理
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadCategories()
  loadList()
  loadPendingCount()
})
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
  width: 240px;
}

.table-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  gap: 12px;
  flex-wrap: wrap;
}

.table-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.no-margin {
  margin: 0;
}

.cover {
  width: 56px;
  height: 56px;
  border-radius: 8px;
  cursor: zoom-in;
}

.cover-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 8px;
  background: var(--bobo-bg);
  color: var(--bobo-text-placeholder);
}

.title-cell {
  min-width: 0;
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

.origin {
  font-size: 12px;
  text-decoration: line-through;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.audit-tip {
  margin-bottom: 12px;
  color: var(--bobo-text-regular);
}
</style>
