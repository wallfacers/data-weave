# Data Model: 监督席对话体验企业级打磨（070）

## 1. incident_message（改，唯一 DDL 变更）

既有表（schema.sql `incident_message`），本期**新增一列**：

| 列 | 类型 | 约束 | 说明 |
|---|---|---|---|
| id | UUID | PK | 既有 |
| incident_id | UUID | NOT NULL | 既有 |
| seq | BIGINT | UNIQUE(incident_id, seq) | 既有，排序与去重依据 |
| kind | VARCHAR(16) | CHECK IN ('AGENT_STEP','AGENT_SAY','HUMAN_SAY','ACTION','PROPOSAL','SYSTEM') | 既有，枚举不变 |
| content | TEXT | | 既有，富文本原文（Markdown） |
| payload_json | TEXT | | 既有；打断收尾的 AGENT_SAY 增加 `"interrupted": true` 键 |
| actor | VARCHAR(64) | | 既有语义不变：`ops-agent` \| **username（服务端认定）** \| `system` |
| **actor_name** | **VARCHAR(128)** | **可空（新增）** | **显示名（displayName），仅 HUMAN_SAY/ACTION 类由服务端填写；存量行 NULL** |
| created_at | TIMESTAMP | | 既有 |

**schema_version**: `0.19.0` → **`0.20.0`**（schema.sql 文件头 + `schema_version` 种子行同步）。

**校验规则**：
- `actor`/`actor_name` 由服务端写入，任何来自请求 body 的 actor 字段一律忽略（FR-010）。
- `actor_name` 允许 NULL；展示层兜底链：`actor_name` →（actor 为已知系统标识时的系统称谓）→ 中性称谓「操作员/Operator」（FR-011）。

## 2. IncidentMessage（domain record，改）

```
IncidentMessage(id, incidentId, seq, kind, content, payloadJson, actor, actorName /*新增*/, createdAt)
```

传导面：`IncidentMessageRepository.append(...)/map(...)`、`IncidentConversationService`、`IncidentAgentService`、`IncidentSweeper`、`DefaultPlatformActionExecutor` 的落库调用点全部带新参（Agent/system 路径传 NULL）。SSE `MessageAppended` 与 REST 历史随 record 序列化自动携带 `actorName`。

## 3. policy_rules 种子（新增一行）

对齐既有 56-60 行格式：

```
('TOOL', 'incident_agent_cancel', NULL, 'L0', '打断当前事故 Agent 输出轮次（低风险防护性操作，直执行+留痕）', enabled=1, sort_order=20)
```

L0 ⇒ PolicyEngine 直接放行执行，`agent_action` 审计记录照常落（FR-008）。

## 4. 打断句柄注册表（内存态，master 模块）

```
IncidentConversationService:
  ConcurrentHashMap<UUID /*incidentId*/, AtomicBoolean /*cancelled*/> activeTurns
```

**生命周期**：`respond()` 入池执行前注册（同一 incident 重复 respond 时后者覆盖前者句柄——既有 chatPool=2 线程串行度低，冲突窗口极小，覆盖语义可接受）；轮次自然结束/异常/被打断均在 finally 清除。**不持久化**：进程重启丢失在途轮次本就终止，cancel 幂等语义覆盖。

**状态转移（一次 Agent 对话轮次）**：

```
RUNNING ──自然完成──> FINALIZED（AGENT_SAY 落库，payload 无 interrupted）
RUNNING ──interrupt()──> CANCELLED（部分内容 AGENT_SAY 落库，payload.interrupted=true；发既有收尾事件带 streamId）
(无在途轮次) ──interrupt()──> NO_OP（幂等成功，cancelled=false）
```

竞态规则（spec 边界情况）：interrupt 与自然完成竞争时**先落库者胜**——句柄置位后读循环已退出的情况下不二次落库，前端以落库消息为最终真相（既有 SC 语义）。

## 5. 前端状态（lib/supervision）

### ConnectionPhase（新增，store 内）

```
"connecting"（初始/重连握手中） → "live"（收到 snapshot） ⇄ "degraded"（EventSource error）
```

驱动规则：`connecting` ⇒ LoadingState；`live && feed 空` ⇒ 空态；`degraded` ⇒ 顶部提示条 + 消息保留（FR-001/FR-002）。

### Message（types.ts，改）

```
Message { …既有字段…, actor: string, actorName?: string }
```

派生判定（渲染层纯函数，不进 store）：
- `isSelf = msg.actor === useAuth().user.username`
- `displayName = msg.actorName ?? 系统称谓映射(msg.actor) ?? t("supervision.fallbackOperator")`

### 渲染分组模型（纯函数 `groupMessages(messages): RenderItem[]`）

```
RenderItem = DateSeparator { date }
           | MessageGroup { actorKey, showHeader /*首条带头像姓名*/, messages[] }
```

规则：跨自然日插 DateSeparator；相邻消息同 actor 且间隔 ≤5min 并入同组（FR-012）。纯函数、vitest 直测边界（正好 5min、跨日首条、actor 交替）。

### Composer 状态机

```
idle（发送键；空文本 disabled）
 └─ 提交 → sending（POST 在途，控件禁用）
      ├─ 2xx → idle（清空输入）
      └─ 失败 → idle（输入保留 + toast 后端消息）
streaming（store 中该 incident 存在活跃 streamId）⇒ 发送键替换为停止键
 └─ 停止 → cancel 在途（键禁用）→ 成功: 等待收尾事件回 idle ／ 失败超时: 回弹可重试 + toast
```

## 6. 不变量（既有，明确不动）

- 消息排序与去重唯一依据 `(incident_id, seq)`；REST 历史 + SSE 增量在 store 按 seq 合并。
- delta 流式缓冲以 `streamId` 关联，落库消息到达即收尾清空缓冲——打断路径复用同一收尾事件，不新增事件类型。
- 发送为服务端回显模型，无乐观插入（FR-018）。
