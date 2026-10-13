<template>
  <div class="app-layout">
    <!-- 顶栏：PC 上完整展示，移动端只保留搜索与图标 -->
    <header class="topbar">
      <div class="bobo-container inner">
        <router-link to="/" class="brand">
          <img :src="LOGO" alt="bobo" />
          <span class="name">bobo 校园二手</span>
        </router-link>

        <div class="search">
          <el-input
            v-model="keyword"
            placeholder="搜索课本、数码、宿舍神器…"
            clearable
            @keyup.enter="doSearch"
          >
            <template #append>
              <el-button :icon="Search" @click="doSearch" />
            </template>
          </el-input>
        </div>

        <nav class="actions">
          <router-link to="/publish" class="publish-btn">
            <el-icon><Plus /></el-icon>
            <span>发布闲置</span>
          </router-link>

          <router-link to="/messages" class="icon-btn" title="消息">
            <el-badge :value="userStore.unreadMessage" :hidden="!userStore.unreadMessage" :max="99">
              <el-icon :size="20"><ChatDotRound /></el-icon>
            </el-badge>
          </router-link>

          <router-link to="/notices" class="icon-btn" title="通知">
            <el-badge :value="userStore.unreadNotice" :hidden="!userStore.unreadNotice" :max="99">
              <el-icon :size="20"><Bell /></el-icon>
            </el-badge>
          </router-link>

          <el-dropdown v-if="userStore.isLogin" trigger="click" @command="onCommand">
            <span class="user">
              <img :src="avatarOf(userStore.user)" alt="" />
              <span class="ellipsis">{{ userStore.nickname }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="me" :icon="User">个人中心</el-dropdown-item>
                <el-dropdown-item command="my-items" :icon="Goods">我的发布</el-dropdown-item>
                <el-dropdown-item command="orders" :icon="List">我的订单</el-dropdown-item>
                <el-dropdown-item command="favorites" :icon="Star">我的想要</el-dropdown-item>
                <el-dropdown-item v-if="userStore.isAdmin" command="admin" :icon="Setting" divided>
                  管理后台
                </el-dropdown-item>
                <el-dropdown-item command="logout" :icon="SwitchButton" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>

          <router-link v-else to="/login" class="login-btn">登录 / 注册</router-link>
        </nav>
      </div>
    </header>

    <main class="main">
      <router-view v-slot="{ Component }">
        <keep-alive :include="['HomeView', 'SearchView']">
          <component :is="Component" />
        </keep-alive>
      </router-view>
    </main>

    <!-- 底部导航：仅移动端显示 -->
    <nav class="tabbar">
      <router-link to="/" :class="{ active: activeMenu === 'home' }">
        <el-icon :size="22"><HomeFilled /></el-icon>
        <span>首页</span>
      </router-link>
      <router-link to="/messages" :class="{ active: activeMenu === 'messages' }">
        <el-badge :value="userStore.unreadMessage" :hidden="!userStore.unreadMessage" :max="99">
          <el-icon :size="22"><ChatDotRound /></el-icon>
        </el-badge>
        <span>消息</span>
      </router-link>
      <router-link to="/publish" class="publish">
        <span class="circle"><el-icon :size="24"><Plus /></el-icon></span>
      </router-link>
      <router-link to="/orders" :class="{ active: activeMenu === 'orders' }">
        <el-icon :size="22"><List /></el-icon>
        <span>订单</span>
      </router-link>
      <router-link to="/me" :class="{ active: activeMenu === 'me' }">
        <el-icon :size="22"><User /></el-icon>
        <span>我的</span>
      </router-link>
    </nav>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Bell, ChatDotRound, Goods, HomeFilled, List, Plus, Search, Setting, Star, SwitchButton, User } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { LOGO, avatarOf } from '@/utils/format'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const keyword = ref('')
const activeMenu = computed(() => route.meta?.activeMenu || '')

onMounted(async () => {
  await userStore.fetchUser()
  userStore.refreshBadges()
})

function doSearch() {
  const q = keyword.value.trim()
  if (!q) {
    ElMessage.info('输入商品关键词再搜索')
    return
  }
  router.push({ path: '/search', query: { keyword: q } })
}

function onCommand(command) {
  if (command === 'logout') {
    userStore.logout()
    ElMessage.success('已退出登录')
    router.push('/')
    return
  }
  router.push(`/${command}`)
}
</script>

<style scoped>
.app-layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.topbar {
  position: sticky;
  top: 0;
  z-index: 100;
  background: linear-gradient(135deg, var(--bobo-primary), #ffe066);
  box-shadow: 0 2px 8px rgba(31, 33, 41, 0.08);
}

.topbar .inner {
  height: 60px;
  display: flex;
  align-items: center;
  gap: 16px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.brand img {
  width: 32px;
  height: 32px;
  border-radius: 8px;
}

.brand .name {
  font-size: 17px;
  font-weight: 800;
  color: var(--bobo-text);
  white-space: nowrap;
}

.search {
  flex: 1;
  max-width: 520px;
}

.search :deep(.el-input__wrapper) {
  border-radius: 999px 0 0 999px;
  box-shadow: none;
}

.search :deep(.el-input-group__append) {
  border-radius: 0 999px 999px 0;
  background: #fff;
  box-shadow: none;
}

.actions {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-left: auto;
}

.publish-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 34px;
  padding: 0 14px;
  border-radius: 999px;
  background: var(--bobo-text);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
}

.publish-btn:hover {
  background: #35384a;
}

.icon-btn {
  color: var(--bobo-text);
  display: inline-flex;
}

.user {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  max-width: 120px;
  color: var(--bobo-text);
  font-size: 13px;
  outline: none;
}

.user img {
  width: 26px;
  height: 26px;
  border-radius: 50%;
  object-fit: cover;
}

.login-btn {
  font-size: 13px;
  font-weight: 600;
  color: var(--bobo-text);
  white-space: nowrap;
}

.main {
  flex: 1;
}

.tabbar {
  display: none;
}

@media (max-width: 768px) {
  .brand .name,
  .publish-btn span,
  .user span,
  .icon-btn[title='通知'] {
    display: none;
  }

  .topbar .inner {
    height: 54px;
    gap: 10px;
  }

  .actions {
    gap: 10px;
  }

  .main {
    padding-bottom: 64px;
  }

  .tabbar {
    position: fixed;
    left: 0;
    right: 0;
    bottom: 0;
    z-index: 200;
    height: 58px;
    display: flex;
    align-items: center;
    justify-content: space-around;
    background: #fff;
    border-top: 1px solid var(--bobo-border);
    padding-bottom: env(safe-area-inset-bottom);
  }

  .tabbar a {
    flex: 1;
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 2px;
    font-size: 11px;
    color: var(--bobo-text-secondary);
  }

  .tabbar a.active {
    color: var(--bobo-text);
    font-weight: 600;
  }

  .tabbar .publish .circle {
    width: 42px;
    height: 42px;
    margin-top: -20px;
    border-radius: 50%;
    background: linear-gradient(135deg, var(--bobo-primary), var(--bobo-primary-deep));
    color: var(--bobo-text);
    display: flex;
    align-items: center;
    justify-content: center;
    box-shadow: 0 4px 12px rgba(255, 180, 0, 0.45);
  }
}
</style>
