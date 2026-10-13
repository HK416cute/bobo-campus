import axios from 'axios'
import { ElMessage, ElMessageBox } from 'element-plus'

/**
 * 统一 HTTP 客户端。
 *
 * 契约（见 docs/api.md）：
 *   - 响应体固定为 { code, msg, data }
 *   - code === 200 才算成功；401 统一跳登录；其它错误弹 message
 *   之所以不用 axios 的 HTTP 状态码判断，是因为后端始终返回 200，
 *   业务错误码在 body 里，这样前端只需要一处判断逻辑。
 */
const http = axios.create({
  baseURL: '/',
  timeout: 20000
})

const TOKEN_KEY = 'bobo_token'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

/** 401 只弹一次，避免并发请求刷屏 */
let redirecting = false

http.interceptors.request.use((config) => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    // 文件流等非标准响应直接放行
    if (body === null || body === undefined || typeof body !== 'object') {
      return body
    }
    if (body.code === 200) {
      return body
    }
    if (body.code === 401) {
      handleUnauthorized(body.msg)
      return Promise.reject(new Error(body.msg || '登录状态已失效'))
    }
    ElMessage.error(body.msg || '操作失败')
    return Promise.reject(new Error(body.msg || '操作失败'))
  },
  (error) => {
    const message = error?.response?.status === 404
      ? '接口不存在，请检查后端是否已启动'
      : (error?.message || '网络异常，请稍后重试')
    ElMessage.error(message)
    return Promise.reject(error)
  }
)

function handleUnauthorized(msg) {
  setToken('')
  if (redirecting) {
    return
  }
  redirecting = true
  ElMessageBox.alert(msg || '登录状态已失效，请重新登录', '提示', {
    confirmButtonText: '去登录',
    type: 'warning',
    callback: () => {
      redirecting = false
      const redirect = encodeURIComponent(window.location.pathname + window.location.search)
      window.location.href = `/login?redirect=${redirect}`
    }
  }).catch(() => {
    redirecting = false
  })
}

export default http

/** 常用增删改查快捷方法（返回 data 本身，省掉一层 .data.data） */
export const request = {
  get: (url, params, config = {}) => http.get(url, { params, ...config }).then((r) => r.data),
  post: (url, data, config = {}) => http.post(url, data, config).then((r) => r.data),
  put: (url, data, config = {}) => http.put(url, data, config).then((r) => r.data),
  del: (url, params, config = {}) => http.delete(url, { params, ...config }).then((r) => r.data),
  /** 上传：file 或 FileList */
  upload: (url, formData) =>
    http.post(url, formData, { headers: { 'Content-Type': 'multipart/form-data' } }).then((r) => r.data)
}
