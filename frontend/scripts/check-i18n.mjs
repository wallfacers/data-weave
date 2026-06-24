#!/usr/bin/env node
/**
 * i18n CI 兜底校验（design 12.3）。三项检查：
 *  ① messages/zh-CN.json 与 en-US.json 键集完全一致（无 zh-only / en-only 漏译）。
 *  ② components/ app/ 下 .tsx 无残留硬编码中文（排除注释 // 与 块注释，JSX/字符串字面量里的中文视为漏抽 key）。
 *  ③ 报告统计，任一不通过则非零退出，可挂 CI / pre-commit。
 *
 * 用法：node scripts/check-i18n.mjs  （或 pnpm i18n:lint）
 */
import { readFileSync, readdirSync, statSync } from "node:fs"
import { join, extname } from "node:path"
import { fileURLToPath } from "node:url"
import { dirname } from "node:path"

const ROOT = join(dirname(fileURLToPath(import.meta.url)), "..")
const CJK = /[一-鿿]/

let failed = false

/* ── ① 键集一致 ─────────────────────────────────────────── */
function flatten(obj, prefix = "") {
  const out = new Set()
  for (const [k, v] of Object.entries(obj)) {
    const key = prefix + k
    out.add(key)
    if (v && typeof v === "object" && !Array.isArray(v)) {
      for (const nested of flatten(v, key + ".")) out.add(nested)
    }
  }
  return out
}
const zh = JSON.parse(readFileSync(join(ROOT, "messages/zh-CN.json"), "utf8"))
const en = JSON.parse(readFileSync(join(ROOT, "messages/en-US.json"), "utf8"))
const zhKeys = flatten(zh)
const enKeys = flatten(en)
const zhOnly = [...zhKeys].filter((k) => !enKeys.has(k))
const enOnly = [...enKeys].filter((k) => !zhKeys.has(k))
if (zhOnly.length || enOnly.length) {
  failed = true
  console.error("✗ messages 键集不一致：")
  if (zhOnly.length) console.error("  仅 zh-CN：", zhOnly.join(", "))
  if (enOnly.length) console.error("  仅 en-US：", enOnly.join(", "))
} else {
  console.log(`✓ messages 键集一致（${zhKeys.size} keys × zh/en）`)
}

/* ── ② 残留硬编码中文（剥离注释后） ────────────────────── */
function stripComments(src) {
  // 去块注释 /* ... */（含 JSX {/* ... */}）、行注释 // ...、开发日志 console.*（非用户可见，design Non-Goal）
  return src
    .replace(/\/\*[\s\S]*?\*\//g, "")
    .replace(/^\s*\/\/.*$/gm, "")
    .replace(/([^:"'`])\/\/.*$/gm, "$1")
    .replace(/^\s*console\.(log|warn|error|info|debug)\(.*$/gm, "")
}
function walk(dir, acc = []) {
  for (const name of readdirSync(dir)) {
    if (name === "node_modules" || name === ".next" || name === "scripts") continue
    const p = join(dir, name)
    const st = statSync(p)
    if (st.isDirectory()) walk(p, acc)
    // 测试文件描述用中文属约定，不算用户可见漏抽
    else if ([".tsx", ".ts"].includes(extname(p)) && !/\.(test|spec)\.tsx?$/.test(name)) acc.push(p)
  }
  return acc
}
const SCAN_DIRS = ["components", "app", "lib"]
// 豁免清单：登记刻意保留中文的文件（须附理由）。
// - lib/chat/mock.ts：mock provider，模拟后端 AG-UI 回复 + Finding 数据（生产由后端
//   Messages.get 本地化），仅为开发期替身，非生产 UI 框架文案。
const ALLOWLIST = new Set(["lib/chat/mock.ts"])
const offenders = []
for (const d of SCAN_DIRS) {
  for (const file of walk(join(ROOT, d))) {
    if (ALLOWLIST.has(file.replace(ROOT + "/", ""))) continue
    const stripped = stripComments(readFileSync(file, "utf8"))
    stripped.split("\n").forEach((line, i) => {
      if (CJK.test(line)) {
        offenders.push(`${file.replace(ROOT + "/", "")}:${i + 1}  ${line.trim().slice(0, 80)}`)
      }
    })
  }
}
if (offenders.length) {
  failed = true
  console.error(`✗ 发现 ${offenders.length} 处残留硬编码中文（应抽 i18n key）：`)
  offenders.slice(0, 50).forEach((o) => console.error("  " + o))
  if (offenders.length > 50) console.error(`  …还有 ${offenders.length - 50} 处`)
} else {
  console.log("✓ 无残留硬编码中文（注释除外）")
}

process.exit(failed ? 1 : 0)
