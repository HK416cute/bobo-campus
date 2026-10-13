<template>
  <div class="categories-view">
    <div class="bobo-card">
      <div class="table-bar">
        <div>
          <div class="bobo-section-title no-margin">分类管理</div>
          <div class="bobo-muted tip">分类用于前台筛选与发布商品，停用后前台不再展示。</div>
        </div>
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          <span>新增分类</span>
        </el-button>
      </div>

      <el-table v-loading="loading" :data="list" border stripe row-key="categoryId">
        <el-table-column label="图标" width="80" align="center">
          <template #default="{ row }">
            <span class="cat-icon">{{ row.icon || '📦' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="名称" min-width="160" prop="name" show-overflow-tooltip />

        <el-table-column label="排序" width="90" align="center" prop="sort" />

        <el-table-column label="状态" width="100" align="center">
          <template #default="{ row }">
            <span class="bobo-tag" :class="row.status === 0 ? 'success' : 'info'">
              {{ row.status === 0 ? '启用' : '停用' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="在售商品数" width="120" align="center">
          <template #default="{ row }">{{ row.itemCount || 0 }}</template>
        </el-table-column>

        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <div class="bobo-empty">还没有分类，先新增一个吧</div>
        </template>
      </el-table>
    </div>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑分类' : '新增分类'" width="460px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="分类名称" prop="name">
          <el-input v-model="form.name" maxlength="16" show-word-limit placeholder="例如：数码产品" />
        </el-form-item>

        <el-form-item label="图标" prop="icon">
          <el-input v-model="form.icon" maxlength="4" placeholder="粘贴一个 emoji，例如 📱" />
        </el-form-item>

        <el-form-item label="排序" prop="sort">
          <el-input-number v-model="form.sort" :min="0" :max="999" controls-position="right" />
          <span class="bobo-muted sort-tip">数字越小越靠前</span>
        </el-form-item>

        <el-form-item label="状态">
          <el-switch
            v-model="form.status"
            :active-value="0"
            :inactive-value="1"
            active-text="启用"
            inactive-text="停用"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'

/**
 * 分类管理。
 * 分类数据量小（几十条），一次全量拉取，不做分页。
 */
const loading = ref(false)
const saving = ref(false)
const list = ref([])

const dialogVisible = ref(false)
const isEdit = ref(false)
const editingId = ref(null)
const formRef = ref(null)

const form = reactive({
  name: '',
  icon: '',
  sort: 0,
  status: 0
})

const rules = {
  name: [
    { required: true, message: '请输入分类名称', trigger: 'blur' },
    { max: 16, message: '分类名称最长 16 个字', trigger: 'blur' }
  ]
}

async function loadList() {
  loading.value = true
  try {
    list.value = await adminApi.categories()
  } catch (e) {
    // 错误提示由 http.js 统一处理
  } finally {
    loading.value = false
  }
}

function resetForm() {
  form.name = ''
  form.icon = ''
  form.sort = 0
  form.status = 0
}

function openCreate() {
  isEdit.value = false
  editingId.value = null
  resetForm()
  // 新分类默认排到最后
  form.sort = list.value.length ? Math.max(...list.value.map((it) => it.sort || 0)) + 1 : 1
  dialogVisible.value = true
}

function openEdit(row) {
  isEdit.value = true
  editingId.value = row.categoryId
  form.name = row.name
  form.icon = row.icon || ''
  form.sort = row.sort || 0
  form.status = row.status ?? 0
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
    const payload = { name: form.name, icon: form.icon, sort: form.sort, status: form.status }
    if (isEdit.value) {
      await adminApi.updateCategory(editingId.value, payload)
      ElMessage.success('分类已更新')
    } else {
      await adminApi.createCategory(payload)
      ElMessage.success('分类已创建')
    }
    dialogVisible.value = false
    loadList()
  } catch (e) {
    // 错误提示由 http.js 统一处理
  } finally {
    saving.value = false
  }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除分类「${row.name}」吗？该分类下还有 ${row.itemCount || 0} 件商品，请先确认已迁移。`,
      '删除分类',
      { type: 'warning', confirmButtonText: '确定删除' }
    )
  } catch (e) {
    return
  }

  try {
    await adminApi.removeCategory(row.categoryId)
    ElMessage.success('分类已删除')
    loadList()
  } catch (e) {
    // 错误提示由 http.js 统一处理
  }
}

onMounted(loadList)
</script>

<style scoped>
.table-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
  gap: 12px;
  flex-wrap: wrap;
}

.no-margin {
  margin: 0;
}

.tip {
  margin-top: 4px;
  font-size: 12px;
}

.cat-icon {
  font-size: 20px;
}

.sort-tip {
  margin-left: 10px;
  font-size: 12px;
}
</style>
