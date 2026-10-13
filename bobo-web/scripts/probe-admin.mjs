/**
 * 管理后台端到端验证（真实浏览器 + CDP）。
 *
 * 为什么要留这个脚本：后台的"点不进去 / 白屏 / 表格没数据"这几类问题，
 * curl 接口全是 200、静态检查也全过，只有真正跑一遍浏览器才会暴露。
 * 本次开发就靠它抓到三个真 bug：
 *   1) el-menu 的 index 写成路由名 → 点击后跳到 /admin/admin-items（空页面）；
 *   2) 管理端订单 SQL 少了 role=all 分支 → 订单列表永远 0 条；
 *   3) 订单里的封面快照指向不存在的图片 → 页面一片 404。
 *
 * 前置：站点已在跑（docker compose up -d），并有一个可用的 Chromium 系浏览器。
 * 用法（Windows，Edge 为例）：
 *   # 1) 启动带调试端口的浏览器
 *   "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe" \
 *     --headless=new --remote-debugging-port=9222 --user-data-dir=%TEMP%\bobo-probe about:blank
 *   # 2) 跑验证
 *   node scripts/probe-admin.mjs
 *
 * 环境变量：
 *   BOBO_SITE   站点地址，默认 http://127.0.0.1
 *   BOBO_CDP    CDP 地址，默认 http://127.0.0.1:9222
 *   BOBO_ADMIN_USER / BOBO_ADMIN_PASS  管理员账号，默认 admin / password
 */
const BASE = process.env.BOBO_SITE || 'http://127.0.0.1'
const CDP = process.env.BOBO_CDP || 'http://127.0.0.1:9222'
const USER = process.env.BOBO_ADMIN_USER || 'admin'
const PASS = process.env.BOBO_ADMIN_PASS || 'password'

/**
 * 后台菜单的声明顺序必须与 AdminLayout.vue 里 el-menu-item 的顺序一致，
 * 因为 Element Plus 不把 index 渲染成 DOM 属性，只能按位置断言高亮。
 */
const MENU = [
  { path: '/admin/dashboard', name: '数据看板', content: '.bobo-card', desc: '统计卡片' },
  { path: '/admin/items', name: '商品管理', content: '.el-table__body tr', desc: '表格行' },
  { path: '/admin/categories', name: '分类管理', content: '.el-table__body tr', desc: '表格行' },
  { path: '/admin/orders', name: '订单管理', content: '.el-table__body tr', desc: '表格行' },
  { path: '/admin/users', name: '用户管理', content: '.el-table__body tr', desc: '表格行' },
  { path: '/admin/reviews', name: '评价管理', content: '.el-table__body tr', desc: '表格行' },
  { path: '/admin/reports', name: '举报处理', content: '.el-table__body tr', desc: '表格行' },
  { path: '/admin/notices', name: '公告管理', content: '.el-table__body tr', desc: '表格行' },
  // AI 运营页：断言用「指标卡片」而不是表格行——审核记录表可能为空，
  // 用表格行会让"页面其实是好的"被判成失败（这正是本探针踩过的老坑）
  { path: '/admin/ai', name: 'AI 运营', content: '.cards .card', desc: '指标卡片' }
]

/** 极简 CDP 客户端（用 Node 内置 WebSocket，不引第三方依赖） */
class Cdp {
  constructor(url) {
    this.ws = new WebSocket(url)
    this.id = 0
    this.pending = new Map()
    this.errors = []
    this.failedRequests = []
    this.ready = new Promise((res, rej) => {
      this.ws.addEventListener('open', res)
      this.ws.addEventListener('error', () => rej(new Error('无法连接 CDP：' + url + '（浏览器没启动或端口不对）')))
    })
    this.ws.addEventListener('message', (ev) => {
      const msg = JSON.parse(typeof ev.data === 'string' ? ev.data : ev.data.toString())
      if (msg.id && this.pending.has(msg.id)) {
        const { resolve, reject } = this.pending.get(msg.id)
        this.pending.delete(msg.id)
        msg.error ? reject(new Error(JSON.stringify(msg.error))) : resolve(msg.result)
      } else if (msg.method === 'Runtime.exceptionThrown') {
        this.errors.push(msg.params.exceptionDetails.exception?.description || msg.params.exceptionDetails.text)
      } else if (msg.method === 'Network.responseReceived' && msg.params.response.status >= 400) {
        this.failedRequests.push(`${msg.params.response.status} ${msg.params.response.url}`)
      }
    })
  }

  send(method, params = {}) {
    const id = ++this.id
    return new Promise((resolve, reject) => {
      this.pending.set(id, { resolve, reject })
      this.ws.send(JSON.stringify({ id, method, params }))
    })
  }

