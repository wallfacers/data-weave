/**
 * 原创全息机器人管家 —— 纯代码程序化构建。
 *
 * 版权归属：Weft 项目原创资产，零第三方 3D 模型/贴图依赖。
 * 约束（见 tmp/companion-prototype/NOTICE.md）：
 *   产品文案不得关联任何影视角色名；管家名 "Vega" 为产品名，不翻译。
 *
 * 本文件只负责几何体构建与材质引用暴露；
 * 颜色由调用方从 CSS token（--companion-*）读取后注入，
 * 主题切换时调用方更新材质 .color / .emissive 即可，无需重建。
 */
import * as THREE from "three"

export interface BotColors {
  /** 外壳色（shell/ear/torso/arm，取自 --background 派生） */
  shell: THREE.Color
  /** 面屏色（visor/belt，深色） */
  visor: THREE.Color
  /** 装饰/能量色（trim/halo/coreRing/hoverRing/palm，默认 = --companion-idle） */
  trim: THREE.Color
  /** 核心发光色（core/coreGlow/hoverGlow，默认 = --companion-idle） */
  glow: THREE.Color
  /** 尾焰粒子色（默认 = --companion-idle） */
  thrust: THREE.Color
}

export interface BotModel {
  group: THREE.Group
  head: THREE.Group
  /** 所有需要随状态换色的 MeshStandardMaterial */
  tintedMaterials: THREE.MeshStandardMaterial[]
  /** 胸口能量核心材质（单独引用方便脉冲动画） */
  coreMat: THREE.MeshStandardMaterial
  /** 能量核心发光 sprite */
  coreGlow: THREE.Sprite
  /** 底部悬浮发光 sprite */
  hoverGlow: THREE.Sprite
  /** 头顶光环 */
  halo: THREE.Mesh
  /** 底部悬浮环 */
  hoverRing: THREE.Mesh
  /** 尾焰粒子 */
  thrustPoints: THREE.Points
  /** 尾焰粒子材质 */
  thrustMat: THREE.PointsMaterial
  /** 左臂 group */
  armL: THREE.Group
  /** 右臂 group */
  armR: THREE.Group
  /** 表情 CanvasTexture */
  faceTex: THREE.CanvasTexture
  /** 表情 canvas 2D context */
  faceCtx: CanvasRenderingContext2D
}

function makeGlowTexture(): THREE.CanvasTexture {
  const c = document.createElement("canvas")
  c.width = c.height = 128
  const g = c.getContext("2d")!
  const grad = g.createRadialGradient(64, 64, 0, 64, 64, 64)
  grad.addColorStop(0, "rgba(255,255,255,1)")
  grad.addColorStop(0.25, "rgba(255,255,255,0.55)")
  grad.addColorStop(1, "rgba(255,255,255,0)")
  g.fillStyle = grad
  g.fillRect(0, 0, 128, 128)
  return new THREE.CanvasTexture(c)
}

function makeGlow(tex: THREE.CanvasTexture, color: THREE.Color, size: number): THREE.Sprite {
  const mat = new THREE.SpriteMaterial({
    map: tex,
    color,
    transparent: true,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
  })
  const s = new THREE.Sprite(mat)
  s.scale.setScalar(size)
  return s
}

function makeArm(side: number, shellMat: THREE.MeshStandardMaterial, trimMat: THREE.MeshStandardMaterial): THREE.Group {
  const arm = new THREE.Group()
  arm.position.set(side * 0.33, 0.16, 0)

  const shoulder = new THREE.Mesh(new THREE.SphereGeometry(0.062, 28, 28), shellMat)
  shoulder.scale.set(0.85, 1.1, 0.9)
  arm.add(shoulder)

  const fore = new THREE.Mesh(new THREE.CapsuleGeometry(0.038, 0.16, 8, 20), shellMat)
  fore.position.y = -0.17
  arm.add(fore)

  const palm = new THREE.Mesh(new THREE.SphereGeometry(0.032, 20, 20), trimMat.clone())
  palm.material.emissiveIntensity = 0.9
  palm.position.y = -0.3
  arm.add(palm)

  arm.rotation.z = side * 0.12
  return arm
}

/**
 * 构建完整机器人管家 Group。
 * @param colors 从 CSS token 读取的颜色（壳/面屏/装饰/发光/尾焰）
 */
