<template>
  <div class="auth-page">
    <div class="auth-card">
      <router-link to="/" class="brand">
        <img :src="LOGO" alt="bobo" />
        <span>bobo 校园二手</span>
      </router-link>

      <h1>欢迎回来</h1>
      <p class="sub">同校闲置，当面交易更放心</p>

      <el-form ref="formRef" :model="form" @submit.prevent>
        <el-form-item prop="username" :rules="[{ required: true, message: '请输入账号' }]">
          <el-input v-model="form.username" size="large" placeholder="账号 / 学号 / 手机号" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password" :rules="[{ required: true, message: '请输入密码' }]">
          <el-input
            v-model="form.password"
            size="large"
            type="password"
            show-password
            placeholder="密码"
            :prefix-icon="Lock"
            @keyup.enter="submit"
          />
        </el-form-item>

        <el-button type="primary" size="large" class="submit bobo-btn-primary" :loading="loading" @click="submit">
          登录
        </el-button>
      </el-form>

      <div class="links">
        <router-link to="/register">还没有账号？去注册</router-link>
        <router-link to="/admin/login">管理员登录入口</router-link>
      </div>

      <el-alert type="info" :closable="false" class="demo">
        <template #title>
          演示账号：<code>admin / password</code>（管理员）、<code>xiaoyu / password</code>（普通用户）
        </template>
      </el-alert>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, User } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { LOGO } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref(null)
const loading = ref(false)
const form = reactive({ username: '', password: '' })

async function submit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) {
    return
  }
  loading.value = true
  try {
    const user = await userStore.login({ ...form })
    ElMessage.success(`欢迎回来，${user.nickname}`)
    router.replace(route.query.redirect ? String(route.query.redirect) : '/')
  } catch (e) {
    // 提示由 http.js 处理
  } finally {
    loading.value = false
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
  max-width: 400px;
  background: #fff;
  border-radius: var(--bobo-radius-lg);
  padding: 32px 28px;
  box-shadow: 0 18px 48px rgba(31, 33, 41, 0.14);
}

.brand {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-weight: 800;
  font-size: 15px;
  margin-bottom: 20px;
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
  margin: 0 0 22px;
  color: var(--bobo-text-secondary);
  font-size: 13px;
}

.submit {
  width: 100%;
  margin-top: 4px;
}

.links {
  display: flex;
  justify-content: space-between;
  margin-top: 16px;
  font-size: 13px;
  color: var(--bobo-text-regular);
}

.demo {
  margin-top: 20px;
  border-radius: var(--bobo-radius);
}

.demo code {
  background: rgba(31, 33, 41, 0.06);
  padding: 1px 4px;
  border-radius: 4px;
}
</style>
