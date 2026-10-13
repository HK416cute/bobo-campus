/**
 * 轻量静态校验器（不依赖任何 npm 包、不依赖构建工具链）。
 *
 * 背景：本仓库的交付环境里可能装不了 node_modules（离线/沙箱），
 * 但又必须自查「.vue 文件是否能通过编译」。于是做三件事：
 *   1. <script setup> 块去掉 import/export 后用 new Function 解析，捕获真实语法错误；
 *   2. 模板里的 {{ 表达式 }} 与 :attr / v-if 等指令值做同类解析，捕获模板表达式笔误；
 *   3. 校验标签配平、{ } ( ) 括号配平，捕获标签漏闭、括号漏闭这类高频错误。
 *
 * 用法：node scripts/check-static.mjs
 * 退出码：0 全部通过；1 存在错误（错误信息会逐条打印）。
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { execFileSync } from 'node:child_process'
import { dirname, extname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const projectRoot = resolve(here, '..')
const srcRoot = join(projectRoot, 'src')

const problems = []
const stats = { vue: 0, js: 0, css: 0 }

/** 递归收集文件 */
function walk(dir, out = []) {
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) {
      walk(full, out)
    } else {
      out.push(full)
    }
  }
  return out
}

/** 判断某个位置是否处于字符串/模板串/注释/正则里（简化版，够用即可） */
function isCodeAt(code, index) {
  let quote = null
  let inLineComment = false
  let inBlockComment = false
  let templateDepth = 0
  for (let i = 0; i < index; i++) {
    const ch = code[i]
    const next = code[i + 1]
    if (inLineComment) {
      if (ch === '\n') inLineComment = false
      continue
    }
    if (inBlockComment) {
      if (ch === '*' && next === '/') {
        inBlockComment = false
        i++
      }
      continue
    }
    if (quote) {
      if (ch === '\\') {
        i++
        continue
      }
      if (ch === quote) {
        quote = null
      } else if (quote === '`' && ch === '$' && next === '{') {
        templateDepth++
        i++
      } else if (quote === '`' && ch === '}' && templateDepth > 0) {
        templateDepth--
      }
      continue
    }
    if (ch === '/' && next === '/') {
      inLineComment = true
      i++
      continue
    }
    if (ch === '/' && next === '*') {
      inBlockComment = true
      i++
      continue
    }
    if (ch === '"' || ch === "'" || ch === '`') {
      quote = ch
    }
  }
  return !quote && !inLineComment && !inBlockComment
}

/** 用 Node 的模块解析器校验一段 ESM 源码（能正确处理 import.meta） */
function checkModuleSyntax(code, label) {
  try {
    execFileSync(process.execPath, ['--input-type=module', '--check'], {
      input: code,
      stdio: ['pipe', 'ignore', 'pipe']
    })
    return true
  } catch (error) {
    const detail = error.stderr ? error.stderr.toString().split('\n').slice(0, 4).join(' ').trim() : error.message
    problems.push(`${label}: ESM 语法错误 -> ${detail}`)
    return false
  }
}

/** 用 new Function 解析一段 JS，捕获语法错误 */
function checkScript(code, label) {
  // import / export 在 Function 里非法，先做等价替换后再解析
  const normalized = code
    .replace(/^\s*import\s+[\s\S]*?from\s*['"][^'"]+['"];?\s*$/gm, '')
    .replace(/^\s*import\s*['"][^'"]+['"];?\s*$/gm, '')
    .replace(/^\s*export\s+default\s+/gm, 'const __default__ = ')
    .replace(/^\s*export\s+(const|let|var|function|async)\s+/gm, '$1 ')
    .replace(/^\s*export\s*\{[^}]*\}\s*;?\s*$/gm, '')
  try {
    // eslint-disable-next-line no-new-func
    new Function(normalized)
    return true
  } catch (error) {
    // import.meta 是 ESM 专有语法，在 Function 体内必然非法——
    // 这不是缺陷，是校验手段不适用。此时交给 checkModuleSyntax 判定（它用真实 ESM 解析器）。
    if (/import\.meta|await is only valid|Cannot use 'import\.meta'/.test(error.message)) {
      return true
    }
    problems.push(`${label}: JS 语法错误 -> ${error.message}`)
    return false
  }
}

/** 模板表达式校验：{{ }} 与常见指令的值都当表达式解析 */
function checkTemplateExpressions(template, label) {
  const expressions = []
  for (const match of template.matchAll(/\{\{([\s\S]*?)\}\}/g)) {
    expressions.push(match[1])
  }
  for (const match of template.matchAll(/\s(?::|v-bind:|@|v-on:|v-if|v-else-if|v-show|v-for|v-model[.\w]*)\s*=\s*"([^"]*)"/g)) {
    expressions.push(match[1])
  }
  for (const raw of expressions) {
    let expr = raw.trim()
    if (!expr) {
      continue
    }
    // v-for="(a, b) in list" 这类需要单独改写
    const forMatch = expr.match(/^\(?([^)]*?)\)?\s+(?:in|of)\s+([\s\S]+)$/)
    if (forMatch) {
      expr = `[${forMatch[1]}] = ${forMatch[2]}`
    }
    try {
      // eslint-disable-next-line no-new-func
      new Function(`return (${expr})`)
    } catch (error) {
      try {
        // eslint-disable-next-line no-new-func
        new Function(expr)
      } catch (error2) {
        problems.push(`${label}: 模板表达式无法解析 -> "${raw.trim()}" (${error.message})`)
      }
    }
  }
}