export function createBot(colors: BotColors): BotModel {
  const glowTex = makeGlowTexture()

  /* ── 材质 ── */
  const shellMat = new THREE.MeshStandardMaterial({
    color: colors.shell,
    roughness: 0.32,
    metalness: 0.08,
  })
  const visorMat = new THREE.MeshStandardMaterial({
    color: colors.visor,
    roughness: 0.12,
    metalness: 0.45,
  })
  const trimMat = new THREE.MeshStandardMaterial({
    color: colors.trim,
    emissive: colors.trim,
    emissiveIntensity: 1.4,
    roughness: 0.4,
  })

  const bot = new THREE.Group()
  bot.position.y = 1.0

  /* ── 头部 ── */
  const head = new THREE.Group()
  head.position.y = 0.46
  bot.add(head)

  const skull = new THREE.Mesh(new THREE.SphereGeometry(0.21, 48, 48), shellMat)
  skull.scale.set(1, 0.82, 0.92)
  head.add(skull)

  const visor = new THREE.Mesh(new THREE.SphereGeometry(0.185, 48, 48), visorMat)
  visor.scale.set(0.82, 0.58, 0.78)
  visor.position.set(0, -0.01, 0.09)
  head.add(visor)

  /* 表情屏 */
  const faceCanvas = document.createElement("canvas")
  faceCanvas.width = 256
  faceCanvas.height = 128
  const faceCtx = faceCanvas.getContext("2d")!
  const faceTex = new THREE.CanvasTexture(faceCanvas)
  const facePlane = new THREE.Mesh(
    new THREE.PlaneGeometry(0.24, 0.12),
    new THREE.MeshBasicMaterial({ map: faceTex, transparent: true, depthWrite: false })
  )
  facePlane.position.set(0, -0.01, 0.238)
  head.add(facePlane)

  /* 头顶光环 */
  const halo = new THREE.Mesh(new THREE.TorusGeometry(0.075, 0.008, 12, 40), trimMat.clone())
  halo.position.y = 0.24
  halo.rotation.x = Math.PI / 2.4
  head.add(halo)

  /* 两侧耳部凸起 */
  ;[-1, 1].forEach((s) => {
    const ear = new THREE.Mesh(new THREE.SphereGeometry(0.045, 24, 24), shellMat)
    ear.scale.set(0.6, 1, 1)
    ear.position.set(s * 0.195, 0, 0)
    head.add(ear)
    const earGlow = new THREE.Mesh(new THREE.SphereGeometry(0.018, 16, 16), trimMat.clone())
    earGlow.position.set(s * 0.215, 0, 0)
    earGlow.scale.set(0.5, 1, 1)
    head.add(earGlow)
  })

  /* ── 蛋形身体 ── */
  const torso = new THREE.Mesh(new THREE.SphereGeometry(0.24, 48, 48), shellMat)
  torso.scale.set(1, 1.28, 0.88)
  torso.position.y = 0.02
  bot.add(torso)

  /* 胸口能量核心 */
  const coreMat = new THREE.MeshStandardMaterial({
    color: colors.glow,
    emissive: colors.glow,
    emissiveIntensity: 2.2,
    roughness: 0.3,
  })
  const core = new THREE.Mesh(new THREE.CircleGeometry(0.045, 32), coreMat)
  core.position.set(0, 0.1, 0.207)
  core.rotation.x = -0.18
  bot.add(core)

  const coreRing = new THREE.Mesh(new THREE.TorusGeometry(0.062, 0.006, 12, 40), trimMat.clone())
  coreRing.position.copy(core.position)
  coreRing.rotation.x = -0.18
  bot.add(coreRing)

  const coreGlow = makeGlow(glowTex, colors.glow, 0.35)
  coreGlow.position.set(0, 0.1, 0.24)
  bot.add(coreGlow)

  /* 腰部装饰环 */
  const belt = new THREE.Mesh(new THREE.TorusGeometry(0.205, 0.012, 14, 48), visorMat)
  belt.position.y = -0.12
  belt.rotation.x = Math.PI / 2
  belt.scale.set(1, 0.88, 1)
  bot.add(belt)

  /* ── 浮空手臂 ── */
  const armL = makeArm(-1, shellMat, trimMat)
  const armR = makeArm(1, shellMat, trimMat)
  bot.add(armL)
  bot.add(armR)

  /* ── 底部悬浮推进环 + 尾焰粒子 ── */
  const hoverRing = new THREE.Mesh(new THREE.TorusGeometry(0.13, 0.014, 14, 48), trimMat.clone())
  hoverRing.position.y = -0.4
  hoverRing.rotation.x = Math.PI / 2
  bot.add(hoverRing)

  const hoverGlow = makeGlow(glowTex, colors.glow, 0.5)
  hoverGlow.position.y = -0.42
  bot.add(hoverGlow)

  const THRUST = 60
  const tGeo = new THREE.BufferGeometry()
  const tPos = new Float32Array(THRUST * 3)
  for (let i = 0; i < THRUST; i++) {
    tPos[i * 3] = (Math.random() - 0.5) * 0.16
    tPos[i * 3 + 1] = -0.42 - Math.random() * 0.5
    tPos[i * 3 + 2] = (Math.random() - 0.5) * 0.16
  }
  tGeo.setAttribute("position", new THREE.BufferAttribute(tPos, 3))
  const thrustMat = new THREE.PointsMaterial({
    color: colors.thrust,
    size: 0.014,
    transparent: true,
    opacity: 0.8,
    blending: THREE.AdditiveBlending,
    depthWrite: false,
  })
  const thrust = new THREE.Points(tGeo, thrustMat)
  bot.add(thrust)

  /* 收集所有需要随状态换色的材质引用 */
  const tintedMaterials: THREE.MeshStandardMaterial[] = [
    trimMat,
    halo.material as THREE.MeshStandardMaterial,
    coreMat,
    coreRing.material as THREE.MeshStandardMaterial,
    hoverRing.material as THREE.MeshStandardMaterial,
    (armL.children[2] as THREE.Mesh).material as THREE.MeshStandardMaterial,
    (armR.children[2] as THREE.Mesh).material as THREE.MeshStandardMaterial,
  ]

  return {
    group: bot,
    head,
    tintedMaterials,
    coreMat,
    coreGlow,
    hoverGlow,
    halo,
    hoverRing,
    thrustPoints: thrust,
    thrustMat,
    armL,
    armR,
    faceTex,
    faceCtx,
  }
}
