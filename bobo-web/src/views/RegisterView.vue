<template>
  <div class="auth-page">
    <div class="auth-card">
      <router-link to="/" class="brand">
        <img :src="LOGO" alt="bobo" />
        <span>bobo 校园二手</span>
      </router-link>

      <h1>加入 bobo</h1>
      <p class="sub">用学号注册，开启同校闲置交易</p>

      <el-form ref="formRef" :model="form" label-position="top">
        <el-form-item label="账号" prop="username" :rules="[
          { required: true, message: '请输入账号' },
          { pattern: /^[A-Za-z0-9_]{4,32}$/, message: '4-32 位字母、数字或下划线' }
        ]">
          <el-input v-model="form.username" placeholder="学号或手机号" :prefix-icon="User" />
        </el-form-item>

        <el-form-item label="昵称" prop="nickname" :rules="[{ required: true, message: '请输入昵称' }]">
          <el-input v-model="form.nickname" maxlength="20" placeholder="同学们怎么称呼你" />
        </el-form-item>

        <el-form-item label="密码" prop="password" :rules="[
          { required: true, message: '请输入密码' },
          { min: 6, max: 32, message: '密码长度 6-32 位' }
        ]">
          <el-input v-model="form.password" type="password" show-password :prefix-icon="Lock" />
        </el-form-item>

        <el-form-item label="确认密码" prop="confirm" :rules="[
          { required: true, message: '请再次输入密码' },
          { validator: validateConfirm, trigger: 'blur' }
        ]">
          <el-input v-model="form.confirm" type="password" show-password @keyup.enter="submit" />
        </el-form-item>

        <div class="grid-2">
          <el-form-item label="校区">
            <el-input v-model="form.campus" placeholder="如：东校区" maxlength="32" />
          </el-form-item>
          <el-form-item label="学院">
            <el-input v-model="form.college" placeholder="如：计算机学院" maxlength="32" />
          </el-form-item>
        </div>

        <div class="grid-2">
          <el-form-item label="年级">
            <el-input v-model="form.grade" placeholder="如：2023" maxlength="16" />
          </el-form-item>
          <el-form-item label="手机号（选填）" prop="phone" :rules="[
            { pattern: /^$|^1[3-9]\d{9}$/, message: '手机号格式不正确' }
          ]">
            <el-input v-model="form.phone" maxlength="11" />
          </el-form-item>
        </div>

        <el-form-item label="管理员邀请码（选填）" v-if="showAdminCode">
          <el-input v-model="form.adminCode" placeholder="仅创建管理员账号时需要" />
        </el-form-item>

        <el-button type="primary" size="large" class="submit bobo-btn-primary" :loading="loading" @click="submit">
          注册并进入
        </el-button>
      </el-form>

      <div class="links">
        <router-link to="/login">已有账号？去登录</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { LOGO } from '@/utils/format'

const router = useRouter()
const userStore = useUserStore()

const formRef = ref(null)
const loading = ref(false)
const showAdminCode = ref(false)
const form = reactive({
  username: '',
  nickname: '',
  password: '',
  confirm: '',
  campus: '',
  college: '',
  grade: '',
  phone: '',
  adminCode: ''
})

/** 连点 brand 5 次会露出管理员邀请码输入框，避免普通用户误操作 */
let taps = 0
function validateConfirm(rule, value, callback) {
  if (value !== form.password) {
    callback(new Error('两次输入的密码不一致'))
    return
  }
  callback()
}

async function submit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  loading.value = true
  try {
    const user = await userStore.register({
      username: form.username,
      password: form.password,
      nickname: form.nickname,
      campus: form.campus,
      college: form.college,
      grade: form.grade,
      phone: form.phone,
      adminCode: form.adminCode || undefined
    })
    ElMessage.success(`注册成功，欢迎 ${user.nickname}`)
    router.replace('/')
  } catch (e) {
    // 提示由 http.js 处理
  } finally {
    loading.value = false
  }
}

// 连点 5 次 Logo 露出管理员邀请码（运维创建管理员账号用）
function onLogoTap() {
  taps += 1
  if (taps >= 5) {
    showAdminCode.value = true
  }
}
</script>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: linear-gradient(135deg, #fffbe6 0%, #ffe9a8 60%, #ffd100 100%);
}

.auth-card {
  width: 100%;
  max-width: 460px;
  background: #fff;
  border-radius: var(--bobo-radius-lg);
  padding: 30px 28px;
  box-shadow: 0 18px 48px rgba(31, 33, 41, 0.14);
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-weight: 800;
  font-size: 15px;
  margin-bottom: 18px;
}

.brand img {
  width: 30px;
  height: 30px;
  border-radius: 8px;
}

h1 {
  margin: 0 0 4px;
  font-size: 24px;
}

.sub {
  margin: 0 0 18px;
  color: var(--bobo-text-secondary);
  font-size: 13px;
}

.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.submit {
  width: 100%;
  margin-top: 6px;
}

.links {
  margin-top: 14px;
  text-align: center;
  font-size: 13px;
  color: var(--bobo-text-regular);
}

@media (max-width: 520px) {
  .grid-2 {
    grid-template-columns: 1fr;
  }
}
</style>
