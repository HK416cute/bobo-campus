<template>
  <div class="bobo-page">
    <div class="bobo-container narrow">
      <div class="bobo-card">
        <h2 class="page-title">编辑资料</h2>

        <el-form ref="formRef" :model="form" label-width="90px">
          <el-form-item label="头像">
            <div class="avatar-row">
              <img :src="avatarOf(form)" alt="" />
              <ImageUploader v-model="avatarList" :max="1" tip="建议上传正方形图片，不超过 10MB" />
            </div>
          </el-form-item>

          <el-form-item label="昵称" prop="nickname" :rules="[{ required: true, message: '请填写昵称' }]">
            <el-input v-model="form.nickname" maxlength="20" show-word-limit />
          </el-form-item>

          <el-form-item label="个性签名">
            <el-input v-model="form.bio" maxlength="60" show-word-limit placeholder="一句话介绍自己" />
          </el-form-item>

          <div class="grid-2">
            <el-form-item label="校区">
              <el-input v-model="form.campus" maxlength="32" placeholder="如：东校区" />
            </el-form-item>
            <el-form-item label="学院">
              <el-input v-model="form.college" maxlength="32" placeholder="如：计算机学院" />
            </el-form-item>
          </div>

          <div class="grid-2">
            <el-form-item label="年级">
              <el-input v-model="form.grade" maxlength="16" placeholder="如：2023" />
            </el-form-item>
            <el-form-item label="学号">
              <el-input v-model="form.studentNo" maxlength="32" placeholder="用于学生认证" />
            </el-form-item>
          </div>

          <div class="grid-2">
            <el-form-item label="手机号" prop="phone" :rules="phoneRule">
              <el-input v-model="form.phone" maxlength="11" />
            </el-form-item>
            <el-form-item label="邮箱" prop="email" :rules="emailRule">
              <el-input v-model="form.email" maxlength="64" />
            </el-form-item>
          </div>

          <div class="submit-row">
            <el-button @click="$router.back()">取消</el-button>
            <el-button type="primary" class="bobo-btn-primary" :loading="submitting" @click="submit">保存</el-button>
          </div>
        </el-form>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api'
import { useUserStore } from '@/stores/user'
import ImageUploader from '@/components/ImageUploader.vue'
import { avatarOf } from '@/utils/format'

const router = useRouter()
const userStore = useUserStore()

const formRef = ref(null)
const submitting = ref(false)
const avatarList = ref([])

const phoneRule = [
  { pattern: /^$|^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
]
const emailRule = [
  { type: 'email', message: '邮箱格式不正确', trigger: 'blur' }
]

const form = reactive({
  nickname: '',
  avatar: '',
  bio: '',
  campus: '',
  college: '',
  grade: '',
  studentNo: '',
  phone: '',
  email: ''
})

onMounted(async () => {
  const user = await userStore.fetchUser(true)
  if (!user) {
    router.replace('/login')
    return
  }
  Object.assign(form, {
    nickname: user.nickname || '',
    avatar: user.avatar || '',
    bio: user.bio || '',
    campus: user.campus || '',
    college: user.college || '',
    grade: user.grade || '',
    studentNo: user.studentNo || '',
    phone: user.phone || '',
    email: user.email || ''
  })
  if (form.avatar) {
    avatarList.value = [form.avatar]
  }
})

// 上传组件是数组语义，这里同步成单个 avatar 字段
watch(avatarList, (list) => {
  form.avatar = list[0] || ''
})

async function submit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  submitting.value = true
  try {
    const updated = await authApi.updateProfile({ ...form })
    userStore.patchUser(updated)
    ElMessage.success('资料已更新')
    router.push('/me')
  } finally {
    submitting.value = false
  }
}
</script>

<style scoped>
.narrow {
  max-width: 720px;
}

.page-title {
  margin: 0 0 20px;
  font-size: 20px;
}

.avatar-row {
  display: flex;
  align-items: center;
  gap: 16px;
}

.avatar-row > img {
  width: 72px;
  height: 72px;
  border-radius: 50%;
  object-fit: cover;
  flex-shrink: 0;
}

.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.submit-row {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
  margin-top: 8px;
}

@media (max-width: 640px) {
  .grid-2 {
    grid-template-columns: 1fr;
  }
}
</style>