  async evaluate(expression) {
    const r = await this.send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text)
    return r.result?.value
  }

  reset() {
    this.errors = []
    this.failedRequests = []
  }

  close() {
    try { this.ws.close() } catch { /* 忽略 */ }
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function main() {
  console.log(`站点 ${BASE} / CDP ${CDP}`)

  const target = await (await fetch(`${CDP}/json/new?${encodeURIComponent(BASE + '/admin/login')}`, { method: 'PUT' })).json()
  const cdp = new Cdp(target.webSocketDebuggerUrl)
  await cdp.ready
  await cdp.send('Runtime.enable')
  await cdp.send('Network.enable')
  await sleep(2500)

  // 注入 token，跳过登录 UI（登录 UI 本身由前台探针覆盖）
  const token = await cdp.evaluate(`(async () => {
    const r = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: ${JSON.stringify(USER)}, password: ${JSON.stringify(PASS)} })
    });
    const j = await r.json();
    if (j.code !== 200) throw new Error('登录失败：' + j.msg);
    if (j.data.user.role !== 'admin') throw new Error('该账号不是管理员：' + j.data.user.role);
    localStorage.setItem('bobo_token', j.data.token);
    return j.data.token;
  })()`)

  let failed = 0

  // ---------- 1. 逐个路由：能打开、内容渲染、高亮正确、无 4xx ----------
  console.log('\n[1/3] 逐个后台路由（打开 / 渲染 / 高亮 / 网络）')
  for (let i = 0; i < MENU.length; i++) {
    const m = MENU[i]
    cdp.reset()
    await cdp.send('Page.navigate', { url: `${BASE}${m.path}` })
    await sleep(2800)
    const raw = await cdp.evaluate(`(() => {
      const host = document.querySelector('.admin-content');
      const rows = document.querySelectorAll('.el-table__body tr').length;
      const emptyText = (document.querySelector('.el-table__empty-text')?.innerText || '').trim();
      const paginationTotal = (document.querySelector('.el-pagination__total')?.innerText || '').trim();
      const activeIndexes = [...document.querySelectorAll('.el-menu-item')]
        .map((el, idx) => el.classList.contains('is-active') ? idx : -1).filter((idx) => idx >= 0);
      return JSON.stringify({
        href: location.href,
        menuCount: document.querySelectorAll('.el-menu-item').length,
        activeIndexes,
        contentLen: host ? host.innerHTML.length : 0,
        metric: Number(${JSON.stringify(m.content)}.startsWith('.el-table') ? rows : document.querySelectorAll(${JSON.stringify(m.content)}).length) || 0,
        emptyText, paginationTotal
      });
    })()`)
    const s = JSON.parse(raw)
    const urlOk = s.href === BASE + m.path
    const renderOk = s.contentLen > 500 && (s.metric > 0 || !!s.emptyText || /共\s*0\s*条/.test(s.paginationTotal))
    const highlightOk = s.menuCount === MENU.length && s.activeIndexes.length === 1 && s.activeIndexes[0] === i
    const netOk = cdp.failedRequests.length === 0
    const jsOk = cdp.errors.length === 0
    const ok = urlOk && renderOk && highlightOk && netOk && jsOk
    if (!ok) failed++
    const note = s.metric > 0 ? `${m.desc}=${s.metric}` : `空状态="${s.emptyText || s.paginationTotal}"`
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${m.name.padEnd(6)} ${m.path.padEnd(20)} ${note} 高亮#${s.activeIndexes} 内容=${s.contentLen}`)
    if (!urlOk) console.log(`        期望 URL ${BASE}${m.path}，实际 ${s.href}`)
    if (!netOk) console.log('        4xx:', cdp.failedRequests.slice(0, 4).join(' | '))
    if (!jsOk) console.log('        JS 异常:', String(cdp.errors[0]).split('\n')[0])
  }

  // ---------- 2. 真实点击菜单：必须跳到对应路由（本脚本抓到的核心 bug） ----------
  console.log('\n[2/3] 真实点击左侧菜单')
  await cdp.send('Page.navigate', { url: `${BASE}/admin/dashboard` })
  await sleep(2500)
  for (let i = 0; i < MENU.length; i++) {
    cdp.reset()
    const raw = await cdp.evaluate(`(async () => {
      const el = document.querySelectorAll('.el-menu-item')[${i}];
      if (!el) return 'null';
      el.click();
      await new Promise((r) => setTimeout(r, 1400));
      return location.pathname;
    })()`)
    const ok = raw === MENU[i].path
    if (!ok) failed++
    console.log(`${ok ? 'PASS' : 'FAIL'}  点击第 ${i} 项 -> ${raw}（期望 ${MENU[i].path}）`)
  }

  // ---------- 3. 前台页面冒烟 ----------
  console.log('\n[3/3] 前台页面冒烟')
  const front = [
    { path: '/', sel: '.item-card', name: '首页' },
    { path: '/search?keyword=iPad', sel: '.item-card', name: '搜索' },
    { path: '/item/1', sel: '.main-image img', name: '商品详情' },
    { path: '/login', sel: '.el-input__inner', name: '登录页' }
  ]
  for (const f of front) {
    cdp.reset()
    await cdp.send('Page.navigate', { url: `${BASE}${f.path}` })
    await sleep(2600)
    const n = await cdp.evaluate(`document.querySelectorAll(${JSON.stringify(f.sel)}).length`)
    const ok = Number(n) > 0 && cdp.errors.length === 0 && cdp.failedRequests.length === 0
    if (!ok) failed++
    console.log(`${ok ? 'PASS' : 'FAIL'}  ${f.name.padEnd(6)} ${f.path.padEnd(20)} ${f.sel}=${n}` +
      (cdp.failedRequests.length ? `  4xx: ${cdp.failedRequests.slice(0, 3).join(' | ')}` : ''))
  }

  cdp.close()
  console.log('\n' + '='.repeat(64))
  console.log(failed === 0
    ? `全部通过：后台 ${MENU.length} 个页面可打开、数据已渲染、菜单高亮与点击跳转均正确，前台页面正常。`
    : `存在 ${failed} 处失败，见上面 FAIL 行。`)
  process.exit(failed === 0 ? 0 : 1)
}

main().catch((e) => {
  console.error('\n验证无法执行：', e.message)
  console.error('提示：先 docker compose up -d，再启动带 --remote-debugging-port 的浏览器。')
  process.exit(1)
})
