# Contract: 发言身份服务端认定（chat / markHandled / reverify / close）

统一响应包络（既有约定）：HTTP 200 + `{"code": 0, "data": …}`；错误经 `BizException(code,args)` + `GlobalExceptionHandler` 本地化。

## POST /api/incidents/{id}/chat（改）

**请求**（鉴权：Bearer JWT + `X-Project-Id`，既有）：

```json
{ "text": "帮我看下这个失败的根因" }
```

**变更**：
- body 中 `actor` 字段**废弃**——服务端仍接受该字段存在（不报错）但**一律忽略**；
- 服务端从 JWT（`TenantContext.username()`）认定 `actor`，经 user 表（带缓存）解析 `actor_name`；
- 后台 Agent 轮次提交线程池**之前**在 controller/service 边界完成身份捕获（ThreadLocal 不透传）。

**落库效果**：`HUMAN_SAY` 消息 `actor=<username>`、`actor_name=<displayName|NULL 回退 username>`。

**错误**：`text` 空 → 既有校验错误码；未认证 → 401（既有 JwtAuthFilter 行为）。

## POST /api/incidents/{id}/mark-handled | /reverify | /close（改）

同上：body `actor` 废弃忽略，`ACTION` 消息身份改为服务端认定。请求/响应结构其余不变。

## 兼容性

- 前端 `lib/supervision/api.ts` 移除所有 actor 传参；旧客户端仍传 actor 不破坏（忽略语义）。
- 存量消息 `actor="user"/"ui-user"`、`actor_name=NULL`：展示层兜底中性称谓，接口不做数据迁移。
