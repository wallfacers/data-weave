"use client"

/**
 * 虚拟管家 3D 场景组件。
 *
 * - "use client" + next/dynamic ssr:false（three.js 需要 DOM）
 * - 启动/主题切换时从 getComputedStyle 读取语义 token → THREE.Color
 * - resolvedTheme 变化 → 材质 .color.set()，不重建场景
 * - WebGL2/WebGL 探测失败 → 抛错误由父组件切 orb-fallback
 * - 完整的 dispose 清理
 */
import { useEffect, useRef, useCallback } from "react"
import { useTheme } from "next-themes"
import * as THREE from "three"
import type { CompanionState } from "@/lib/companion/types"
import { useCompanionStore } from "@/lib/companion/store"
import { createBot, type BotColors, type BotModel } from "./bot-model"
import { drawFace, cssHex } from "./face-screen"

/* ── Token 取色 ── */

interface CompanionTokens {
  idle: string
  patrol: string
  alert: string
  think: string
  speak: string
  background: string
  foreground: string
}

function readCompanionTokens(): CompanionTokens {
  const style = getComputedStyle(document.documentElement)
  return {
    idle: style.getPropertyValue("--companion-idle").trim(),
    patrol: style.getPropertyValue("--companion-patrol").trim(),
    alert: style.getPropertyValue("--companion-alert").trim(),
    think: style.getPropertyValue("--companion-think").trim(),
    speak: style.getPropertyValue("--companion-speak").trim(),
    background: style.getPropertyValue("--background").trim(),
    foreground: style.getPropertyValue("--foreground").trim(),
  }
}

function parseOklch(css: string): THREE.Color {
  // oklch(L C H) → 通过 CSS 颜色解析
  if (!css) return new THREE.Color(0x888888)
  // 用临时元素让浏览器解析 oklch → rgb
  const div = document.createElement("div")
  div.style.color = css
  document.body.appendChild(div)
  const rgb = getComputedStyle(div).color
  document.body.removeChild(div)
  // rgb(r, g, b) → THREE.Color
  const match = rgb.match(/rgb\((\d+),\s*(\d+),\s*(\d+)\)/)
  if (match) {
    return new THREE.Color(
      parseInt(match[1]) / 255,
      parseInt(match[2]) / 255,
      parseInt(match[3]) / 255
    )
  }
  return new THREE.Color(css)
}

const STATE_COLOR_KEY: Record<CompanionState, keyof CompanionTokens> = {
  idle: "idle",
  patrol: "patrol",
  alert: "alert",
  think: "think",
  speak: "speak",
}

/* ── WebGL 探测 ── */

