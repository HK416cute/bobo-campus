<template>
  <div class="users-view">
    <div class="bobo-card filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="搜索昵称 / 账号 / 手机号"
        clearable
        class="filter-item keyword"
        @keyup.enter="search"
        @clear="search"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
      </el-input>

      <el-select v-model="query.role" class="filter-item" placeholder="全部角色" @change="search">
        <el-option label="全部角色" :value="''" />
        <el-option label="普通用户" value="user" />
        <el-option label="管理员" value="admin" />
      </el-select>

      <el-select v-model="query.status" class="filter-item" placeholder="全部状态" @change="search">
        <el-option label="全部状态" :value="''" />
        <el-option label="正常" :value="0" />
        <el-option label="已封禁" :value="1" />
      </el-select>

      <el-select v-model="query.verifyStatus" class="filter-item" placeholder="全部认证" @change="search">
        <el-option label="全部认证" :value="''" />
        <el-option label="未认证" :value="0" />
        <el-option label="待审核" :value="1" />
        <el-option label="已认证" :value="2" />
        <el-option label="已驳回" :value="3" />
      </el-select>

      <el-button type="primary" @click="search">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </div>

    <div class="bobo-card">
      <div class="bobo-section-title no-margin">用户列表</div>

      <el-table v-loading="loading" :data="list" border stripe row-key="userId">
        <el-table-column label="头像" width="72" align="center">
          <template #default="{ row }">
            <el-avatar :size="36" :src="row.avatar || ''">{{ firstChar(row) }}</el-avatar>
          </template>
        </el-table-column>

        <el-table-column label="用户" min-width="170">
          <template #default="{ row }">
            <div class="nick-row">
              <a class="nick" :href="`/user/${row.userId}`" target="_blank" rel="noopener">
                {{ row.nickname || '未设置昵称' }}
              </a>
              <span v-if="row.role === 'admin'" class="bobo-tag">管理员</span>
            </div>
            <div class="bobo-muted sub">@{{ row.username }}</div>
          </template>
        </el-table-column>

        <el-table-column label="学院 / 年级" min-width="150">
          <template #default="{ row }">
            <div class="ellipsis">{{ row.college || '-' }}</div>
            <div class="bobo-muted sub">{{ row.grade || '-' }}</div>
          </template>
        </el-table-column>

        <el-table-column label="校区" width="120" prop="campus" show-overflow-tooltip />

        <el-table-column label="信用分" width="90" align="center">
          <template #default="{ row }">
            <span :class="['credit', (row.creditScore || 0) < 60 ? 'low' : '']">{{ row.creditScore ?? 100 }}</span>
          </template>
        </el-table-column>

        <el-table-column label="认证状态" width="100" align="center">
          <template #default="{ row }">
            <span class="bobo-tag" :class="verifyClass(row.verifyStatus)">
              {{ VERIFY_MAP[row.verifyStatus] || '未认证' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="账号状态" width="100" align="center">
          <template #default="{ row }">
            <span class="bobo-tag" :class="row.status === 1 ? 'danger' : 'success'">
              {{ row.status === 1 ? '已封禁' : '正常' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="注册时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>

        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="row.status === 1"
              link
              type="success"
              @click="openAction(row, 'unban')"
            >解封</el-button>
            <el-button v-else link type="danger" @click="openAction(row, 'ban')">封禁</el-button>

            <el-button
              v-if="row.verifyStatus === 1"
              link
              type="success"
              @click="openAction(row, 'verifyPass')"
            >认证通过</el-button>
            <el-button
              v-if="row.verifyStatus === 1"
              link
              type="warning"
              @click="openAction(row, 'verifyReject')"
            >认证驳回</el-button>

            <el-button link type="primary" @click="openAction(row, 'credit')">调整信用分</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="bobo-empty">暂无用户数据</div>
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

    <!-- 封禁/解封/认证/信用分共用一个弹窗，action 决定表单形态 -->
    <el-dialog v-model="dialogVisible" :title="DIALOG_TITLE[action]" width="460px">
      <div v-if="current" class="target-tip">
        用户：<strong>{{ current.nickname || current.username }}</strong>
        <span class="bobo-muted">（当前信用分 {{ current.creditScore ?? 100 }}）</span>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="88px">
        <el-form-item v-if="action === 'credit'" label="调整分值" prop="creditDelta">
          <el-input-number v-model="form.creditDelta" :min="-100" :max="100" :step="5" controls-position="right" />
          <span class="bobo-muted delta-tip">正数加分，负数扣分</span>
        </el-form-item>

        <el-form-item v-if="needsReason" label="原因" prop="reason">
          <el-input
            v-model="form.reason"
            type="textarea"
            :rows="3"
            maxlength="100"
            show-word-limit
            :placeholder="reasonPlaceholder"
          />
        </el-form-item>

        <el-form-item v-else label="提示">
          <span class="bobo-muted">确认后立即生效，用户会收到通知。</span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { adminApi } from '@/api'

/**
 * 用户管理。
 * 封禁、认证、信用分三个动作表单结构相似，合成一个弹窗，用 action 区分，减少重复代码。
 */
const VERIFY_MAP = {
  0: '未认证',
  1: '待审核',
  2: '已认证',
  3: '已驳回'
}

const DIALOG_TITLE = {
  ban: '封禁用户',
  unban: '解封用户',
  verifyPass: '认证通过',
  verifyReject: '认证驳回',
  credit: '调整信用分'
}

const loading = ref(false)
const saving = ref(false)
const list = ref([])
const total = ref(0)

const query = reactive({
  pageNum: 1,
  pageSize: 10,
  keyword: '',
  role: '',
  status: '',
  verifyStatus: ''
})

const dialogVisible = ref(false)
const action = ref('ban')
const current = ref(null)
const formRef = ref(null)
const form = reactive({ reason: '', creditDelta: 10 })

// 封禁、认证驳回必须留痕，解封和认证通过可以不填
const needsReason = computed(() => ['ban', 'verifyReject'].includes(action.value))

const reasonPlaceholder = computed(() => {
  if (action.value === 'ban') {
    return '例如：发布违规商品，多次提醒无效'
  }
  if (action.value === 'verifyReject') {
    return '例如：学生证照片不清晰，请重新上传'
  }
  return '请填写原因'
})

// rules 里只给需要必填的字段挂校验；未渲染的 el-form-item 不会参与校验，
// 所以信用分弹窗里没有原因输入框时也不会被 required 拦住
const rules = computed(() => {
  const base = {
    creditDelta: [{ required: true, message: '请填写调整分值', trigger: 'blur' }]
  }
  if (needsReason.value) {
    base.reason = [{ required: true, message: '请填写原因', trigger: 'blur' }]
  }
  return base
})

function firstChar(row) {
  return (row.nickname || row.username || '用').slice(0, 1)
}

function verifyClass(status) {
  if (status === 2) {
    return 'success'
  }
  if (status === 1) {
    return ''
  }
  if (status === 3) {
    return 'danger'
  }
  return 'info'
}

function formatTime(value) {
  return value || '-'
}

async function loadList() {
  loading.value = true
  try {
    const params = { ...query }
    Object.keys(params).forEach((key) => {
      if (params[key] === '' || params[key] === null) {
        delete params[key]
      }
    })
    const data = await adminApi.users(params)
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
  query.role = ''
  query.status = ''
  query.verifyStatus = ''
  query.pageNum = 1
  loadList()
}

function openAction(row, act) {
  current.value = row
  action.value = act
  form.reason = ''
  form.creditDelta = act === 'credit' ? 10 : 0
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

  const userId = current.value.userId
  saving.value = true
  try {
    if (action.value === 'ban') {
      await adminApi.changeUserStatus(userId, { status: 1, reason: form.reason })
      ElMessage.success('用户已封禁')
    } else if (action.value === 'unban') {
      await adminApi.changeUserStatus(userId, { status: 0, reason: form.reason })
      ElMessage.success('用户已解封')
    } else if (action.value === 'verifyPass') {
      await adminApi.auditVerify(userId, { verifyStatus: 2, reason: form.reason })
      ElMessage.success('认证已通过')
    } else if (action.value === 'verifyReject') {
      await adminApi.auditVerify(userId, { verifyStatus: 3, reason: form.reason })
      ElMessage.success('认证已驳回')
    } else if (action.value === 'credit') {
      await adminApi.changeCredit(userId, { creditDelta: form.creditDelta, reason: form.reason })
      ElMessage.success('信用分已调整')
    }
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

.filter-item.keyword {
  width: 240px;
}

.no-margin {
  margin: 0 0 12px;
}

.nick-row {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.nick {
  font-weight: 600;
  color: var(--bobo-text);
}

.nick:hover {
  color: var(--bobo-accent);
}

.sub {
  font-size: 12px;
}

.credit {
  font-weight: 700;
}

.credit.low {
  color: var(--bobo-accent);
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.target-tip {
  margin-bottom: 12px;
  color: var(--bobo-text-regular);
}

.delta-tip {
  margin-left: 10px;
  font-size: 12px;
}
</style>
