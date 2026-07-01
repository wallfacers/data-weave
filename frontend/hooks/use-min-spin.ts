"use client"

import { useEffect, useRef, useState } from "react"

/**
 * 把一个可能瞬间结束的「进行中」布尔量兜底成「至少旋转 minMs」的展示态。
 *
 * 场景：刷新按钮跟随 refreshing/loading 转圈，但本地或秒回接口一次刷新可能在
 * ~30ms 内完成，直接跟随会让图标只闪一帧、肉眼看不到「在转」。本 hook 在 active
 * 上升沿点亮 spinning，并兜底到满 minMs（默认一整圈，animate-spin 默认 1s/圈，
 * 停在起点更干净）后由定时器熄灭。自动刷新与手动点击都经此，行为一致。
 *
 * 实现遵守 react-hooks 规则：上升沿点亮走渲染期「记录上一次值」官方模式（非
 * effect setState）；起点时间戳的 ref 写在 effect 内；熄灭的 setState 仅在定时器
 * 回调内。
 */
export function useMinSpin(active: boolean, minMs = 1000): boolean {
  const [spinning, setSpinning] = useState(false)
  const [prevActive, setPrevActive] = useState(active)
  const startRef = useRef(0)

  if (active !== prevActive) {
    setPrevActive(active)
    if (active) setSpinning(true)
  }

  // spinning 点亮时记录起点（ref 写在 effect 内，符合 react-hooks 规则）。
  useEffect(() => {
    if (spinning && startRef.current === 0) startRef.current = Date.now()
  }, [spinning])

  // active 结束且仍在转：续到满 minMs 再停（setState 仅在定时器回调内）。
  useEffect(() => {
    if (active || !spinning) return
    const remaining = Math.max(0, minMs - (Date.now() - startRef.current))
    const timer = setTimeout(() => {
      setSpinning(false)
      startRef.current = 0
    }, remaining)
    return () => clearTimeout(timer)
  }, [active, spinning, minMs])

  return spinning
}