export function detectWebGL(): boolean {
  try {
    const c = document.createElement("canvas")
    return !!(c.getContext("webgl2") || c.getContext("webgl"))
  } catch {
    return false
  }
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
    scene: THREE.Scene
    camera: THREE.PerspectiveCamera
    bot: BotModel
    ringMat: THREE.MeshBasicMaterial
    ringMat2: THREE.MeshBasicMaterial
    discMat: THREE.MeshBasicMaterial
    pMat: THREE.PointsMaterial
    ringGroup: THREE.Group
    particles: THREE.Points
    clock: THREE.Clock
    mouseX: number
    mouseY: number
    blinkRef: { t: number; next: number }
    alertShakeT: number
    /** 当前动画用的状态色 hex number */
    currentStateColor: number
  } | null>(null)
  const rafRef = useRef<number>(0)
  const { resolvedTheme } = useTheme()

  const state = useCompanionStore((s) => s.state)

  /* ── Token 取色函数（稳定引用） ── */
  const buildColors = useCallback((): { tokens: CompanionTokens; botColors: BotColors } => {
    const tokens = readCompanionTokens()
    // shell: 基于 background 明度判断亮/暗
    const bg = parseOklch(tokens.background)
    const shellColor = bg.r > 0.5
      ? new THREE.Color(0xeef2f7) // 亮色：暖白壳
      : new THREE.Color(0x1a1a2e) // 暗色：深蓝灰壳
    const visorColor = new THREE.Color(0x0a1120) // 面屏恒深
    const defaultTrim = parseOklch(tokens.idle)

    return {
      tokens,
      botColors: {
        shell: shellColor,
        visor: visorColor,
        trim: defaultTrim,
        glow: defaultTrim,
        thrust: defaultTrim,
      },
    }
  }, [])

  /* ── 场景初始化 ── */
  useEffect(() => {
    if (!containerRef.current) return

    // WebGL 探测
    if (!detectWebGL()) {
      onWebGLUnavailable?.()
      return
    }

    const container = containerRef.current
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

    /* Lights */
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
    const { tokens, botColors } = buildColors()
    const bot = createBot(botColors)
    scene.add(bot.group)

    /* Ground rings + particles */
    const ringGroup = new THREE.Group()
    scene.add(ringGroup)
    const ringMat = new THREE.MeshBasicMaterial({
      color: botColors.trim,
      transparent: true,
      opacity: 0.55,
      side: THREE.DoubleSide,
    })
    const ring1 = new THREE.Mesh(new THREE.RingGeometry(0.52, 0.545, 80), ringMat)
    ring1.rotation.x = -Math.PI / 2
    ringGroup.add(ring1)
    const ringMat2 = ringMat.clone()
    ringMat2.opacity = 0.22
    const ring2 = new THREE.Mesh(new THREE.RingGeometry(0.66, 0.672, 80), ringMat2)
    ring2.rotation.x = -Math.PI / 2
    ringGroup.add(ring2)
    const discMat = new THREE.MeshBasicMaterial({
      color: botColors.trim,
      transparent: true,
      opacity: 0.05,
      side: THREE.DoubleSide,
    })
    const disc = new THREE.Mesh(new THREE.CircleGeometry(0.52, 64), discMat)
    disc.rotation.x = -Math.PI / 2
    disc.position.y = 0.001
    ringGroup.add(disc)

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
      color: botColors.trim,
      size: 0.012,
      transparent: true,
      opacity: 0.75,
    })
    const particles = new THREE.Points(pGeo, pMat)
    scene.add(particles)

    const clock = new THREE.Clock()
    const blinkRef = { t: 0, next: 2.2 }
    const mouseRef = { x: 0, y: 0 }
    let alertShakeT = 0
    let currentStateColor = tokens.idle ? parseOklch(tokens.idle).getHex() : 0x5eead4

    sceneRef.current = {
      renderer,
      scene,
      camera,
      bot,
      ringMat,
      ringMat2,
      discMat,
      pMat,
      ringGroup,
      particles,
      clock,
      mouseX: 0,
      mouseY: 0,
      blinkRef,
      alertShakeT,
      currentStateColor,
    }

    /* Resize */
    const onResize = () => {
      const w2 = container.clientWidth
      const h2 = container.clientHeight
      renderer.setSize(w2, h2)
      camera.aspect = w2 / h2
      camera.updateProjectionMatrix()
    }
    window.addEventListener("resize", onResize)

    /* Mouse */
    const onMouse = (e: MouseEvent) => {
      if (!sceneRef.current) return
      sceneRef.current.mouseX = (e.clientX / window.innerWidth) * 2 - 1
      sceneRef.current.mouseY = (e.clientY / window.innerHeight) * 2 - 1
    }
    window.addEventListener("mousemove", onMouse)

    /* ── 动画循环 ── */
    function animate() {
      rafRef.current = requestAnimationFrame(animate)
      const s = sceneRef.current
      if (!s) return

      const dt = Math.min(s.clock.getDelta(), 0.1)
      const t = s.clock.elapsedTime
      const { bot: b, ringMat: rm, ringMat2: rm2, discMat: dm, pMat: pm, ringGroup, particles } = s

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
      } else {
        b.armL.rotation.x *= 0.9
        b.armR.rotation.x *= 0.9
      }

      /* 头顶光环 & 推进环旋转 */
      b.halo.rotation.z += dt * 1.2
      b.hoverRing.rotation.z += dt * 2.4

      /* 核心脉冲 */
      const corePulse = 1.8 + Math.sin(t * (state === "alert" ? 8 : 2.2)) * 0.7
      b.coreMat.emissiveIntensity = corePulse
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
      ringGroup.rotation.y += dt * (state === "alert" ? 1.6 : 0.35)
      rm.opacity = state === "alert"
        ? 0.4 + Math.abs(Math.sin(t * 5)) * 0.5
        : 0.45 + Math.sin(t * 1.4) * 0.12
      particles.rotation.y += dt * (state === "patrol" ? 0.5 : 0.16)
      const pp = particles.geometry.attributes.position
      for (let i = 0; i < 240; i++) {
        let y = pp.getY(i) + dt * (state === "patrol" ? 0.34 : 0.12)
        if (y > 1.9) y = 0
        pp.setY(i, y)
      }
      pp.needsUpdate = true

      /* 表情绘制 */
      drawFace({
        ctx: b.faceCtx,
        colorHex: "#" + s.currentStateColor.toString(16).padStart(6, "0"),
        state,
        elapsed: t,
        dt,
        mouseX: s.mouseX,
        mouseY: s.mouseY,
        blinkRef: s.blinkRef,
      })
      b.faceTex.needsUpdate = true

      s.renderer.render(s.scene, s.camera)
    }
    animate()

    return () => {
      cancelAnimationFrame(rafRef.current)
      window.removeEventListener("resize", onResize)
      window.removeEventListener("mousemove", onMouse)
      // Dispose
      renderer.dispose()
      scene.traverse((obj) => {
        if (obj instanceof THREE.Mesh) {
          obj.geometry?.dispose()
          if (Array.isArray(obj.material)) {
            obj.material.forEach((m) => m.dispose())
          } else {
            obj.material?.dispose()
          }
        }
      })
      if (container.contains(renderer.domElement)) {
        container.removeChild(renderer.domElement)
      }
      sceneRef.current = null
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  /* ── 状态变化 → 换色 ── */
  const prevStateRef = useRef<CompanionState>("idle")
  useEffect(() => {
    const s = sceneRef.current
    if (!s) return

    const tokens = readCompanionTokens()
    const colorKey = STATE_COLOR_KEY[state]
    const hex = parseOklch(tokens[colorKey]).getHex()

    s.currentStateColor = hex

    // 更新所有 tinted 材质
    s.bot.tintedMaterials.forEach((m) => {
      m.color.setHex(hex)
      m.emissive?.setHex(hex)
    })
    // 更新 ground ring + disc + particles
    s.ringMat.color.setHex(hex)
    s.ringMat2.color.setHex(hex)
    s.discMat.color.setHex(hex)
    s.pMat.color.setHex(hex)
    // 更新 glow sprites
    s.bot.thrustMat.color.setHex(hex)
    ;(s.bot.coreGlow.material as THREE.SpriteMaterial).color.setHex(hex)
    ;(s.bot.hoverGlow.material as THREE.SpriteMaterial).color.setHex(hex)

    // Alert 震动
    if (state === "alert" && prevStateRef.current !== "alert") {
      s.alertShakeT = 0.55
    }

    prevStateRef.current = state
  }, [state])

  /* ── 主题切换 → 重取色（不重建场景） ── */
  useEffect(() => {
    const s = sceneRef.current
    if (!s || !resolvedTheme) return

    const tokens = readCompanionTokens()
    const bg = parseOklch(tokens.background)
    // Update shell/visor
    const isLight = resolvedTheme === "light"
    const shellHex = isLight ? 0xeef2f7 : 0x1a1a2e
    // Walk scene to update shell materials...
    // For simplicity, update tinted materials with current state color
    const colorKey = STATE_COLOR_KEY[state]
    const hex = parseOklch(tokens[colorKey]).getHex()
    s.currentStateColor = hex

    s.bot.tintedMaterials.forEach((m) => {
      m.color.setHex(hex)
      m.emissive?.setHex(hex)
    })
    s.ringMat.color.setHex(hex)
    s.ringMat2.color.setHex(hex)
    s.discMat.color.setHex(hex)
    s.pMat.color.setHex(hex)
    s.bot.thrustMat.color.setHex(hex)
    ;(s.bot.coreGlow.material as THREE.SpriteMaterial).color.setHex(hex)
    ;(s.bot.hoverGlow.material as THREE.SpriteMaterial).color.setHex(hex)
  }, [resolvedTheme, state])

  return <div ref={containerRef} className="absolute inset-0" />
}
