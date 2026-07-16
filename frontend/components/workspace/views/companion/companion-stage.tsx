"use client"

/**
 * 虚拟管家 3D 场景组件。
 *
 * - "use client" + next/dynamic ssr:false
 * - Token 取色：getComputedStyle 读取 --companion-* → THREE.Color
 * - 动画循环内通过 useCompanionStore.getState().state 读取最新形态（不闭包冻结）
 * - resolvedTheme 变化 → 材质 .color.set()，不重建场景
 * - WebGL2/WebGL 探测失败 → 抛给父组件切 orb-fallback
 * - 完整 dispose（含 Points/Sprite/纹理/forceContextLoss）
 */
import { useEffect, useRef, useCallback } from "react"
import { useTheme } from "next-themes"
import * as THREE from "three"
import type { CompanionState } from "@/lib/companion/types"
import { useCompanionStore } from "@/lib/companion/store"
import { createBot, type BotColors, type BotModel } from "./bot-model"
import { drawFace } from "./face-screen"

/* ── Token 取色 ── */

interface CompanionTokens {
  idle: string; patrol: string; alert: string; think: string; speak: string
}

function readTokens(): CompanionTokens {
  const s = getComputedStyle(document.documentElement)
  return {
    idle: s.getPropertyValue("--companion-idle").trim(),
    patrol: s.getPropertyValue("--companion-patrol").trim(),
    alert: s.getPropertyValue("--companion-alert").trim(),
    think: s.getPropertyValue("--companion-think").trim(),
    speak: s.getPropertyValue("--companion-speak").trim(),
  }
}

/**
 * 将 CSS 颜色字符串（oklch/hex/rgb/...）转为 THREE.Color。
 * 使用 2D canvas fillStyle+getImageData 读取 RGBA，绕开 getComputedStyle
 * 返回 lab() 导致 THREE.Color 无法解析的问题。
 */
function parseCssColor(css: string): THREE.Color {
  if (!css) return new THREE.Color(0x5eead4)
  try {
    const canvas = document.createElement("canvas")
    canvas.width = canvas.height = 1
    const ctx = canvas.getContext("2d")!
    ctx.fillStyle = css
    ctx.fillRect(0, 0, 1, 1)
    const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data
    return new THREE.Color(r / 255, g / 255, b / 255)
  } catch {
    return new THREE.Color(0x5eead4)
  }
}

const STATE_COLOR_KEY: Record<CompanionState, keyof CompanionTokens> = {
  idle: "idle", patrol: "patrol", alert: "alert", think: "think", speak: "speak",
}

/**
 * 问题②：亮色主题下状态色偏浅（如 idle 青 oklch(0.65 …)），小粒子/地面光环在白底被冲淡看不清。
 * 这里把颜色压深、提饱和，仅用于粒子/光环等「白底上的细线元素」；机器人自发光（core/thrust/emissive）
 * 仍用原亮色，因其贴在深色机身上、越亮越清晰。暗色主题不变（直接返回原色）。
 */
function toVisibleColor(hex: number, isLight: boolean): THREE.Color {
  const c = new THREE.Color(hex)
  if (isLight) {
    const hsl = { h: 0, s: 0, l: 0 }
    c.getHSL(hsl)
    c.setHSL(hsl.h, Math.min(1, hsl.s * 1.2 + 0.08), Math.min(hsl.l, 0.42))
  }
  return c
}

export function detectWebGL(): boolean {
  try {
    const c = document.createElement("canvas")
    return !!(c.getContext("webgl2") || c.getContext("webgl"))
  } catch { return false }
}

/* ── Props ── */

interface CompanionStageProps {
  onWebGLUnavailable?: () => void
}

/* ── 组件 ── */

