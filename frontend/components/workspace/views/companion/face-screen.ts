/**
 * 机器人管家表情屏 —— CanvasTexture 实时绘制。
 *
 * 五状态表情：
 *   idle    — 圆角竖眼 + 偶尔眯眼微笑弧线
 *   patrol  — 同 idle + 整体左右扫视（由 stage 动画控制）
 *   alert   — 上斜切锐利眼型（警觉）
 *   think   — 眼位上移 + 眼下三个渐次点亮的小点
 *   speak   — 眼下波形"嘴"（7 条跳动柱）
 *
 * 眨眼：随机间隔 1.8–5.0s，160ms 闭眼正弦曲线。
 * 颜色：全部来自调用方传入的 CSS token → hex 字符串，零硬编码色值。
 */
import type { CompanionState } from "@/lib/companion/types"

/** 将 THREE.Color 或 hex number 转为 CSS hex 颜色字符串 */
export function cssHex(c: { getHexString(): string } | number): string {
  if (typeof c === "number") {
    return "#" + c.toString(16).padStart(6, "0")
  }
  return "#" + c.getHexString()
}

export interface DrawFaceParams {
  ctx: CanvasRenderingContext2D
  /** 当前状态色（hex 字符串） */
  colorHex: string
  /** 当前管家形态 */
  state: CompanionState
  /** 自动画开始的总时间（秒） */
  elapsed: number
  /** 上一帧到本帧的时间差（秒） */
  dt: number
  /** 鼠标归一化坐标 (-1..1) */
  mouseX: number
  mouseY: number
  /** 眨眼状态引用（外部维护，跨帧持久） */
  blinkRef: { t: number; next: number }
}

const CANVAS_W = 256
const CANVAS_H = 128

/**
 * 绘制一帧表情。
 * 每帧调用；blinkRef 由外部维护（跨帧持久）。
 */
export function drawFace(params: DrawFaceParams): void {
  const { ctx, colorHex, state, elapsed, dt, mouseX, mouseY, blinkRef } = params
  const g = ctx

  g.clearRect(0, 0, CANVAS_W, CANVAS_H)
  g.fillStyle = colorHex
  g.strokeStyle = colorHex
  g.shadowColor = colorHex
  g.shadowBlur = 14

  /* ── 眨眼进度 0（睁）→ 1（闭）── */
  blinkRef.t += dt
  let blink = 0
  if (blinkRef.t > blinkRef.next) {
    const phase = (blinkRef.t - blinkRef.next) / 0.16
    if (phase >= 1) {
      blinkRef.t = 0
      blinkRef.next = 1.8 + Math.random() * 3.2
    } else {
      blink = Math.sin(phase * Math.PI)
    }
  }

  /* ── 眼位计算 ── */
  const eyeY = 52 + (state === "think" ? -10 : 0) + mouseY * 6
  const eyeDX = (state === "think" ? 10 : 0) + mouseX * 10
  const L = CANVAS_W / 2 - 34 + eyeDX
  const R = CANVAS_W / 2 + 34 + eyeDX

  function roundEye(cx: number, cy: number, w: number, h: number, r: number) {
    g.beginPath()
    if ((g as CanvasRenderingContext2D & { roundRect?: unknown }).roundRect) {
      ;(g as any).roundRect(cx - w / 2, cy - h / 2, w, h, r)
    } else {
      g.rect(cx - w / 2, cy - h / 2, w, h)
    }
    g.fill()
  }

  /* ── 按状态绘制眼部 ── */
  if (state === "alert") {
    // 警觉：上斜切锐利眼型
    ;([ [L, 1], [R, -1] ] as const).forEach(([cx, dir]) => {
      g.beginPath()
      g.moveTo(cx - 16, eyeY - 12 * (1 - blink) * dir)
      g.lineTo(cx + 16, eyeY - 12 * (1 - blink) * -dir * 0.2)
      g.lineTo(cx + 16, eyeY + 14)
      g.lineTo(cx - 16, eyeY + 14)
      g.closePath()
      g.fill()
    })
  } else if (
    (state === "idle" || state === "speak") &&
    Math.sin(elapsed * 0.35) > 0.55
  ) {
    // 偶尔眯眼微笑（开心弧线）
    ;[L, R].forEach((cx) => {
      g.beginPath()
      g.lineWidth = 9
      g.lineCap = "round"
      g.arc(cx, eyeY + 8, 15, Math.PI * 1.15, Math.PI * 1.85)
      g.stroke()
    })
  } else {
    // 常态圆角竖眼（眨眼压扁）
    const h = 42 * (1 - blink * 0.92)
    roundEye(L, eyeY, 26, Math.max(h, 4), 12)
    roundEye(R, eyeY, 26, Math.max(h, 4), 12)
  }

  /* ── 说话：眼下波形"嘴" ── */
  if (state === "speak") {
    const bars = 7
    for (let i = 0; i < bars; i++) {
      const bh = 4 + Math.abs(Math.sin(elapsed * 10 + i * 1.3)) * 14
      g.fillRect(CANVAS_W / 2 - 42 + i * 12, 100 - bh / 2, 7, bh)
    }
  }

  /* ── 思考：眼下三个渐次点亮的小点 ── */
  if (state === "think") {
    for (let i = 0; i < 3; i++) {
      g.globalAlpha = 0.25 + 0.75 * Math.abs(Math.sin(elapsed * 2.4 - i * 0.7))
      g.beginPath()
      g.arc(108 + i * 20, 100, 5, 0, Math.PI * 2)
      g.fill()
    }
    g.globalAlpha = 1
  }
}
