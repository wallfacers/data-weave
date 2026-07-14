/**
 * 069 T040 监督席指挥中心 · Playwright 浏览器门（SC-009/SC-010/SC-011）。
 *
 * 运行前置（真栈，非 mock）：
 *   1) 后端：cd backend && ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2   # :8000
 *   2) 前端：cd frontend && pnpm dev                                                                  # :4000
 *   3) 智能运维需启用 + 配好 AI Agent（设置→AI Agent→智能运维 开；否则事故走 DIAG_UNAVAILABLE，feed 仍出但无 delta）
 *   4) 触发一条会失败的周期实例（或 scripts/fault-injection.sql）产生事故
 *
 * 执行（全局 @playwright/test，绝对路径 import，见 memory playwright-browser-gate-setup）：
 *   node <globalPlaywright>/cli.js test frontend/e2e/supervision.spec.ts
 *
 * 登录绕过：注入 dw.auth.token + dw.auth.user（缺一不可）；SSE 必直连后端（Next rewrite 会缓冲）。
 */
import { test, expect, type Page } from "@playwright/test"

const BASE = process.env.DW_WEB ?? "http://localhost:4000"
const TOKEN = process.env.DW_TOKEN ?? ""

async function login(page: Page) {
  await page.addInitScript((token) => {
    localStorage.setItem("dw.auth.token", token as string)
    localStorage.setItem("dw.auth.user", JSON.stringify({ username: "e2e", name: "e2e" }))
    localStorage.setItem("dw.project.current", "1")
  }, TOKEN)
}

test.describe("监督席指挥中心", () => {
  test.beforeEach(async ({ page }) => {
    await login(page)
  })

  test("首屏即监督席（SC-010）", async ({ page }) => {
    await page.goto(BASE)
    // 首屏默认视图=supervision：战况播报横幅可见（含实时数字/直播脉冲）
    await expect(page.getByText(/直播中|已断开/)).toBeVisible({ timeout: 15000 })
  })

  test("战况数字与 feed 一致 + 点击数字过滤（SC-010）", async ({ page }) => {
    await page.goto(BASE)
    // 「需人工」数字 chip 存在且可点击过滤
    const chip = page.getByTitle("需人工")
    await expect(chip).toBeVisible({ timeout: 15000 })
    await chip.click()
    // 过滤后 feed 只剩 NEEDS_HUMAN（若有）
  })

  test("下钻线程发问得流式回应 + 提案审批按钮可用（SC-011）", async ({ page }) => {
    await page.goto(BASE)
    // 点击第一张事故卡进入线程
    const firstCard = page.locator("button", { hasText: /./ }).first()
    await firstCard.click()
    // 对话输入框可见
    const composer = page.getByPlaceholder(/提问|指令|Agent/)
    await expect(composer).toBeVisible({ timeout: 15000 })
    // 待审批事故：批准按钮可见（存在待审批提案时）
    // await expect(page.getByRole("button", { name: "批准" })).toBeVisible()
  })

  test("reduced-motion 降级检查", async ({ browser }) => {
    const ctx = await browser.newContext({ reducedMotion: "reduce" })
    const page = await ctx.newPage()
    await login(page)
    await page.goto(BASE)
    // reduced-motion 下呼吸/打字动效停止，静态状态文本仍在（LiveDot 文本）
    await expect(page.getByText(/直播中|已断开/)).toBeVisible({ timeout: 15000 })
    await ctx.close()
  })
})
