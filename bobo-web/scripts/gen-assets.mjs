/**
 * 生成前端内置的占位图与品牌图，避免仓库里塞二进制资源。
 *
 * 用法：node scripts/gen-assets.mjs
 * 产物：
 *   public/img/*.svg      商品封面占位图、头像、通用占位图
 *   public/logo.svg       站点 Logo
 *   public/favicon.svg    浏览器图标（现代浏览器支持）
 *   public/favicon.ico    ICO 图标（用 Pillow 生成，脚本会提示是否成功）
 *
 * 之所以用脚本生成而不是直接提交一堆 SVG：颜色、圆角、水印风格需要统一，
 * 以后改主题只改这里的 THEME 常量再跑一次即可。
 */
import { mkdirSync, writeFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const publicDir = resolve(here, '..', 'public')
const imgDir = resolve(publicDir, 'img')

const THEME = {
  primary: '#ffd100',
  primaryDeep: '#ffb400',
  accent: '#ff4d4f',
  text: '#1f2129',
  secondary: '#8a8f99',
  bg: '#f5f6f8'
}

mkdirSync(imgDir, { recursive: true })

/** 商品封面：白底 + 品类 emoji + 文字标签 + 品牌水印 */
function cover(label, emoji, { bg = '#ffffff', accent = THEME.primary } = {}) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="600" height="600" viewBox="0 0 600 600" role="img" aria-label="${label}">
  <defs>
    <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="${bg}"/>
      <stop offset="100%" stop-color="#f2f4f8"/>
    </linearGradient>
  </defs>
  <rect width="600" height="600" fill="url(#g)"/>
  <circle cx="300" cy="250" r="130" fill="${accent}" opacity="0.18"/>
  <text x="300" y="300" font-size="150" text-anchor="middle" dominant-baseline="middle">${emoji}</text>
  <text x="300" y="450" font-family="PingFang SC, Microsoft YaHei, sans-serif" font-size="34" font-weight="600"
        text-anchor="middle" fill="${THEME.text}">${label}</text>
  <text x="300" y="500" font-family="PingFang SC, Microsoft YaHei, sans-serif" font-size="22"
        text-anchor="middle" fill="${THEME.secondary}">bobo 校园二手 · 实物图待补</text>
  <rect x="0" y="560" width="600" height="40" fill="${THEME.primary}" opacity="0.6"/>
</svg>
`
}

/** 通用占位图（商品副图） */
function placeholder(index) {
  const tones = ['#ffe9a8', '#ffd9d9', '#d9e8ff', '#d9f7e5']
  const tone = tones[index % tones.length]
  return `<svg xmlns="http://www.w3.org/2000/svg" width="600" height="600" viewBox="0 0 600 600" role="img" aria-label="商品图片占位">
  <rect width="600" height="600" fill="${THEME.bg}"/>
  <rect x="60" y="60" width="480" height="480" rx="32" fill="${tone}"/>
  <circle cx="300" cy="260" r="90" fill="#ffffff" opacity="0.75"/>
  <path d="M240 300 L285 245 L325 285 L355 255 L400 310 Z" fill="${THEME.primaryDeep}" opacity="0.8"/>
  <text x="300" y="430" font-family="PingFang SC, Microsoft YaHei, sans-serif" font-size="30"
        text-anchor="middle" fill="${THEME.text}" opacity="0.7">图片 ${index + 1}</text>
  <text x="300" y="470" font-family="PingFang SC, Microsoft YaHei, sans-serif" font-size="22"
        text-anchor="middle" fill="${THEME.secondary}">bobo 校园二手</text>
</svg>
`
}

/** 头像：彩色圆底 + 首字/emoji */
function avatar(text, color) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="0 0 200 200" role="img" aria-label="头像">
  <defs>
    <linearGradient id="a" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="${color}"/>
      <stop offset="100%" stop-color="#ffffff"/>
    </linearGradient>
  </defs>
  <circle cx="100" cy="100" r="100" fill="url(#a)"/>
  <text x="100" y="112" font-family="PingFang SC, Microsoft YaHei, sans-serif" font-size="84" font-weight="700"
        text-anchor="middle" fill="${THEME.text}">${text}</text>
</svg>
`
}

/** 品牌 Logo：黄色圆角方块 + 白色 bobo 字形 */
function logo(size = 256, rounded = 56) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${size}" height="${size}" viewBox="0 0 256 256" role="img" aria-label="bobo">
  <defs>
    <linearGradient id="lg" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0%" stop-color="${THEME.primary}"/>
      <stop offset="100%" stop-color="${THEME.primaryDeep}"/>
    </linearGradient>
  </defs>
  <rect width="256" height="256" rx="${rounded}" fill="url(#lg)"/>
  <path d="M64 190 V66 h34 q40 0 40 32 0 18 -18 24 24 6 24 30 0 38 -46 38 Z M96 104 h14 q12 0 12 -12 t-12 -12 h-14 Z M96 144 h18 q14 0 14 -14 t-14 -14 h-18 Z"
        fill="${THEME.text}"/>
  <circle cx="176" cy="176" r="14" fill="${THEME.accent}"/>
</svg>
`
}

const covers = {
  'cover-tablet.svg': cover('iPad Air', '📱'),
  'cover-mouse.svg': cover('无线鼠标', '🖱️'),
  'cover-book.svg': cover('考研资料', '📚'),
  'cover-book2.svg': cover('线性代数', '📖'),
  'cover-bike.svg': cover('山地车', '🚲'),
  'cover-pot.svg': cover('宿舍电煮锅', '🍲'),
  'cover-shoes.svg': cover('运动鞋', '👟'),
  'cover-guitar.svg': cover('民谣吉他', '🎸'),
  'cover-fridge.svg': cover('小冰箱', '🧊'),
  'cover-keyboard.svg': cover('机械键盘', '⌨️'),
  'cover-earbuds.svg': cover('无线耳机', '🎧'),
  'cover-felt.svg': cover('手作摆件', '🧸'),
  'placeholder-1.svg': placeholder(0),
  'placeholder-2.svg': placeholder(1),
  'placeholder-3.svg': placeholder(2),
  'avatar-1.svg': avatar('管', '#ffd100'),
  'avatar-2.svg': avatar('鱼', '#a8d8ff'),
  'avatar-3.svg': avatar('露', '#ffc9c9'),
  'avatar-4.svg': avatar('铁', '#c9e8c9'),
  'avatar-5.svg': avatar('面', '#e6d1ff'),
  'default-avatar.svg': avatar('b', '#ffe9a8'),
  'default-cover.svg': cover('暂无图片', '📦')
}

for (const [name, content] of Object.entries(covers)) {
  writeFileSync(resolve(imgDir, name), content, 'utf8')
}

writeFileSync(resolve(publicDir, 'logo.svg'), logo(256, 56), 'utf8')
writeFileSync(resolve(publicDir, 'favicon.svg'), logo(64, 14), 'utf8')

console.log(`[gen-assets] 已生成 ${Object.keys(covers).length + 2} 个 SVG 到 ${publicDir}`)
console.log('[gen-assets] 提示：favicon.ico 由 make-favicon.py 用 Pillow 生成（见 scripts/ 目录）')
