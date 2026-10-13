import { defineStore } from 'pinia'
import { authApi, messageApi, noticeApi } from '@/api'
import { getToken, setToken } from '@/api/http'

/**
 * 登录态与全局角标。
 * token 存 localStorage（刷新不掉线），用户信息每次进站拉一次 /api/auth/me。
 */
export const useUserStore = defineStore('user', {
  state: () => ({
    token: getToken(),
    user: null,
    /** 未读私信数 */
    unreadMessage: 0,
    /** 未读通知数 */
    unreadNotice: 0,
    /** 是否已经拉取过用户信息 */
    loaded: false
  }),

  getters: {
    isLogin: (state) => !!state.token && !!state.user,
    isAdmin: (state) => state.user?.role === 'admin',
    nickname: (state) => state.user?.nickname || '未登录',
    userId: (state) => state.user?.userId || null
  },

  actions: {
    async login(payload) {
      const data = await authApi.login(payload)
      this.token = data.token
      this.user = data.user
      this.loaded = true
      setToken(data.token)
      await this.refreshBadges()
      return data.user
    },

    async register(payload) {
      const data = await authApi.register(payload)
      this.token = data.token
      this.user = data.user
      this.loaded = true
      setToken(data.token)
      return data.user
    },

    /** 拉取当前用户；未登录或失效时清空状态，不抛错 */
    async fetchUser(force = false) {
      if (!this.token) {
        this.user = null
        this.loaded = true
        return null
      }
      if (this.loaded && !force && this.user) {
        return this.user
      }
      try {
        this.user = await authApi.me()
        this.loaded = true
        return this.user
      } catch (e) {
        this.token = ''
        this.user = null
        this.loaded = true
        setToken('')
        return null
      }
    },

    /** 局部更新用户资料（改完昵称/头像后不必重新请求） */
    patchUser(partial) {
      this.user = { ...(this.user || {}), ...partial }
    },

    /** 刷新未读角标（顶栏红点） */
    async refreshBadges() {
      if (!this.token) {
        this.unreadMessage = 0
        this.unreadNotice = 0
        return
      }
      try {
        const [msg, notice] = await Promise.all([
          messageApi.unreadCount(),
          noticeApi.unreadCount()
        ])
        this.unreadMessage = msg?.count || 0
        this.unreadNotice = notice?.count || 0
      } catch (e) {
        // 角标失败不打扰用户
      }
    },

    logout() {
      this.token = ''
      this.user = null
      this.loaded = true
      this.unreadMessage = 0
      this.unreadNotice = 0
      setToken('')
      authApi.logout().catch(() => {})
    }
  }
})
