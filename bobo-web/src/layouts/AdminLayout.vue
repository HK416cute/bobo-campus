<template>
  <div class="admin-layout">
    <aside class="admin-aside" :class="{ collapsed }">
      <div class="admin-logo">
        <span class="dot">bo</span>
        <span v-show="!collapsed" class="name">bobo 管理后台</span>
      </div>

      <!--
        index 必须写**绝对路径**，不能写路由 name。
        el-menu 在 router 模式下执行的是 router.push(index)：若 index 是
        "admin-items" 这种名字，vue-router 会把它当**相对路径**拼到当前路由后面，
        在 /admin/dashboard 下就变成 /admin/admin-items → 匹配不上任何后台路由
        → 内容区空白。用 /admin/items 这样的绝对路径才与路由表对得上。
      -->
      <el-menu
        :default-active="activeMenu"
        router
        :collapse="collapsed"
        :collapse-transition="false"
        class="admin-menu"
      >
        <el-menu-item index="/admin/dashboard">
          <el-icon><DataLine /></el-icon>
          <template #title>数据看板</template>
        </el-menu-item>

        <el-menu-item index="/admin/items">
          <el-icon><Goods /></el-icon>
          <template #title>
            <span class="menu-title">商品管理</span>
            <el-badge v-if="pending.itemPending > 0" :value="pending.itemPending" :max="99" class="menu-badge" />
          </template>
        </el-menu-item>

        <el-menu-item index="/admin/categories">
          <el-icon><Menu /></el-icon>
          <template #title>分类管理</template>
        </el-menu-item>

        <el-menu-item index="/admin/orders">
          <el-icon><List /></el-icon>
          <template #title>
            <span class="menu-title">订单管理</span>
            <el-badge v-if="pending.refunding > 0" :value="pending.refunding" :max="99" class="menu-badge" />
          </template>
        </el-menu-item>

        <el-menu-item index="/admin/users">
          <el-icon><User /></el-icon>
          <template #title>用户管理</template>
        </el-menu-item>

        <el-menu-item index="/admin/reviews">
          <el-icon><ChatDotSquare /></el-icon>
          <template #title>评价管理</template>
        </el-menu-item>

        <el-menu-item index="/admin/reports">
          <el-icon><Warning /></el-icon>
          <template #title>
            <span class="menu-title">举报处理</span>
            <el-badge v-if="pending.reportPending > 0" :value="pending.reportPending" :max="99" class="menu-badge" />
          </template>
        </el-menu-item>

        <el-menu-item index="/admin/notices">
          <el-icon><Bell /></el-icon>
          <template #title>公告管理</template>
        </el-menu-item>

        <el-menu-item index="/admin/ai">
          <el-icon><MagicStick /></el-icon>
          <template #title>
            <span class="menu-title">AI 运营</span>
            <el-badge v-if="aiHighRisk > 0" :value="aiHighRisk" :max="99" class="menu-badge" type="warning" />
          </template>
        </el-menu-item>
      </el-menu>
    </aside>

    <section class="admin-main">
      <header class="admin-header">
        <div class="header-left">
          <el-button text class="fold-btn" @click="collapsed = !collapsed">
            <el-icon><Expand v-if="collapsed" /><Fold v-else /></el-icon>
          </el-button>
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/admin/dashboard' }">管理后台</el-breadcrumb-item>
            <el-breadcrumb-item>{{ pageTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>

        <div class="header-right">
          <el-button text @click="goFront">
            <el-icon><HomeFilled /></el-icon>
            <span>去前台</span>
          </el-button>
          <el-dropdown @command="onCommand">
            <span class="admin-user">
              <el-avatar :size="28" :src="userStore.user?.avatar || ''">
                {{ firstChar }}
              </el-avatar>
              <span class="nickname ellipsis">{{ userStore.nickname }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="front">返回前台首页</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <main class="admin-content">
        <router-view />
      </main>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { adminApi } from '@/api'
import { useUserStore } from '@/stores/user'

/**
 * 后台外壳。
 *
 * 菜单高亮的 key 与 index 一致，都是**绝对路径**（见模板里的说明）：
 * 用 route.path 直接比对，比维护一份「路由 name ↔ 菜单」映射更不容易错。
 */
const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const collapsed = ref(false)

const pending = reactive({ reportPending: 0, itemPending: 0, refunding: 0 })

/** AI 高风险内容数量：与举报红点并列展示，让管理员知道还有 AI 标出来的东西要看 */
const aiHighRisk = ref(0)

/** 所有后台菜单路径，按长度倒序，保证 /admin/reports 这类前缀相同的路径不会被误判 */
const MENU_PATHS = [
  '/admin/dashboard', '/admin/items', '/admin/categories', '/admin/orders',
  '/admin/users', '/admin/reviews', '/admin/reports', '/admin/notices', '/admin/ai'
].sort((a, b) => b.length - a.length)

/**
 * 当前高亮项：优先精确匹配；子页面（如 /admin/items/12，若有）回退到最长前缀匹配。
 * 显示层不依赖它做跳转，只影响高亮，因此前缀匹配是安全的。
 */
const activeMenu = computed(() => {
  const path = route.path
  if (MENU_PATHS.includes(path)) {
    return path
  }
  return MENU_PATHS.find((p) => path.startsWith(p + '/')) || '/admin/dashboard'
})

const pageTitle = computed(() => route.meta?.title || '数据看板')
const firstChar = computed(() => (userStore.nickname || '管').slice(0, 1))

async function loadPending() {
  try {
    const data = await adminApi.pending()
    Object.assign(pending, {
      reportPending: data?.reportPending || 0,
      itemPending: data?.itemPending || 0,
      refunding: data?.refunding || 0
    })
  } catch (e) {
    // 角标是锦上添花，失败就不显示，不打扰管理员
  }
  // AI 高风险数量单独请求：AI 未启用或 03-ai.sql 未执行时接口会返回 0，不影响其他角标
  try {
    aiHighRisk.value = (await adminApi.aiHighRiskCount()) || 0
  } catch (e) {
    aiHighRisk.value = 0
  }
}

function goFront() {
  router.push('/')
}

async function onCommand(command) {
  if (command === 'front') {
    goFront()
    return
  }
  if (command === 'logout') {
    try {
      await ElMessageBox.confirm('确定要退出管理后台吗？', '提示', { type: 'warning' })
    } catch (e) {
      return
    }
    userStore.logout()
    ElMessage.success('已退出登录')
    router.push('/admin/login')
  }
}

/** 窄屏自动折叠侧边栏，省得手动点 */
function syncCollapse() {
  collapsed.value = window.innerWidth < 900
}

onMounted(() => {
  syncCollapse()
  window.addEventListener('resize', syncCollapse)
  loadPending()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncCollapse)
})
</script>

<style scoped>
.admin-layout {
  display: flex;
  min-height: 100vh;
  background: var(--bobo-bg);
}

.admin-aside {
  flex: 0 0 210px;
  width: 210px;
  background: var(--bobo-card);
  border-right: 1px solid var(--bobo-border);
  transition: width 0.2s ease, flex-basis 0.2s ease;
  overflow: hidden;
}

.admin-aside.collapsed {
  flex-basis: 64px;
  width: 64px;
}

.admin-logo {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 56px;
  padding: 0 16px;
  border-bottom: 1px solid var(--bobo-border);
  white-space: nowrap;
}

.admin-logo .dot {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: var(--bobo-primary);
  color: var(--bobo-text);
  font-weight: 800;
  font-size: 13px;
}

.admin-logo .name {
  font-weight: 700;
  color: var(--bobo-text);
}

.admin-menu {
  border-right: none;
}

.admin-menu :deep(.el-menu-item.is-active) {
  background: var(--bobo-primary-light);
  color: var(--bobo-text);
  font-weight: 600;
}

.admin-menu :deep(.el-menu-item) {
  color: var(--bobo-text-regular);
}

.menu-title {
  margin-right: 16px;
}

.menu-badge {
  margin-left: 6px;
  vertical-align: middle;
}

.menu-badge :deep(.el-badge__content) {
  border: none;
}

.admin-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.admin-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 16px;
  background: var(--bobo-card);
  border-bottom: 1px solid var(--bobo-border);
  position: sticky;
  top: 0;
  z-index: 10;
}

.header-left,
.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.fold-btn {
  padding: 4px 6px;
}

.admin-user {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  color: var(--bobo-text);
  outline: none;
}

.admin-user .nickname {
  max-width: 120px;
  font-size: 13px;
}

.admin-content {
  flex: 1;
  padding: 16px;
  min-width: 0;
}
</style>
