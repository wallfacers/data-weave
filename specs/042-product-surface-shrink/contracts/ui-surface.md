# UI Surface Contract: 收缩后的工作区对外面

本特性无 HTTP API 契约（服务端零改动）。对外契约是工作区的 URL 面与降级行为。

## 1. 深链参数 `/?open=<view>`

| 输入 | 行为 |
|---|---|
| `/?open=` + 保留视图（14 个之一） | 打开对应视图（不变） |
| `/?open=marketplace` / `reports` / `service` / `integration` | **回退默认视图**，无白屏、无用户可见错误（既有 isKnownView 守卫） |
| `/?open=<任意未知串>` | 同上（不变） |

## 2. 遗留 redirect 路由

| 路由 | 收缩前 | 收缩后 |
|---|---|---|
| `/integration` | `redirect("/?open=integration")` | `redirect("/")` |
| `/service` | `redirect("/?open=service")` | `redirect("/")` |
| `/metrics` | `redirect("/?open=reports")` | `redirect("/")` |
| 其余遗留路由（/ops /tasks /fleet /catalog /quality /lineage） | 不变 | 不变 |

## 3. 左侧导航清单（收缩后全量）

| 分组 | 入口 |
|---|---|
| dev | workflow-canvas |
| ops | ops、metrics、fleet、freshness |
| alerting | alerts、event-center |
| governance | catalog、quality、lineage |
| assets | datasources |
| admin | settings |

共 12 个入口（原 16）；`analytics` 分组不复存在；上下文详情视图（instance-log、workflow-instance-detail）仍不作为入口。

## 4. 默认常驻（Pinned）标签

`freshness`、`metrics`（原含 `reports`）。首开工作区即此二者，均不可关闭。

## 5. 快照恢复降级

含已删除视图的历史快照：该标签静默丢弃；若其为激活标签，激活态回退到剩余标签；极端情况（全部标签被丢弃）回到 Pinned 底座。控制台不抛异常。