export default function CompanionStage({ onWebGLUnavailable }: CompanionStageProps) {
  const containerRef = useRef<HTMLDivElement>(null)
  const sceneRef = useRef<{
    renderer: THREE.WebGLRenderer
    camera: THREE.PerspectiveCamera
    bot: BotModel
    shellMat: THREE.MeshStandardMaterial
    visorMat: THREE.MeshStandardMaterial
    ringMat: THREE.MeshBasicMaterial
    ringMat2: THREE.MeshBasicMaterial
    discMat: THREE.MeshBasicMaterial
    pMat: THREE.PointsMaterial
    ringGroup: THREE.Group
    particles: THREE.Points
    clock: THREE.Clock
    mouseX: number; mouseY: number
    blinkRef: { t: number; next: number }
    alertShakeT: number
    currentStateHex: number
    isLight: boolean
    glowTex: THREE.CanvasTexture
  } | null>(null)
  const rafRef = useRef(0)
  const roRef = useRef<ResizeObserver | null>(null)
  const { resolvedTheme } = useTheme()

  /* ── 更新所有状态相关材质颜色 ── */
  const applyStateColor = useCallback((hex: number) => {
    const s = sceneRef.current
    if (!s) return
    s.currentStateHex = hex
    s.bot.tintedMaterials.forEach((m) => { m.color.setHex(hex); m.emissive?.setHex(hex) })
    // 粒子/地面光环用「白底可见色」（亮色主题压深提饱和），机身自发光仍用原亮色
    const vis = toVisibleColor(hex, s.isLight)
    s.ringMat.color.copy(vis)
    s.ringMat2.color.copy(vis)
    s.discMat.color.copy(vis)
    s.pMat.color.copy(vis)
    s.bot.thrustMat.color.setHex(hex)
    ;(s.bot.coreGlow.material as THREE.SpriteMaterial).color.setHex(hex)
    ;(s.bot.hoverGlow.material as THREE.SpriteMaterial).color.setHex(hex)
  }, [])

  /* ── 主题切换：更新壳/面屏/装饰色 ── */
  const applyThemeColors = useCallback(() => {
    const s = sceneRef.current
    if (!s) return
    const tokens = readTokens()
    const isLight = resolvedTheme === "light"
    s.isLight = isLight
    // 壳色：亮=暖白，暗=深蓝灰
    s.shellMat.color.set(isLight ? 0xeef2f7 : 0x1a1a2e)
    // 面屏恒深
    s.visorMat.color.set(0x0a1120)
    // 问题②：亮色主题下加大加实粒子，抵消白底冲淡
    s.pMat.size = isLight ? 0.02 : 0.012
    s.pMat.opacity = isLight ? 0.95 : 0.75
    // 装饰/发光色随当前状态（applyStateColor 内按 s.isLight 取白底可见色）
    const st = useCompanionStore.getState().state
    const key = STATE_COLOR_KEY[st]
    const hex = parseCssColor(tokens[key]).getHex()
    applyStateColor(hex)
    // 更新 rim light 颜色
  }, [resolvedTheme, applyStateColor])

  /* ── 场景初始化 ── */
  useEffect(() => {
    const container = containerRef.current
    if (!container) return

    if (!detectWebGL()) { onWebGLUnavailable?.(); return }

    const w = container.clientWidth
    const h = container.clientHeight

    /* Renderer */
    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
    renderer.setSize(w, h)
    renderer.setPixelRatio(Math.min(devicePixelRatio, 2))
    renderer.outputColorSpace = THREE.SRGBColorSpace
    container.appendChild(renderer.domElement)

    /* Scene & Camera */
    const scene = new THREE.Scene()
    const camera = new THREE.PerspectiveCamera(30, w / h, 0.1, 40)
    camera.position.set(0, 1.18, 3.1)
    camera.lookAt(0, 1.02, 0)

    /* Lights — 场景常量（灯光颜色不映射 token，见 DESIGN.md M3 声明） */
    scene.add(new THREE.AmbientLight(0x9db4d8, 0.8))
    const keyLight = new THREE.DirectionalLight(0xffffff, 1.6)
    keyLight.position.set(1.4, 2.4, 2.2)
    scene.add(keyLight)
    const rimLight = new THREE.DirectionalLight(0x5eead4, 2.2)
    rimLight.position.set(-1.8, 1.2, -2.0)
    scene.add(rimLight)
    const fillLight = new THREE.DirectionalLight(0x818cf8, 0.7)
    fillLight.position.set(-1.0, 0.4, 2.2)
    scene.add(fillLight)

    /* Bot */
    const tokens = readTokens()
    const isLight = resolvedTheme === "light"
    const defaultTrim = parseCssColor(tokens.idle)
    const botColors: BotColors = {
      shell: new THREE.Color(isLight ? 0xeef2f7 : 0x1a1a2e),
      visor: new THREE.Color(0x0a1120),
      trim: defaultTrim,
      glow: defaultTrim,
      thrust: defaultTrim,
    }
    const bot = createBot(botColors)
    scene.add(bot.group)

    /* 提取 shell/visor 材质引用于主题切换 */
    const shellMat = (bot.group.children.find(
      (c) => c instanceof THREE.Mesh && (c as THREE.Mesh).geometry.type === "SphereGeometry"
    ) as THREE.Mesh)?.material as THREE.MeshStandardMaterial
    // 从 torso 取 shellMat（更可靠）
    const shellMatFromTorso = ((bot.group.children[3] as THREE.Mesh)?.material) as THREE.MeshStandardMaterial
    const visorMat = bot.tintedMaterials[0]?.clone()
    // 面屏不在 tintedMaterials 中，从 head 子节点获取
    const visorMesh = bot.head.children[1] as THREE.Mesh
    const visorMaterial = visorMesh.material as THREE.MeshStandardMaterial

    /* Ground rings + particles */
    const ringGroup = new THREE.Group()
    scene.add(ringGroup)
    // 白底可见色（亮色主题压深）：粒子/光环初值即用，避免挂载首帧闪一下浅色
    const trimVis = toVisibleColor(defaultTrim.getHex(), isLight)
    const ringMat = new THREE.MeshBasicMaterial({
      color: trimVis, transparent: true, opacity: 0.55, side: THREE.DoubleSide,
    })
    const ring1 = new THREE.Mesh(new THREE.RingGeometry(0.52, 0.545, 80), ringMat)
    ring1.rotation.x = -Math.PI / 2; ringGroup.add(ring1)
    const ringMat2 = ringMat.clone(); ringMat2.opacity = 0.22
    const ring2 = new THREE.Mesh(new THREE.RingGeometry(0.66, 0.672, 80), ringMat2)
    ring2.rotation.x = -Math.PI / 2; ringGroup.add(ring2)
    const discMat = new THREE.MeshBasicMaterial({
      color: trimVis, transparent: true, opacity: 0.05, side: THREE.DoubleSide,
    })
    const disc = new THREE.Mesh(new THREE.CircleGeometry(0.52, 64), discMat)
    disc.rotation.x = -Math.PI / 2; disc.position.y = 0.001; ringGroup.add(disc)

    const P_COUNT = 240
    const pGeo = new THREE.BufferGeometry()
    const pPos = new Float32Array(P_COUNT * 3)
    for (let i = 0; i < P_COUNT; i++) {
      const r = 0.55 + Math.pow(i / P_COUNT, 1.5) * 0.85
      const a = (i / P_COUNT) * Math.PI * 14
      pPos[i * 3] = Math.cos(a) * r
      pPos[i * 3 + 1] = (i / P_COUNT) * 1.9
      pPos[i * 3 + 2] = Math.sin(a) * r
    }
    pGeo.setAttribute("position", new THREE.BufferAttribute(pPos, 3))
    const pMat = new THREE.PointsMaterial({
      color: trimVis, size: isLight ? 0.02 : 0.012, transparent: true, opacity: isLight ? 0.95 : 0.75,
    })
    const particles = new THREE.Points(pGeo, pMat)
    scene.add(particles)

    const clock = new THREE.Clock()
    const blinkRef = { t: 0, next: 2.2 }
    const currentStateHex = defaultTrim.getHex()

    sceneRef.current = {
      renderer, camera, bot,
      shellMat: shellMatFromTorso, visorMat: visorMaterial,
      ringMat, ringMat2, discMat, pMat, ringGroup, particles, clock,
      mouseX: 0, mouseY: 0, blinkRef, alertShakeT: 0,
      currentStateHex,
      isLight,
      glowTex: (bot.coreGlow.material as THREE.SpriteMaterial).map as THREE.CanvasTexture,
    }

    /* ResizeObserver */
    const ro = new ResizeObserver(() => {
      const w2 = container.clientWidth; const h2 = container.clientHeight
      renderer.setSize(w2, h2)
      camera.aspect = w2 / h2; camera.updateProjectionMatrix()
    })
    ro.observe(container)
    roRef.current = ro

    /* Mouse */
    const onMouse = (e: MouseEvent) => {
      const s = sceneRef.current; if (!s) return
      s.mouseX = (e.clientX / innerWidth) * 2 - 1
      s.mouseY = (e.clientY / innerHeight) * 2 - 1
    }
    window.addEventListener("mousemove", onMouse)

    /* ══ 动画循环 ══ */
    function animate() {
      rafRef.current = requestAnimationFrame(animate)
      const s = sceneRef.current; if (!s) return

      // B1 修复：实时读取 store 中的最新状态，不依赖闭包
      const state = useCompanionStore.getState().state

      const dt = Math.min(s.clock.getDelta(), 0.1)
      const t = s.clock.elapsedTime
      const { bot: b } = s

      /* 悬浮呼吸 */
      b.group.position.y = 1.0 + Math.sin(t * 1.5) * 0.035

      /* 头部朝向鼠标 + 姿态 */
      const thinkTilt = state === "think" ? 0.18 : 0
      const speakNod = state === "speak" ? Math.sin(t * 6) * 0.045 : 0
      b.head.rotation.y += (s.mouseX * 0.5 - b.head.rotation.y) * 0.08
      b.head.rotation.x += (s.mouseY * 0.28 + speakNod - b.head.rotation.x) * 0.1
      b.head.rotation.z += (thinkTilt - b.head.rotation.z) * 0.06

      /* 巡检扫视 */
      if (state === "patrol") b.group.rotation.y = Math.sin(t * 0.6) * 0.35
      else b.group.rotation.y += (0 - b.group.rotation.y) * 0.05

      /* 警觉震动 */
      if (s.alertShakeT > 0) {
        s.alertShakeT -= dt
        b.group.position.x = Math.sin(t * 60) * 0.02 * (s.alertShakeT / 0.55)
      } else b.group.position.x += (0 - b.group.position.x) * 0.1

      /* 手臂 */
      const idleSwing = Math.sin(t * 1.5) * 0.06
      if (state === "think") {
        b.armR.position.x += (0.2 - b.armR.position.x) * 0.08
        b.armR.position.y += (0.3 - b.armR.position.y) * 0.08
        b.armR.position.z += (0.14 - b.armR.position.z) * 0.08
        b.armR.rotation.z += (2.6 - b.armR.rotation.z) * 0.08
      } else {
        b.armR.position.x += (0.33 - b.armR.position.x) * 0.08
        b.armR.position.y += (0.16 - b.armR.position.y) * 0.08
        b.armR.position.z += (0 - b.armR.position.z) * 0.08
        b.armR.rotation.z += (0.12 + idleSwing - b.armR.rotation.z) * 0.08
      }
      b.armL.rotation.z = -0.12 - idleSwing

      /* 说话双臂比划 */
      if (state === "speak") {
        b.armL.rotation.x = Math.sin(t * 4) * 0.25
        b.armR.rotation.x = Math.sin(t * 4 + 1.2) * 0.25
      } else { b.armL.rotation.x *= 0.9; b.armR.rotation.x *= 0.9 }

      /* 光环 & 环旋转 */
      b.halo.rotation.z += dt * 1.2
      b.hoverRing.rotation.z += dt * 2.4

      /* 核心脉冲 */
      b.coreMat.emissiveIntensity = 1.8 + Math.sin(t * (state === "alert" ? 8 : 2.2)) * 0.7
      b.coreGlow.material.opacity = 0.5 + Math.sin(t * 2.2) * 0.18

      /* 尾焰粒子下坠 */
      const tp = b.thrustPoints.geometry.attributes.position
      for (let i = 0; i < 60; i++) {
        let y = tp.getY(i) - dt * (0.4 + (i % 5) * 0.08)
        if (y < -0.95) y = -0.42
        tp.setY(i, y)
      }
      tp.needsUpdate = true
      b.thrustMat.opacity = 0.5 + Math.sin(t * 3) * 0.25

      /* 地面光环 & 粒子 */
      s.ringGroup.rotation.y += dt * (state === "alert" ? 1.6 : 0.35)
      s.ringMat.opacity = state === "alert"
        ? 0.4 + Math.abs(Math.sin(t * 5)) * 0.5 : 0.45 + Math.sin(t * 1.4) * 0.12
      s.particles.rotation.y += dt * (state === "patrol" ? 0.5 : 0.16)
      const pp = s.particles.geometry.attributes.position
      for (let i = 0; i < 240; i++) {
        let y = pp.getY(i) + dt * (state === "patrol" ? 0.34 : 0.12)
        if (y > 1.9) y = 0
        pp.setY(i, y)
      }
      pp.needsUpdate = true

      /* 表情 */
      drawFace({
        ctx: b.faceCtx,
        colorHex: "#" + s.currentStateHex.toString(16).padStart(6, "0"),
        state, elapsed: t, dt,
        mouseX: s.mouseX, mouseY: s.mouseY,
        blinkRef: s.blinkRef,
      })
      b.faceTex.needsUpdate = true

      s.renderer.render(scene, camera)
    }
    animate()

    return () => {
      cancelAnimationFrame(rafRef.current)
      ro.disconnect()
      window.removeEventListener("mousemove", onMouse)
      // M1: 完整 dispose
      scene.traverse((obj) => {
        if (obj instanceof THREE.Mesh || obj instanceof THREE.Points) {
          obj.geometry?.dispose()
          if (Array.isArray(obj.material)) obj.material.forEach((m) => m.dispose())
          else obj.material?.dispose()
        }
        if (obj instanceof THREE.Sprite) {
          ;(obj as THREE.Sprite).material?.dispose()
        }
      })
      // 显式 dispose 纹理
      ;(bot.coreGlow.material as THREE.SpriteMaterial).map?.dispose()
      renderer.forceContextLoss()
      renderer.dispose()
      if (container.contains(renderer.domElement)) container.removeChild(renderer.domElement)
      sceneRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /* ── 状态变化 → 换色 ── */
  const state = useCompanionStore((s) => s.state)
  const prevStateRef = useRef<CompanionState>("idle")
  useEffect(() => {
    const s = sceneRef.current; if (!s) return
    const tokens = readTokens()
    const key = STATE_COLOR_KEY[state]
    const hex = parseCssColor(tokens[key]).getHex()
    applyStateColor(hex)
    if (state === "alert" && prevStateRef.current !== "alert") s.alertShakeT = 0.55
    prevStateRef.current = state
  }, [state, applyStateColor])

  /* ── 主题切换 → 壳/面屏色 ── */
  useEffect(() => {
    if (!resolvedTheme) return
    applyThemeColors()
  }, [resolvedTheme, applyThemeColors])

  return <div ref={containerRef} className="absolute inset-0" />
}
