<template>
  <div class="notices-view">
    <div class="bobo-card filter-bar">
      <el-select v-model="query.type" class="filter-item" placeholder="全部类型" @change="search">
        <el-option label="全部类型" :value="''" />
        <el-option v-for="(label, value) in TYPE_MAP" :key="value" :label="label" :value="value" />
      </el-select>

      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
      <div class="spacer"></div>
      <el-button type="primary" @click="openPublish">
        <el-icon><Plus /></el-icon>
        <span>发布公告</span>
      </el-button>
    </div>

    <div class="bobo-card">
      <div class="bobo-section-title no-margin">公告列表</div>

      <el-table v-loading="loading" :data="list" border stripe row-key="noticeId">
        <el-table-column label="标题" min-width="170" prop="title" show-overflow-tooltip />

        <el-table-column label="内容" min-width="240">
          <template #default="{ row }">
            <div class="content ellipsis-2">{{ row.content || '-' }}</div>
          </template>
        </el-table-column>

        <el-table-column label="接收对象" width="120">
          <template #default="{ row }">
            <span :class="row.userId === 0 ? 'bobo-tag' : 'bobo-tag info'">
              {{ row.userId === 0 ? '全站公告' : `用户 #${row.userId}` }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="类型" width="100" align="center">
          <template #default="{ row }">
            <span class="bobo-tag info">{{ TYPE_MAP[row.type] || row.type || '系统' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="发送时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="bobo-empty">暂无公告</div>
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

    <el-dialog v-model="dialogVisible" title="发布公告" width="540px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" maxlength="60" show-word-limit placeholder="例如：平台维护通知" />
        </el-form-item>

        <el-form-item label="内容" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="5"
            maxlength="500"
            show-word-limit
            placeholder="请输入公告正文"
          />
        </el-form-item>

        <el-form-item label="类型" prop="type">
          <el-select v-model="form.type" class="full">
            <el-option v-for="(label, value) in TYPE_MAP" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>

        <el-form-item label="接收对象" prop="scope">
          <el-radio-group v-model="form.scope">
            <el-radio label="all">全站公告</el-radio>
            <el-radio label="user">指定用户</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="form.scope === 'user'" label="用户 ID" prop="userId">
          <el-input-number v-model="form.userId" :min="1" :max="99999999" controls-position="right" />
          <span class="bobo-muted id-tip">填目标用户的 userId</span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">发布</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'

/**
 * 公告管理。
 * 后端约定 userId = 0 表示全站广播，所以「接收对象」在前端用 scope 单选映射成 userId 数字。
 */
const TYPE_MAP = {
  system: '系统',
  audit: '审核',
  order: '订单',
  chat: '聊天'
}

const loading = ref(false)
const saving = ref(false)
const list = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  type: ''
})

const dialogVisible = ref(false)
const formRef = ref(null)

const form = reactive({
  title: '',
  content: '',
  type: 'system',
  scope: 'all',
  userId: 1
})

const rules = {
  title: [
    { required: true, message: '请输入公告标题', trigger: 'blur' },
    { max: 60, message: '标题最长 60 个字', trigger: 'blur' }
  ],
  content: [
    { required: true, message: '请输入公告内容', trigger: 'blur' },
    { max: 500, message: '内容最长 500 个字', trigger: 'blur' }
  ],
  type: [{ required: true, message: '请选择公告类型', trigger: 'change' }],
  userId: [{ required: true, message: '请填写用户 ID', trigger: 'blur' }]
}

function formatTime(value) {
  return value || '-'
}

async function loadList() {
  loading.value = true
  try {
    const params = { ...query }
    if (params.type === '' || params.type === null) {
      delete params.type
    }
    const data = await adminApi.notices(params)
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
  query.type = ''
  query.pageNum = 1
  loadList()
}

function openPublish() {
  form.title = ''
  form.content = ''
  form.type = 'system'
  form.scope = 'all'
  form.userId = 1
  dialogVisible.value = true
}

async function submit() {
  if (!formRef.value) {
    return
  }
  try {
    await formRef.value.validate()
  } catch (e) {
    return
  }

  saving.value = true
  try {
    await adminApi.publishNotice({
      title: form.title,
      content: form.content,
      type: form.type,
      userId: form.scope === 'all' ? 0 : form.userId
    })
    ElMessage.success('公告已发布')
    dialogVisible.value = false
    search()
  } catch (e) {
    // 错误提示由 http.js 统一处理
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(`确定删除公告「${row.title}」吗？用户侧也将不再可见。`, '删除公告', {
      type: 'warning',
      confirmButtonText: '确定删除'
    })
  } catch (e) {
    return
  }

  try {
    await adminApi.removeNotice(row.noticeId)
    ElMessage.success('公告已删除')
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

.spacer {
  flex: 1;
}

.no-margin {
  margin: 0 0 12px;
}

.content {
  color: var(--bobo-text-regular);
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.full {
  width: 100%;
}

.id-tip {
  margin-left: 10px;
  font-size: 12px;
}
</style>
