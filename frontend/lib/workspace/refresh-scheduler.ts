/**
 * 激活页自动无感刷新的**纯逻辑核心**（与 React 解耦，便于单测）。
 *
 * - RefreshScheduler：调度内核。仅当 active && enabled && visible 时按 intervalMs
 *   轮询；任一信号转入「运行」边沿立即触发一次（切回 tab / 窗口重新可见）；
 *   in-flight 去重——轮询命中在途则跳过（不堆叠），手动 tickNow 命中在途则复用
 *   同一 promise（合并，不并发）。不持有数据，只管「何时该刷新」。
 * - liveDataReducer：取数视图的状态迁移。刷新时**保留旧数据**（loading 仅首屏、
 *   refreshing 表后台重取）；失败保留上次成功数据并置 stale，成功即清。
 *
 * useRefreshSchedule / useLiveData（在 use-api.ts）是绑定 React 的薄包装。
 */

export interface RefreshSignals {
  /** 本视图是否当前激活 tab */
  active: boolean
  /** 自动刷新开关（会话内、默认开） */
  enabled: boolean
  /** 浏览器页面是否可见（整窗后台 → false） */
  visible: boolean
}

export interface SchedulerOptions {
  skipInitialFire?: boolean
}

export interface RefreshScheduler {
  /** 更新部分信号；触发边沿计算与 timer 增删 */
  update(signals: Partial<RefreshSignals>): void
  /** 手动触发一次（不受 enabled/gating 限制；与在途请求合并） */
  tickNow(): Promise<void>
  /** 当前信号是否构成「运行轮询」 */
  isRunning(): boolean
  /** 卸载清理 */
  dispose(): void
}

const running = (s: RefreshSignals) => s.active && s.enabled && s.visible

/**
 * 创建调度内核。`onTick` 可返回 Promise；返回时 in-flight 去重生效。
 * 使用全局 setInterval/clearInterval —— vitest fake timers 可直接接管。
 */
export function createRefreshScheduler(
  onTick: () => void | Promise<void>,
  intervalMs: number,
  initial: Partial<RefreshSignals> = {},
  opts: SchedulerOptions = {},
): RefreshScheduler {
  const signals: RefreshSignals = {
    active: initial.active ?? false,
    enabled: initial.enabled ?? true,
    visible: initial.visible ?? true,
  }
  let timer: ReturnType<typeof setInterval> | null = null
  let inFlight: Promise<void> | null = null
  let disposed = false
  // skipInitialFire 时也跳过首次 enter-running 边沿触发——组件挂载时 DataTable 已自行取数，
  // 调度器只需起定时器，后续 tab 切换回激活时正常触发立即刷新。
  let firstActivation = opts.skipInitialFire

  function fire(): Promise<void> {
    // 合并：在途则复用同一 promise（不并发、不堆叠）
    if (inFlight) return inFlight
    let result: void | Promise<void>
    try {
      result = onTick()
    } catch {
      return Promise.resolve()
    }
    if (!result || typeof (result as Promise<void>).then !== "function") {
      return Promise.resolve()
    }
    const p = (result as Promise<void>).then(
      () => {},
      () => {},
    )
    inFlight = p
    void p.finally(() => {
      if (inFlight === p) inFlight = null
    })
    return p
  }

  function startTimer() {
    if (timer != null) return
    timer = setInterval(() => {
      if (disposed) return
      // 轮询命中在途 → 跳过（不堆叠）
      if (inFlight) return
      void fire()
    }, intervalMs)
  }

  function stopTimer() {
    if (timer != null) {
      clearInterval(timer)
      timer = null
    }
  }

  // 创建时即处于运行态 → 立即一次 + 起轮询（等价于「挂载即激活」边沿）。
  // skipInitialFire：表格视图已有 DataTable 首次取数，跳过创建时的立即触发，仅起定时器。
  if (running(signals)) {
    startTimer()
    if (!opts.skipInitialFire) void fire()
  }

  return {
    update(next) {
      if (disposed) return
      const was = running(signals)
      Object.assign(signals, next)
      const now = running(signals)
      if (now && !was) {
        // 进入运行边沿：立即刷新一次 + 开始轮询。
        // 首次激活（组件刚挂载）跳过——DataTable 已自行取数，避免重复请求。
        startTimer()
        if (!firstActivation) void fire()
        firstActivation = false
      } else if (!now && was) {
        stopTimer()
      }
    },
    tickNow() {
      if (disposed) return Promise.resolve()
      return fire()
    },
    isRunning() {
      return running(signals)
    },
    dispose() {
      disposed = true
      stopTimer()
      inFlight = null
    },
  }
}

// ─── 取数视图状态迁移（纯函数 reducer） ───────────────────────

export interface LiveDataState<T> {
  data: T | null
  loading: boolean
  refreshing: boolean
  error: boolean
  stale: boolean
  lastUpdatedAt: number | null
}

export type LiveDataAction<T> =
  | { type: "start" }
  | { type: "success"; data: T; at: number }
  | { type: "error" }

export function initialLiveDataState<T>(): LiveDataState<T> {
  return { data: null, loading: false, refreshing: false, error: false, stale: false, lastUpdatedAt: null }
}

export function liveDataReducer<T>(s: LiveDataState<T>, a: LiveDataAction<T>): LiveDataState<T> {
  switch (a.type) {
    case "start":
      // 有数据 → 后台 refreshing（不闪）；无数据 → 首屏 loading
      return s.data == null
        ? { ...s, loading: true, refreshing: false }
        : { ...s, refreshing: true }
    case "success":
      return {
        data: a.data,
        loading: false,
        refreshing: false,
        error: false,
        stale: false,
        lastUpdatedAt: a.at,
      }
    case "error":
      // 保留上次成功数据；有数据才算「过时」
      return { ...s, loading: false, refreshing: false, error: true, stale: s.data != null }
    default:
      return s
  }
}
