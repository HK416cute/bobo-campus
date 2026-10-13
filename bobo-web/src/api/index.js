import { request } from './http'

/**
 * 接口清单：与 docs/api.md 一一对应。
 * 约定：函数名用「动作 + 资源」，参数直接透传，不做二次包装。
 */

// ------------------------------------------------------------------ 认证与账号
export const authApi = {
  register: (data) => request.post('/api/auth/register', data),
  login: (data) => request.post('/api/auth/login', data),
  logout: () => request.post('/api/auth/logout'),
  me: () => request.get('/api/auth/me'),
  updateProfile: (data) => request.put('/api/auth/profile', data),
  changePassword: (data) => request.put('/api/auth/password', data),
  submitVerify: (data) => request.post('/api/auth/verify', data),
  overview: () => request.get('/api/auth/overview')
}

// ------------------------------------------------------------------ 分类
export const categoryApi = {
  list: () => request.get('/api/categories'),
  options: () => request.get('/api/categories/options')
}

// ------------------------------------------------------------------ 商品
export const itemApi = {
  page: (params) => request.get('/api/items', params),
  detail: (itemId) => request.get(`/api/items/${itemId}`),
  publish: (data) => request.post('/api/items', data),
  update: (itemId, data) => request.put(`/api/items/${itemId}`, data),
  remove: (itemId) => request.del(`/api/items/${itemId}`),
  offShelf: (itemId) => request.put(`/api/items/${itemId}/off-shelf`),
  onShelf: (itemId) => request.put(`/api/items/${itemId}/on-shelf`),
  mine: (params) => request.get('/api/my/items', params)
}

// ------------------------------------------------------------------ 上传
export const uploadApi = {
  /** 单张：返回 { url, name, size } */
  image: (file) => {
    const form = new FormData()
    form.append('file', file)
    return request.upload('/api/upload/image', form)
  },
  /** 多张：fileList 为 File[]，返回数组 */
  images: (files) => {
    const form = new FormData()
    Array.from(files).forEach((file) => form.append('files', file))
    return request.upload('/api/upload/images', form)
  }
}

// ------------------------------------------------------------------ 收藏
export const favoriteApi = {
  toggle: (itemId) => request.post(`/api/favorites/toggle/${itemId}`),
  page: (params) => request.get('/api/favorites', params),
  check: (itemId) => request.get(`/api/favorites/check/${itemId}`)
}

// ------------------------------------------------------------------ 私信
export const messageApi = {
  open: (data) => request.post('/api/messages/conversations', data),
  conversations: (params) => request.get('/api/messages/conversations', params),
  conversationCount: () => request.get('/api/messages/conversations/count'),
  enter: (id) => request.get(`/api/messages/conversations/${id}`),
  messages: (id, params) => request.get(`/api/messages/conversations/${id}/messages`, params),
  unreadMessages: (id) => request.get(`/api/messages/conversations/${id}/unread`),
  markRead: (id) => request.post(`/api/messages/conversations/${id}/read`),
  send: (data) => request.post('/api/messages/send', data),
  unreadCount: () => request.get('/api/messages/unread-count')
}

// ------------------------------------------------------------------ 订单
export const orderApi = {
  create: (data) => request.post('/api/orders', data),
  page: (params) => request.get('/api/orders', params),
  counts: () => request.get('/api/orders/counts'),
  detail: (orderId) => request.get(`/api/orders/${orderId}`),
  pay: (orderId) => request.post(`/api/orders/${orderId}/pay`),
  deliver: (orderId, data) => request.post(`/api/orders/${orderId}/deliver`, data),
  confirm: (orderId) => request.post(`/api/orders/${orderId}/confirm`),
  cancel: (orderId, data) => request.post(`/api/orders/${orderId}/cancel`, data || {}),
  refund: (orderId, data) => request.post(`/api/orders/${orderId}/refund`, data || {}),
  agreeRefund: (orderId) => request.post(`/api/orders/${orderId}/refund/agree`)
}

// ------------------------------------------------------------------ 评价
export const reviewApi = {
  submit: (data) => request.post('/api/reviews', data),
  page: (params) => request.get('/api/reviews', params),
  mine: (params) => request.get('/api/reviews/my', params)
}

// ------------------------------------------------------------------ 地址
export const addressApi = {
  list: () => request.get('/api/addresses'),
  create: (data) => request.post('/api/addresses', data),
  update: (id, data) => request.put(`/api/addresses/${id}`, data),
  remove: (id) => request.del(`/api/addresses/${id}`),
  setDefault: (id) => request.put(`/api/addresses/${id}/default`)
}