/**
 * 标签配平检查。
 *
 * 注意两个坑，都是本脚本第一版踩过的：
 *   1. 不能用「第一个 </template>」来截取模板内容——组件里的 slot 写法
 *      <template #footer>...</template> 会让截取提前结束，导致满屏假报错。
 *      因此这里直接对整个文件做标签配平（含 script/style 里的字符串也无妨，
 *      因为那些位置不会出现形如 <div> 的配对标签）。
 *   2. <template> 自身不参与配平：根模板与 slot 模板的层级关系对配平没有意义，
 *      只看内部真实元素标签即可。
 */
const VOID_TAGS = new Set([
  'template', 'img', 'input', 'br', 'hr', 'meta', 'link', 'source',
  'area', 'base', 'col', 'track', 'wbr'
])

function checkTags(content, label) {
  const stack = []
  const tagPattern = /<(\/?)([a-zA-Z][\w.-]*)((?:"[^"]*"|'[^']*'|[^>"'])*?)(\/?)>/g
  let match
  while ((match = tagPattern.exec(content))) {
    const [, closing, name, attrs, selfClosing] = match
    const tag = name.toLowerCase()
    if (VOID_TAGS.has(tag) || selfClosing === '/') {
      continue
    }
    // 忽略 HTML 注释里的内容
    const before = content.slice(Math.max(0, match.index - 4), match.index)
    if (before.includes('<!--')) {
      continue
    }
    if (closing === '/') {
      const last = stack.pop()
      if (last !== tag) {
        problems.push(`${label}: 标签不配平 -> 期望 </${last || '?'}>，实际 </${tag}>（属性: ${attrs.trim().slice(0, 40)}）`)
        return
      }
    } else {
      stack.push(tag)
    }
  }
  if (stack.length) {
    problems.push(`${label}: 存在未闭合标签 -> <${stack.join('>, <')}>`)
  }
}

/**
 * 括号配平检查（跳过字符串与注释）。
 *
 * **定位：这是 checkModuleSyntax 的兜底，不是权威校验。**
 * ESM 解析器能证明语法正确时，括号必然配平，本函数的启发式只会带来误报——
 * 它不认识**正则字面量**，例如 withBase 里的 `/^([a-z]...)?\/\//i`，
 * 其中 `[` 会被当成真括号，于是报「位置 N 的 } 无法闭合」（本工程实际踩过）。
 * 因此调用方只在 checkModuleSyntax 失败时才调用它：
 * 语法真错时它帮助定位，语法正确时它不再制造噪音。
 * （.vue 的样式块、.css 仍然只有它可用，保持原样。）
 */
function checkBrackets(code, label) {
  const pairs = { '{': '}', '(': ')', '[': ']' }
  const closers = { '}': '{', ')': '(', ']': '[' }
  const stack = []
  for (let i = 0; i < code.length; i++) {
    const ch = code[i]
    if (!'{}()[]'.includes(ch) || !isCodeAt(code, i)) {
      continue
    }
    if (pairs[ch]) {
      stack.push(ch)
    } else if (closers[ch]) {
      const last = stack.pop()
      if (last !== closers[ch]) {
        problems.push(`${label}: 括号不配平 -> 位置 ${i} 的 ${ch} 无法闭合`)
        return
      }
    }
  }
  if (stack.length) {
    problems.push(`${label}: 存在未闭合括号 -> ${stack.join(' ')}`)
  }
}

function blockOf(content, tag) {
  const pattern = new RegExp(`<${tag}[^>]*>([\\s\\S]*?)</${tag}>`)
  const match = content.match(pattern)
  return match ? match[1] : null
}

const files = walk(srcRoot)
files.push(join(projectRoot, 'vite.config.js'))

for (const file of files) {
  const rel = relative(projectRoot, file).replace(/\\/g, '/')
  const ext = extname(file)
  const content = readFileSync(file, 'utf8')

  if (ext === '.vue') {
    stats.vue++
    const script = blockOf(content, 'script')
    if (script) {
      checkScript(script, rel)
      // 再按真实 ESM 规则解析一遍：能发现 import.meta、顶层 await 之类的问题
      const esmOk = checkModuleSyntax(script, rel)
      // 只有在权威解析失败时才跑启发式括号检查（见 checkBrackets 的说明）
      if (!esmOk) {
        checkBrackets(script, rel)
      }
    }
    const template = blockOf(content, 'template')
    if (template) {
      checkTemplateExpressions(template, rel)
    }
    // 标签配平看整份文件，避免 slot 的 <template> 把根模板截断
    checkTags(content, rel)
    const style = blockOf(content, 'style')
    if (style) {
      checkBrackets(style, rel)
    }
  } else if (ext === '.js' || ext === '.mjs') {
    stats.js++
    // vite.config.js / *.mjs 是纯 ESM 模块：只有 import.meta 之类的语法在
    // new Function 里非法，交给 checkModuleSyntax 判定即可，避免误报。
    const moduleOnly = ext === '.mjs' || file.endsWith('vite.config.js')
    if (!moduleOnly) {
      checkScript(content, rel)
    }
    const esmOk = checkModuleSyntax(content, rel)
    // 同上：ESM 解析已经证明语法正确时，括号检查只会带来误报
    if (!esmOk) {
      checkBrackets(content.replace(/^#!.*$/gm, ''), rel)
    }
  } else if (ext === '.css') {
    stats.css++
    checkBrackets(content, rel)
  }
}

console.log(`已检查：.vue ${stats.vue} 个、.js/.mjs ${stats.js} 个、.css ${stats.css} 个`)
if (problems.length) {
  console.error(`\n发现 ${problems.length} 个问题：`)
  problems.forEach((p, i) => console.error(`${i + 1}. ${p}`))
  process.exit(1)
}
console.log('静态校验通过：脚本与模板表达式均可解析，标签与括号配平正常。')
