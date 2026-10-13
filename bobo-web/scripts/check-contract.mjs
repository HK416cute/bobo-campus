/**
 * 前后端契约校验器。
 *
 * 解决的问题：前端 `src/api/index.js` 里手写的 URL 与后端 Controller 的
 * @RequestMapping 一旦对不上，只有点到那个功能才会 404，很容易漏测。
 * 这个脚本把两边都解析出来做集合比对，让路径不一致在提交前就暴露。
 *
 * 用法：node scripts/check-contract.mjs
 * 退出码：0 一致；1 有前端调用了后端不存在的路径（或重复定义）。
 */
import { readFileSync, readdirSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const webRoot = resolve(here, '..')
const serverRoot = resolve(webRoot, '..', 'bobo-server', 'src', 'main', 'java')

const problems = []

/** 把路径里的 {xxx} 归一化成 :param，便于比对 */
function normalize(path) {
  return path
    .replace(/\$\{[^}]+\}/g, ':param')      // 模板串里的 ${id}
    .replace(/\{[^}]+\}/g, ':param')         // Java 的 {itemId}
    .replace(/\/+$/, '')                     // 去掉结尾斜杠
    .replace(/\/{2,}/g, '/')
}

/** 收集后端所有路由：类级 @RequestMapping + 方法级注解 */
function collectServerRoutes() {
  const routes = new Map()
  const files = []
  const walk = (dir) => {
    for (const name of readdirSync(dir)) {
      const full = join(dir, name)
      if (statSync(full).isDirectory()) {
        walk(full)
      } else if (name.endsWith('.java')) {
        files.push(full)
      }
    }
  }
  walk(serverRoot)

  for (const file of files) {
    const code = readFileSync(file, 'utf8')
    const classMatch = code.match(/@RequestMapping\(\s*"([^"]+)"\s*\)/)
    const base = classMatch ? classMatch[1] : ''
    // 逐个匹配方法上的 @GetMapping/@PostMapping/@PutMapping/@DeleteMapping
    const methodPattern = /@(Get|Post|Put|Delete)Mapping(?:\(\s*(?:value\s*=\s*)?"([^"]*)"\s*\))?/g
    let match
    while ((match = methodPattern.exec(code))) {
      const method = match[1].toUpperCase()
      const sub = match[2] || ''
      let path = normalize(`${base}${sub}`)
      if (!path.startsWith('/')) {
        path = `/${path}`
      }
      const key = `${method} ${path}`
      if (routes.has(key)) {
        problems.push(`后端路由重复定义：${key}（${file}）`)
      }
      routes.set(key, file)
    }
  }
  return routes
}

/** 收集前端调用的路径：解析 request.get/post/put/del/upload 的第一个参数 */
function collectWebCalls() {
  const file = join(webRoot, 'src', 'api', 'index.js')
  const code = readFileSync(file, 'utf8')
  const calls = []
  const pattern = /request\.(get|post|put|del|upload)\(\s*(`[^`]*`|'[^']*')/g
  let match
  while ((match = pattern.exec(code))) {
    const verb = match[1]
    const method = verb === 'del' ? 'DELETE' : verb === 'upload' ? 'POST' : verb.toUpperCase()
    const raw = match[2].slice(1, -1)
    const path = normalize(raw.replace(/\$\{[^}]+\}/g, ':param'))
    calls.push({ method, path, raw })
  }
  return calls
}

const serverRoutes = collectServerRoutes()
const webCalls = collectWebCalls()

const missing = []
for (const call of webCalls) {
  // 上传接口：后端是 /api/upload/image 之类，路径里没有变量，直接比对
  const key = `${call.method} ${call.path}`
  const candidates = [
    key,
    // 前端用 :param 占位，后端同一位置可能是不同变量名，做一次宽松匹配
    ...[...serverRoutes.keys()].filter((k) => {
      const [m, p] = k.split(' ')
      if (m !== call.method) {
        return false
      }
      const a = p.split('/')
      const b = call.path.split('/')
      if (a.length !== b.length) {
        return false
      }
      return a.every((seg, i) => seg.startsWith('{') || seg === b[i])
    })
  ]
  if (!candidates.some((c) => serverRoutes.has(c))) {
    missing.push(call)
  }
}

console.log(`后端路由 ${serverRoutes.size} 条，前端调用 ${webCalls.length} 处`)

if (missing.length) {
  console.error(`\n前端调用了后端不存在的接口（${missing.length} 处）：`)
  missing.forEach((call) => console.error(`  ${call.method} ${call.raw}`))
  process.exit(1)
}

if (problems.length) {
  console.error(`\n${problems.join('\n')}`)
  process.exit(1)
}

console.log('契约校验通过：前端调用的每个路径都能在后端找到对应路由。')
