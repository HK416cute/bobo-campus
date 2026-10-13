<template>
  <div class="reports-view">
    <div class="bobo-card filter-bar">
      <el-select v-model="query.targetType" class="filter-item" placeholder="全部对象" @change="search">
        <el-option label="全部对象" :value="''" />
        <el-option label="商品" value="item" />
        <el-option label="用户" value="user" />
        <el-option label="评价" value="review" />
      </el-select>

      <el-select v-model="query.status" class="filter-item" placeholder="全部状态" @change="search">
        <el-option label="待处理" :value="0" />
        <el-option label="已处理" :value="1" />
        <el-option label="已驳回" :value="2" />
        <el-option label="全部状态" :value="''" />
      </el-select>

      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>

    <div class="bobo-card">
      <div class="bobo-section-title no-margin">举报列表</div>

      <el-table v-loading="loading" :data="list" border stripe row-key="reportId">
        <el-table-column label="举报人" width="120" prop="reporterNickname" show-overflow-tooltip />

        <el-table-column label="对象类型" width="100" align="center">
          <template #default="{ row }">
            <span class="bobo-tag" :class="typeClass(row.targetType)">{{ TYPE_MAP[row.targetType] || '未知' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="被举报对象" min-width="180">
          <template #default="{ row }">
            <span v-if="row.targetType === 'item' && row.targetId" class="target">
              <a class="target-link ellipsis" :href="`/item/${row.targetId}`" target="_blank" rel="noopener">
                {{ row.targetTitle || `商品 ${row.targetId}` }}
              </a>
            </span>
            <span v-else class="ellipsis">{{ row.targetTitle || `#${row.targetId || '-'}` }}</span>
          </template>
        </el-table-column>

        <el-table-column label="举报原因" min-width="140" prop="reason" show-overflow-tooltip />

        <el-table-column label="补充说明" min-width="160">
          <template #default="{ row }">
            <span class="bobo-muted">{{ row.detail || '-' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="举报时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <span class="bobo-tag" :class="statusClass(row.status)">{{ STATUS_MAP[row.status] || '未知' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="处理信息" min-width="170">
          <template #default="{ row }">
            <template v-if="row.status === 0">
              <span class="bobo-muted">待处理</span>
            </template>
            <template v-else>
              <div>{{ row.handleByName || '管理员' }} · {{ formatTime(row.handleTime) }}</div>
              <div class="bobo-muted sub">{{ row.handleRemark || '无处理说明' }}</div>
            </template>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button v-if="row.status === 0" link type="primary" @click="openHandle(row)">处理</el-button>
            <span v-else class="bobo-muted">已处理</span>
          </template>
        </el-table-column>

        <template #empty>
          <div class="bobo-empty">暂无举报数据</div>
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

    <el-dialog v-model="dialogVisible" title="处理举报" width="500px">
      <div v-if="current" class="report-summary">
        <div class="summary-line">
          <span class="bobo-muted">被举报对象：</span>
          <span>{{ TYPE_MAP[current.targetType] || '未知' }} · {{ current.targetTitle || `#${current.targetId}` }}</span>
        </div>
        <div class="summary-line">
          <span class="bobo-muted">举报原因：</span>
          <span>{{ current.reason || '-' }}</span>
        </div>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="认定结果" prop="handled">
          <el-radio-group v-model="form.handled">
            <el-radio :label="true">认定违规，通过举报</el-radio>
            <el-radio :label="false">不违规，驳回举报</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item label="处理说明" prop="remark">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="3"
            maxlength="150"
            show-word-limit
            placeholder="填写处理结论，会记录在案"
          />
        </el-form-item>

        <el-form-item v-if="current && current.targetType === 'item'" label="同步操作">
          <el-switch v-model="form.offShelfItem" active-text="同时下架被举报商品" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">提交处理</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api'

/**
 * 举报处理。默认只看待处理，运营打开页面就是待办队列。
 * 处理结果二选一（通过/驳回）与后端 handled 布尔字段对应。
 */
const TYPE_MAP = {
  item: '商品',
  user: '用户',
  review: '评价'
}

const STATUS_MAP = {
  0: '待处理',
  1: '已处理',
  2: '已驳回'
}

const loading = ref(false)
const saving = ref(false)
const list = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  targetType: '',
  status: 0
})

const dialogVisible = ref(false)
const current = ref(null)
const formRef = ref(null)
const form = reactive({ handled: true, remark: '', offShelfItem: false })

const rules = {
  handled: [{ required: true, message: '请选择认定结果', trigger: 'change' }],
  remark: [{ required: true, message: '请填写处理说明', trigger: 'blur' }]
}

function typeClass(type) {
  if (type === 'item') {
    return ''
  }
  if (type === 'user') {
    return 'danger'
  }
  return 'info'
}

function statusClass(status) {
  if (status === 1) {
    return 'success'
  }
  if (status === 2) {
    return 'info'
  }
  return 'danger'
}

function formatTime(value) {
  return value || '-'
}

async function loadList() {
  loading.value = true
  try {
    const params = { ...query }
    if (params.targetType === '' || params.targetType === null) {
      delete params.targetType
    }
    if (params.status === '' || params.status === null) {
      delete params.status
    }
    const data = await adminApi.reports(params)
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
  query.targetType = ''
  query.status = 0
  query.pageNum = 1
  loadList()
}

function openHandle(row) {
  current.value = row
  form.handled = true
  form.remark = ''
  form.offShelfItem = false
  dialogVisible.value = true
}

async function submit() {
  if (formRef.value) {
    try {
      await formRef.value.validate()
    } catch (e) {
      return
    }
  }

  saving.value = true
  try {
    await adminApi.handleReport(current.value.reportId, {
      handled: form.handled,
      remark: form.remark,
      // 只有商品类型的举报才需要这个字段，其它类型传 false，避免后端误判
      offShelfItem: current.value.targetType === 'item' ? form.offShelfItem : false
    })
    ElMessage.success('举报已处理')
    dialogVisible.value = false
    loadList()
  } catch (e) {
    // 错误提示由 http.js 统一处理
  } finally {
    saving.value = false
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
  width: 150px;
}

.no-margin {
  margin: 0 0 12px;
}

.target {
  display: block;
  min-width: 0;
}

.target-link {
  display: block;
  font-weight: 600;
  color: var(--bobo-text);
}

.target-link:hover {
  color: var(--bobo-accent);
}

.sub {
  font-size: 12px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.report-summary {
  padding: 10px 12px;
  margin-bottom: 14px;
  background: var(--bobo-bg);
  border-radius: var(--bobo-radius);
  font-size: 13px;
}

.summary-line + .summary-line {
  margin-top: 4px;
}
</style>
