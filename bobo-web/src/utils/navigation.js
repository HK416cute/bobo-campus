/**
 * 路由重定向工具：统一处理「登录后跳回原页面」的路径。
 *
 * <p>项目部署在子路径 `/bobo-campus/` 下时，redirect 参数可能来自三种地方：
 * <ul>
 *   <li>路由守卫的 {@code to.fullPath}：形如 {@code /me}，不含 base；</li>
 *   <li>401 拦截器的 {@code window.location.pathname}：形如 {@code /bobo-campus/me}，含 base；</li>
 *   <li>用户手工粘贴的完整 URL。</li>
 * </ul>
 * 如果直接把这些值交给 {@code router.replace()}，含 base 的路径会被 Vue Router
 * 再拼一次 base，变成 {@code /bobo-campus/bobo-campus/me}，进入 404。
 *
 * <p>本工具做三件事：去掉站点 base、只允许站内路径、禁止跳回登录页造成循环。
 * 这样登录后不会再先闪一次 404。
 *
 * @author bobo
 */

const BASE = (import.meta.env.BASE_URL || '/').replace(/\/+$/, '')

/** 去掉路径开头的站点 base；根路径返回 '/' */
export function stripBasePath(pathname) {
  if (!pathname) {
    return '/'
  }
  let path = pathname
  if (BASE && (path === BASE || path.startsWith(`${BASE}/`))) {
    path = path.slice(BASE.length) || '/'
  }
  return path.startsWith('/') ? path : `/${path}`
}

/**
 * 把任意 redirect 参数归一化成 Vue Router 可安全使用的站内路径。
 *
 * @param {string|string[]|undefined|null} raw 原始 redirect
 * @param {string} fallback 无有效值时返回的路径
 */
export function safeRedirect(raw, fallback = '/') {
  let value = Array.isArray(raw) ? raw[0] : raw
  if (typeof value !== 'string' || !value.trim()) {
    return fallback
  }
  value = value.trim()

  // 1. 完整 URL → 只取 pathname + search
  if (/^https?:\/\//i.test(value)) {
    try {
      const url = new URL(value)
      value = `${url.pathname}${url.search}${url.hash}`
    } catch (e) {
      return fallback
    }
  }

  // 2. 只接受站内绝对路径；协议相对 //evil.com 直接拒绝
  if (!value.startsWith('/') || value.startsWith('//')) {
    return fallback
  }

  // 3. 去掉站点 base，避免 router 二次拼接
  value = stripBasePath(value)

  // 4. 不要把用户送回登录页，避免登录成功后原地打转
  if (value === '/login' || value.startsWith('/login?')
      || value === '/admin/login' || value.startsWith('/admin/login?')) {
    return fallback
  }

  return value || fallback
}

/** 当前地址（去掉站点 base），供 401 拦截器生成 redirect */
export function currentAppPath() {
  return stripBasePath(window.location.pathname) + window.location.search
}
