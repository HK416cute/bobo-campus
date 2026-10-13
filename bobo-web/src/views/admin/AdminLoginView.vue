<template>
  <div class="admin-login">
    <div class="login-card">
      <div class="login-head">
        <span class="logo">bo</span>
        <h1>bobo 管理后台</h1>
        <p class="bobo-muted">校园二手交易平台 · 运营管理入口</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        @keyup.enter="submit"
      >
        <el-form-item label="管理员账号" prop="username">
          <el-input v-model="form.username" size="large" placeholder="请输入管理员账号" clearable>
            <template #prefix><el-icon><User /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-form-item label="密码" prop="password">
          <el-input
            v-model="form.password"
            type="password"
            size="large"
            placeholder="请输入密码"
            show-password
          >
            <template #prefix><el-icon><Lock /></el-icon></template>
          </el-input>
        </el-form-item>

        <el-button
          type="primary"
          size="large"
          class="submit-btn bobo-btn-primary"
          :loading="loading"
          @click="submit"
        >
          登录后台
        </el-button>
      </el-form>

      <div class="login-foot">
        <span class="bobo-muted">演示账号：admin / password</span>
        <el-link type="primary" :underline="false" @click="router.push('/')">返回前台</el-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

/**
 * 后台登录独立于前台登录页：入口分开，避免普通用户误入后台表单。
 */
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref(null)
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入管理员账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
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

  loading.value = true
  try {
    const user = await userStore.login({ username: form.username, password: form.password })
    if (user?.role !== 'admin') {
      ElMessage.warning('该账号不是管理员')
      userStore.logout()
      return
    }
    ElMessage.success('登录成功')
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : ''
    router.replace(redirect || '/admin/dashboard')
  } catch (e) {
    // 登录失败提示由 http.js 统一弹出
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.admin-login {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px;
  background: linear-gradient(160deg, var(--bobo-primary-light), var(--bobo-bg) 60%);
}

.login-card {
  width: 100%;
  max-width: 400px;
  padding: 32px 28px 20px;
  background: var(--bobo-card);
  border-radius: var(--bobo-radius-lg);
  box-shadow: var(--bobo-shadow-hover);
}

.login-head {
  text-align: center;
  margin-bottom: 24px;
}

.login-head .logo {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: var(--bobo-primary);
  color: var(--bobo-text);
  font-size: 20px;
  font-weight: 800;
}

.login-head h1 {
  margin: 12px 0 4px;
  font-size: 20px;
  color: var(--bobo-text);
}

.login-head p {
  margin: 0;
  font-size: 13px;
}

.submit-btn {
  width: 100%;
  margin-top: 4px;
}

.login-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px dashed var(--bobo-border);
  font-size: 12px;
}
</style>
