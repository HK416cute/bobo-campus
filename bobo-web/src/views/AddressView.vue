<template>
  <div class="bobo-page">
    <div class="bobo-container narrow">
      <div class="bobo-card">
        <div class="head">
          <h2 class="page-title">收货地址</h2>
          <el-button type="primary" class="bobo-btn-primary" :icon="Plus" @click="openCreate">新增地址</el-button>
        </div>

        <div v-loading="loading">
          <div v-for="address in addresses" :key="address.addressId" class="address">
            <div class="body">
              <div class="line1">
                <strong>{{ address.receiver }}</strong>
                <span class="bobo-muted">{{ maskPhone(address.phone) }}</span>
                <el-tag v-if="address.isDefault === 1" size="small" type="success" round>默认</el-tag>
              </div>
              <p class="detail">
                <span v-if="address.campus">[{{ address.campus }}]</span> {{ address.detail }}
              </p>
            </div>
            <div class="actions">
              <el-button size="small" text @click="setDefault(address)">设为默认</el-button>
              <el-button size="small" text @click="openEdit(address)">编辑</el-button>
              <el-button size="small" text type="danger" @click="remove(address)">删除</el-button>
            </div>
          </div>

          <EmptyState v-if="!loading && !addresses.length" text="还没有收货地址，邮寄交易前先添加一个" />
        </div>
      </div>
    </div>

    <el-dialog v-model="visible" :title="editId ? '编辑地址' : '新增地址'" width="460px">
      <el-form ref="formRef" :model="form" label-width="80px">
        <el-form-item label="收货人" prop="receiver" :rules="[{ required: true, message: '请填写收货人' }]">
          <el-input v-model="form.receiver" maxlength="32" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone" :rules="[
          { required: true, message: '请填写手机号' },
          { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确' }
        ]">
          <el-input v-model="form.phone" maxlength="11" />
        </el-form-item>
        <el-form-item label="校区">
          <el-input v-model="form.campus" maxlength="32" placeholder="如：东校区" />
        </el-form-item>
        <el-form-item label="详细地址" prop="detail" :rules="[{ required: true, message: '请填写详细地址' }]">
          <el-input v-model="form.detail" type="textarea" :rows="2" maxlength="120" placeholder="如：3 号宿舍楼 412 室 / 菜鸟驿站 2 号柜" />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">取消</el-button>
        <el-button type="primary" class="bobo-btn-primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { addressApi } from '@/api'
import EmptyState from '@/components/EmptyState.vue'
import { maskPhone } from '@/utils/format'

const addresses = ref([])
const loading = ref(false)
const visible = ref(false)
const submitting = ref(false)
const editId = ref(null)
const formRef = ref(null)

const form = reactive({
  receiver: '',
  phone: '',
  campus: '',
  detail: '',
  isDefault: 0
})

onMounted(load)

async function load() {
  loading.value = true
  try {
    addresses.value = await addressApi.list()
  } catch (e) {
    addresses.value = []
  } finally {
    loading.value = false
  }
}

function reset() {
  form.receiver = ''
  form.phone = ''
  form.campus = ''
  form.detail = ''
  form.isDefault = addresses.value.length === 0 ? 1 : 0
}

function openCreate() {
  editId.value = null
  reset()
  visible.value = true
}

function openEdit(address) {
  editId.value = address.addressId
  form.receiver = address.receiver
  form.phone = address.phone
  form.campus = address.campus || ''
  form.detail = address.detail
  form.isDefault = address.isDefault
  visible.value = true
}

async function submit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    if (editId.value) {
      await addressApi.update(editId.value, { ...form })
      ElMessage.success('地址已更新')
    } else {
      await addressApi.create({ ...form })
      ElMessage.success('地址已保存')
    }
    visible.value = false
    load()
  } finally {
    submitting.value = false
  }
}

async function setDefault(address) {
  await addressApi.setDefault(address.addressId)
  ElMessage.success('已设为默认地址')
  load()
}

async function remove(address) {
  await ElMessageBox.confirm(`确定删除「${address.receiver}」的这条地址？`, '删除确认', { type: 'warning' })
  await addressApi.remove(address.addressId)
  ElMessage.success('已删除')
  load()
}
</script>

<style scoped>
.narrow {
  max-width: 720px;
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.page-title {
  margin: 0;
  font-size: 20px;
}

.address {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px solid var(--bobo-border);
}

.address .body {
  flex: 1;
  min-width: 0;
}

.line1 {
  display: flex;
  align-items: center;
  gap: 10px;
}

.detail {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--bobo-text-regular);
  line-height: 1.6;
}

.actions {
  display: flex;
  flex-shrink: 0;
}

@media (max-width: 640px) {
  .address {
    flex-direction: column;
    align-items: flex-start;
  }

  .actions {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
