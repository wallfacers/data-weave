"use client"

/**
 * 070 US5 无抖动跟随滚动（配合 DwScroll/OverlayScrollbars）。
 *
 * 位于底部时内容增长平滑跟随（rAF 去重，每帧 ≤1 次）；用户上滑（wheel/touch）离开底部即暂停跟随并
 * 暴露 isAtBottom=false（供「回到底部」按钮）；结构性依赖变化（如切换事故）强制回到底部。
 * 观测经 MutationObserver + ResizeObserver 合流到单一 follow 回调。
 */
import { useCallback, useEffect, useRef, useState } from "react"
import type { OverlayScrollbarsComponentRef } from "overlayscrollbars-react"

const AT_BOTTOM_THRESHOLD_PX = 60
const UP_INTENT_PX = 3

export function useAutoScroll(deps: unknown[]) {
  const osRef = useRef<OverlayScrollbarsComponentRef | null>(null)
  const [isAtBottom, setIsAtBottom] = useState(true)
  const atBottomRef = useRef(true)
  const rafRef = useRef<number | null>(null)

  const viewport = useCallback((): HTMLElement | null => {
    return osRef.current?.osInstance()?.elements().viewport ?? null
  }, [])

  const scrollToBottom = useCallback(() => {
    const vp = viewport()
    if (vp) vp.scrollTop = vp.scrollHeight
    atBottomRef.current = true
    setIsAtBottom(true)
  }, [viewport])

  // 跟随 + 上滑意图暂停：监听 viewport 的 scroll/wheel/touch，rAF 合并 content 增长跟随。
  useEffect(() => {
    let vp: HTMLElement | null = null
    let mo: MutationObserver | null = null
    let ro: ResizeObserver | null = null
    let touchStartY = 0

    const computeAtBottom = (el: HTMLElement) =>
      el.scrollHeight - el.scrollTop - el.clientHeight < AT_BOTTOM_THRESHOLD_PX

    const onScroll = () => {
      if (!vp) return
      const atb = computeAtBottom(vp)
      atBottomRef.current = atb
      setIsAtBottom(atb)
    }
    const pauseIfUp = (deltaY: number) => {
      if (deltaY < -UP_INTENT_PX) {
        atBottomRef.current = false
        setIsAtBottom(false)
      }
    }
    const onWheel = (e: WheelEvent) => pauseIfUp(e.deltaY)
    const onTouchStart = (e: TouchEvent) => {
      touchStartY = e.touches[0]?.clientY ?? 0
    }
    const onTouchMove = (e: TouchEvent) => {
      const y = e.touches[0]?.clientY ?? 0
      pauseIfUp(touchStartY - y < 0 ? -(touchStartY - y) : 0) // 手指下移=内容上滑
      touchStartY = y
    }
    const follow = () => {
      if (rafRef.current != null) return
      rafRef.current = requestAnimationFrame(() => {
        rafRef.current = null
        if (atBottomRef.current && vp) vp.scrollTop = vp.scrollHeight
      })
    }

    // OverlayScrollbars 挂载后 viewport 才就绪——mount 后取，取不到则下一帧重试一次。
    const attach = () => {
      vp = viewport()
      if (!vp) {
        rafRef.current = requestAnimationFrame(attach)
        return
      }
      vp.style.overflowAnchor = "auto"
      vp.addEventListener("scroll", onScroll, { passive: true })
      vp.addEventListener("wheel", onWheel, { passive: true })
      vp.addEventListener("touchstart", onTouchStart, { passive: true })
      vp.addEventListener("touchmove", onTouchMove, { passive: true })
      mo = new MutationObserver(follow)
      mo.observe(vp, { childList: true, subtree: true, characterData: true })
      ro = new ResizeObserver(follow)
      const content = (vp.firstElementChild as HTMLElement) ?? vp
      ro.observe(content)
      scrollToBottom()
    }
    attach()

    return () => {
      if (rafRef.current != null) cancelAnimationFrame(rafRef.current)
      rafRef.current = null
      if (vp) {
        vp.removeEventListener("scroll", onScroll)
        vp.removeEventListener("wheel", onWheel)
        vp.removeEventListener("touchstart", onTouchStart)
        vp.removeEventListener("touchmove", onTouchMove)
      }
      mo?.disconnect()
      ro?.disconnect()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [viewport, scrollToBottom])

  // 结构性依赖变化（切换事故等）→ 强制回底重新跟随。
  useEffect(() => {
    scrollToBottom()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps)

  return { osRef, isAtBottom, scrollToBottom }
}
