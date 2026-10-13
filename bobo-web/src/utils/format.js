/**
 * 展示层格式化工具。
 * 只放「纯函数 + 常量字典」，不放任何请求逻辑，方便单测与复用。
 */

/** 商品状态字典：与后端 Item 常量一致 */
export const ITEM_STATUS = {
  0: { text: '待审核', type: 'warning' },
  1: { text: '在售', type: 'success' },
  2: { text: '已售', type: 'info' },
  3: { text: '已下架', type: 'info' },
  4: { text: '已驳回', type: 'danger' }
}

/** 订单状态字典：与后端 Order 常量一致 */
export const ORDER_STATUS = {
  0: { text: '待付款', type: 'warning' },
  1: { text: '待发货', type: 'primary' },
  2: { text: '待收货', type: 'primary' },
  3: { text: '待评价', type: 'success' },
  4: { text: '已完成', type: 'info' },
  5: { text: '已取消', type: 'info' },
  6: { text: '退款中', type: 'danger' },
  7: { text: '已退款', type: 'info' }
}

/** 交易方式 */
export const TRADE_TYPE = {
  0: '都支持',
  1: '仅当面交易',
  2: '仅邮寄'
}

/** 成色选项（发布表单与筛选共用） */
export const CONDITION_OPTIONS = ['全新', '99新', '95新', '9成新', '8成新', '7成新及以下']

/** 学生认证状态 */
export const VERIFY_STATUS = {
  0: { text: '未认证', type: 'info' },
  1: { text: '认证中', type: 'warning' },
  2: { text: '已认证', type: 'success' },
  3: { text: '已驳回', type: 'danger' }
}

export function itemStatusText(status) {
  return ITEM_STATUS[status]?.text || '未知'
}

export function orderStatusText(status) {
  return ORDER_STATUS[status]?.text || '未知'
}

/** 金额展示：后端返回字符串金额，这里统一补成两位小数 */
export function money(value) {
  if (value === null || value === undefined || value === '') {
    return '0.00'
  }
  const num = Number(value)
  if (Number.isNaN(num)) {
    return String(value)
  }
  return num.toFixed(2)
}

/** 价格整数部分与小数部分拆开，方便首页做「大字价格」排版 */
export function priceParts(value) {
  const text = money(value)
  const [int, decimal] = text.split('.')
  return { int, decimal }
}

/** 时间：null 显示 '-'，只保留到分钟 */
export function formatTime(value, withSeconds = false) {
  if (!value) {
    return '-'
  }
  const text = String(value).replace('T', ' ')
  return withSeconds ? text.slice(0, 19) : text.slice(0, 16)
}

/** 相对时间：刚刚 / 5 分钟前 / 3 小时前 / 2 天前 / 具体日期 */
export function fromNow(value) {
  if (!value) {
    return ''
  }
  const time = new Date(String(value).replace(' ', 'T')).getTime()
  if (Number.isNaN(time)) {
    return formatTime(value)
  }
  const diff = Date.now() - time
  const minute = 60 * 1000
  const hour = 60 * minute
  const day = 24 * hour

  if (diff < minute) {
    return '刚刚'
  }
  if (diff < hour) {
    return `${Math.floor(diff / minute)} 分钟前`
  }
  if (diff < day) {
    return `${Math.floor(diff / hour)} 小时前`
  }
  if (diff < 30 * day) {
    return `${Math.floor(diff / day)} 天前`
  }
  return formatTime(value).slice(0, 10)
}

/** 内置占位图与品牌图：都放在 public/ 下，构建时原样拷贝 */
export const DEFAULT_COVER = '/img/img-default-cover.svg'
export const LOGO = '/logo.svg'

/** 商品封面兜底 */
export function coverOf(item) {
  return item?.cover || DEFAULT_COVER
}

/** 头像兜底：没有头像时用「随机底色 + 昵称首字」的 data-uri，避免额外请求 */
export function avatarOf(user) {
  const avatar = typeof user === 'string' ? user : user?.avatar
  if (avatar) {
    return avatar
  }
  const name = (typeof user === 'object' && user?.nickname) || 'b'
  const char = String(name).slice(0, 1)
  const colors = ['#ffd100', '#a8d8ff', '#ffc9c9', '#c9e8c9', '#e6d1ff', '#ffe0b2']
  const color = colors[char.charCodeAt(0) % colors.length]
  const svg = `<svg xmlns="http://www.w3.org/2000/svg" width="120" height="120">
    <rect width="120" height="120" rx="60" fill="${color}"/>
    <text x="60" y="78" font-size="52" font-family="PingFang SC,Microsoft YaHei,sans-serif"
      font-weight="600" text-anchor="middle" fill="#1f2129">${char}</text>
  </svg>`
  return `data:image/svg+xml;utf8,${encodeURIComponent(svg)}`
}

/** 手机号打码（订单详情里展示双方联系方式） */
export function maskPhone(phone) {
  if (!phone || phone.length < 7) {
    return phone || '-'
  }
  return `${phone.slice(0, 3)}****${phone.slice(-4)}`
}

/** 把秒数倒计时格式化成 mm:ss（订单待付款倒计时用） */
export function countdown(seconds) {
  const total = Math.max(0, Math.floor(seconds))
  const m = String(Math.floor(total / 60)).padStart(2, '0')
  const s = String(total % 60).padStart(2, '0')
  return `${m}:${s}`
}

/** 防抖 */
export function debounce(fn, delay = 300) {
  let timer = null
  return (...args) => {
    clearTimeout(timer)
    timer = setTimeout(() => fn(...args), delay)
  }
}