// ------------------------------------------------------------------ 通知
export const noticeApi = {
  page: (params) => request.get('/api/notices', params),
  broadcast: (limit = 5) => request.get('/api/notices/broadcast', { limit }),
  unreadCount: () => request.get('/api/notices/unread-count'),
  read: (id) => request.put(`/api/notices/${id}/read`),
  readAll: () => request.put('/api/notices/read-all')
}

// ------------------------------------------------------------------ 用户主页
export const userApi = {
  profile: (userId) => request.get(`/api/users/${userId}`),
  items: (userId, params) => request.get(`/api/users/${userId}/items`, params),
  reviews: (userId, limit = 10) => request.get(`/api/users/${userId}/reviews`, { limit })
}

// ------------------------------------------------------------------ 举报
export const reportApi = {
  submit: (data) => request.post('/api/reports', data),
  mine: (params) => request.get('/api/reports/my', params)
}

// ------------------------------------------------------------------ AI 能力
export const aiApi = {
  /** AI 是否可用（未配置 key 时 available=false，前端隐藏或禁用入口） */
  status: () => request.get('/api/ai/status'),
  /** 发布助手：草稿 → 结构化建议（标题/分类/成色/标签/价格建议/风险标记） */
  publishDraft: (data) => request.post('/api/ai/publish/draft', data),
  /** 检索意图解析：自然语言 → 结构化检索槽位 */
  parseSearch: (query) => request.post('/api/ai/search/parse', { query }),
  /** 采纳反馈：采纳率指标的采集入口 */
  feedback: (data) => request.post('/api/ai/feedback', data)
}

// ------------------------------------------------------------------ 管理端
export const adminApi = {
  // 看板
  dashboard: () => request.get('/api/admin/dashboard'),
  trend: (days = 7) => request.get('/api/admin/dashboard/trend', { days }),
  statusDistribution: () => request.get('/api/admin/dashboard/status'),
  categoryDistribution: () => request.get('/api/admin/dashboard/category'),
  pending: () => request.get('/api/admin/dashboard/pending'),

  // 商品
  items: (params) => request.get('/api/admin/items', params),
  auditItem: (itemId, data) => request.post(`/api/admin/items/${itemId}/audit`, data),
  offShelfItem: (itemId, reason) => request.put(`/api/admin/items/${itemId}/off-shelf`, { reason }),
  onShelfItem: (itemId) => request.put(`/api/admin/items/${itemId}/on-shelf`),
  removeItem: (itemId) => request.del(`/api/admin/items/${itemId}`),
  batchAudit: (ids) => request.put('/api/admin/items/batch/audit', { ids }),
  batchOffShelf: (ids) => request.put('/api/admin/items/batch/off-shelf', { ids }),

  // 用户
  users: (params) => request.get('/api/admin/users', params),
  userDetail: (userId) => request.get(`/api/admin/users/${userId}`),
  changeUserStatus: (userId, data) => request.put(`/api/admin/users/${userId}/status`, data),
  auditVerify: (userId, data) => request.put(`/api/admin/users/${userId}/verify`, data),
  changeCredit: (userId, data) => request.put(`/api/admin/users/${userId}/credit`, data),

  // 订单
  orders: (params) => request.get('/api/admin/orders', params),
  orderDetail: (orderId) => request.get(`/api/admin/orders/${orderId}`),

  // 评价
  reviews: (params) => request.get('/api/admin/reviews', params),
  hideReview: (id) => request.put(`/api/admin/reviews/${id}/hide`),
  showReview: (id) => request.put(`/api/admin/reviews/${id}/show`),
  removeReview: (id) => request.del(`/api/admin/reviews/${id}`),

  // 举报
  reports: (params) => request.get('/api/admin/reports', params),
  handleReport: (id, data) => request.put(`/api/admin/reports/${id}/handle`, data),

  // 分类
  categories: () => request.get('/api/admin/categories'),
  createCategory: (data) => request.post('/api/admin/categories', data),
  updateCategory: (id, data) => request.put(`/api/admin/categories/${id}`, data),
  removeCategory: (id) => request.del(`/api/admin/categories/${id}`),

  // 公告
  notices: (params) => request.get('/api/admin/notices', params),
  publishNotice: (data) => request.post('/api/admin/notices', data),
  removeNotice: (id) => request.del(`/api/admin/notices/${id}`),

  // AI
  aiMetrics: () => request.get('/api/admin/ai/metrics'),
  aiModerate: (data) => request.post('/api/admin/ai/moderate', data),
  aiModerationList: (params) => request.get('/api/admin/ai/moderation', params),
  aiHighRiskCount: () => request.get('/api/admin/ai/moderation/high-risk-count'),
  aiReport: (days = 7) => request.get('/api/admin/ai/report', { days }),
  aiAsk: (question) => request.post('/api/admin/ai/ask', { question })
}
