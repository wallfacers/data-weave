# Contracts: 测试依赖的既有 API

> 本 feature **不新增 API**。以下既有端点由压测脚本 `cron-stress.sh` 调用,完整定义见 `WorkflowController.java` / `TaskController.java` / `OpsController.java` / `AuthController.java`。

## 认证

| 方法 | 路径 | body | 返回 |
|---|---|---|---|
| POST | `/api/auth/login` | `{username:"admin",password:"admin"}` | `data.token` |

后续请求:`Authorization: Bearer <token>` + `X-Project-Id: 1`。

## 创建压测载荷

| 方法 | 路径 | body 摘要 | 返回 |
|---|---|---|---|
| POST | `/api/tasks` | `{name,type:"ECHO",content}` | `data.id` |
| POST | `/api/tasks/{id}/publish` | `{}` | — |
| POST | `/api/workflows` | `{name,scheduleType:"CRON",cron,priority}` | `data.id` |
| PUT | `/api/workflows/{id}/dag` | `{version:null,nodes:[{nodeKey,nodeType:"TASK",taskId,name,posX,posY}],edges:[]}` | — |
| POST | `/api/workflows/{id}/publish` | `{}` | — |
| POST | `/api/workflows/{id}/run` | `{}` | `data.outcome`(EXECUTED)、`data.resultInstanceId` |

`run` 返回 `outcome=EXECUTED` 仍可能是 `MANUAL` 触发;**cron 自动触发不经此端点**,而是调度器内部 fire 直建实例(trigger_type=CRON)。

## 观测

| 方法 | 路径 | 关键返回 |
|---|---|---|
| GET | `/api/ops/workflow-instances?page=1&size=200` | `data.items[]`(含 `triggerType` / `state` / `scheduledFireTime` / `startedAt`)、`data.total` |
| GET | `/api/ops/instances?page=1&size=200` | `data.items[]`(含 `state` / `workerNodeCode`)、`data.total` |
| GET | `/actuator/prometheus` | `scheduler_*` / `dispatch_*` / `hikaricp_*` 文本格式 |
| GET | `/api/health` | 200(免认证,探活) |
| GET | `/api/fleet` | worker 注册状态(免认证) |

> 注意:`/api/ops/*` 返回 `data.items / data.total`(不是 `data.content / data.totalElements`)。脚本已适配。

## 清理

| 方法 | 路径 |
|---|---|
| POST | `/api/workflows/{id}/offline` |
| DELETE | `/api/workflows/{id}` |
| POST | `/api/tasks/{id}/offline` |
| DELETE | `/api/tasks/{id}` |

## 直接 DB 观测(可选,绕过 API)

```bash
docker exec dataweave-postgres psql -U dataweave -d dataweave -t -c \
  "select count(*) from cron_fire where workflow_id in (<本次 wf ids>);"
```
