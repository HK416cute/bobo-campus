import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

/**
 * 路由表。
 * meta.requiresAuth  需要登录
 * meta.requiresAdmin 需要管理员
 * meta.activeMenu    底部导航高亮项：home / messages / orders / me
 * meta.title         浏览器标题
 */
const routes = [
  // ---------------------------------------------------------------- 前台
  {
    path: '/',
    component: () => import('@/layouts/AppLayout.vue'),
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/views/HomeView.vue'),
        meta: { title: '首页', activeMenu: 'home' }
      },
      {
        path: 'search',
        name: 'search',
        component: () => import('@/views/SearchView.vue'),
        meta: { title: '搜索', activeMenu: 'home' }
      },
      {
        path: 'item/:id',
        name: 'item-detail',
        component: () => import('@/views/ItemDetailView.vue'),
        meta: { title: '商品详情', activeMenu: 'home' }
      },
      {
        path: 'publish',
        name: 'publish',
        component: () => import('@/views/PublishView.vue'),
        meta: { title: '发布闲置', activeMenu: 'publish', requiresAuth: true }
      },
      {
        path: 'item/:id/edit',
        name: 'item-edit',
        component: () => import('@/views/PublishView.vue'),
        meta: { title: '编辑商品', activeMenu: 'me', requiresAuth: true }
      },
      {
        path: 'messages',
        name: 'messages',
        component: () => import('@/views/MessagesView.vue'),
        meta: { title: '消息', activeMenu: 'messages', requiresAuth: true }
      },
      {
        path: 'chat/:id',
        name: 'chat',
        component: () => import('@/views/ChatView.vue'),
        meta: { title: '聊天', activeMenu: 'messages', requiresAuth: true }
      },
      {
        path: 'orders',
        name: 'orders',
        component: () => import('@/views/OrdersView.vue'),
        meta: { title: '我的订单', activeMenu: 'orders', requiresAuth: true }
      },
      {
        path: 'order/:id',
        name: 'order-detail',
        component: () => import('@/views/OrderDetailView.vue'),
        meta: { title: '订单详情', activeMenu: 'orders', requiresAuth: true }
      },
      {
        path: 'favorites',
        name: 'favorites',
        component: () => import('@/views/FavoritesView.vue'),
        meta: { title: '我的想要', activeMenu: 'me', requiresAuth: true }
      },
      {
        path: 'my-items',
        name: 'my-items',
        component: () => import('@/views/MyItemsView.vue'),
        meta: { title: '我的发布', activeMenu: 'me', requiresAuth: true }
      },
      {
        path: 'notices',
        name: 'notices',
        component: () => import('@/views/NoticesView.vue'),
        meta: { title: '消息通知', activeMenu: 'me', requiresAuth: true }
      },
      {
        path: 'me',
        name: 'me',
        component: () => import('@/views/MineView.vue'),
        meta: { title: '我的', activeMenu: 'me', requiresAuth: true }
      },
      {
        path: 'me/profile',
        name: 'me-profile',
        component: () => import('@/views/ProfileEditView.vue'),
        meta: { title: '编辑资料', activeMenu: 'me', requiresAuth: true }
      },
      {
        path: 'me/addresses',
        name: 'me-addresses',
        component: () => import('@/views/AddressView.vue'),
        meta: { title: '收货地址', activeMenu: 'me', requiresAuth: true }
      },
      {
        path: 'user/:id',
        name: 'user-home',
        component: () => import('@/views/UserHomeView.vue'),
        meta: { title: 'TA 的主页', activeMenu: 'home' }
      }
    ]
  },

  // ---------------------------------------------------------------- 登录注册（无底部导航）
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录', guestOnly: true }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { title: '注册', guestOnly: true }
  },

  // ---------------------------------------------------------------- 管理后台
  {
    path: '/admin/login',
    name: 'admin-login',
    component: () => import('@/views/admin/AdminLoginView.vue'),
    meta: { title: '管理后台登录' }
  },
  {
    path: '/admin',
    component: () => import('@/layouts/AdminLayout.vue'),
    meta: { requiresAdmin: true },
    children: [
      {
        path: '',
        redirect: '/admin/dashboard'
      },
      {
        path: 'dashboard',
        name: 'admin-dashboard',
        component: () => import('@/views/admin/DashboardView.vue'),
        meta: { title: '数据看板', requiresAdmin: true, icon: 'DataLine' }
      },
      {
        path: 'items',
        name: 'admin-items',
        component: () => import('@/views/admin/ItemsView.vue'),
        meta: { title: '商品管理', requiresAdmin: true, icon: 'Goods' }
      },
      {
        path: 'categories',
        name: 'admin-categories',
        component: () => import('@/views/admin/CategoriesView.vue'),
        meta: { title: '分类管理', requiresAdmin: true, icon: 'Menu' }
      },
      {
        path: 'orders',
        name: 'admin-orders',
        component: () => import('@/views/admin/OrdersView.vue'),
        meta: { title: '订单管理', requiresAdmin: true, icon: 'List' }
      },
      {
        path: 'users',
        name: 'admin-users',
        component: () => import('@/views/admin/UsersView.vue'),
        meta: { title: '用户管理', requiresAdmin: true, icon: 'User' }
      },
      {
        path: 'reviews',
        name: 'admin-reviews',
        component: () => import('@/views/admin/ReviewsView.vue'),
        meta: { title: '评价管理', requiresAdmin: true, icon: 'ChatDotSquare' }
      },
      {
        path: 'reports',
        name: 'admin-reports',
        component: () => import('@/views/admin/ReportsView.vue'),
        meta: { title: '举报处理', requiresAdmin: true, icon: 'Warning' }
      },
      {
        path: 'notices',
        name: 'admin-notices',
        component: () => import('@/views/admin/NoticesView.vue'),
        meta: { title: '公告管理', requiresAdmin: true, icon: 'Bell' }
      },
      {
        path: 'ai',
        name: 'admin-ai',
        component: () => import('@/views/admin/AiView.vue'),
        meta: { title: 'AI 运营', requiresAdmin: true, icon: 'MagicStick' }
      }
    ]
  },

  // ---------------------------------------------------------------- 兜底
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {
      return savedPosition
    }
    return { top: 0 }
  }
})

router.beforeEach(async (to) => {
  document.title = to.meta?.title ? `${to.meta.title} · bobo 校园二手` : 'bobo 校园二手'

  const userStore = useUserStore()
  const needAuth = to.matched.some((r) => r.meta?.requiresAuth)
  const needAdmin = to.matched.some((r) => r.meta?.requiresAdmin)

  if (needAuth || needAdmin) {
    const user = await userStore.fetchUser()
    if (!user) {
      ElMessage.warning('请先登录')
      const loginPath = needAdmin ? '/admin/login' : '/login'
      return { path: loginPath, query: { redirect: to.fullPath } }
    }
    if (needAdmin && user.role !== 'admin') {
      ElMessage.error('需要管理员账号')
      return { path: '/' }
    }
  }

  // 已登录用户不再需要看登录/注册页
  if (to.meta?.guestOnly && userStore.token) {
    const user = await userStore.fetchUser()
    if (user) {
      return { path: '/' }
    }
  }

  return true
})

export default router
