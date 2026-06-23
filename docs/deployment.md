# 部署指南（生产）

DataWeave 是前后端分离 + SSE 实时流的架构。生产上线时，**SSE（实时日志、工作流状态、AG-UI 聊天）
的流式行为对反向代理的缓冲配置敏感**——配错会表现为「日志/AI 回复一把输出而非滚屏」。本文给出
上线必改清单。

## SSE 防缓冲（重点）

实时日志（`/api/ops/.../logs/stream`）、工作流状态流（`/api/ops/workflow-instances/.../events/stream`）、
AG-UI 聊天（`/agui`）都是 `text/event-stream` 长连接。任何中间代理若**缓冲**这些响应，就会把逐行 SSE
攒到上游关流才一次性下发，破坏实时性。

平台已做的防护：

1. **后端** `SseNoBufferingWebFilter` 给所有 `text/event-stream` 响应自动加
   `X-Accel-Buffering: no` + `Cache-Control: no-cache`。nginx 识别 `X-Accel-Buffering: no` 后会对该响应
   关闭缓冲（多数反代亦尊重该约定）。
2. **前端** SSE 用 `SSE_BASE` 直连后端，绕过 Next dev rewrite 代理（dev 期 Turbopack 代理会缓冲 SSE）。

部署侧仍建议**显式**关闭 SSE 路径缓冲作双保险（见下方 nginx 示例）。

## 两种部署形态

### 形态 A：前后端独立域名（推荐）

前端 `https://app.example.com`，后端 `https://api.example.com`。前端 SSE/AG-UI 直连后端域名。

需要配置：
- 前端构建期 env：`NEXT_PUBLIC_BACKEND_URL=https://api.example.com`
  （同时作用于 SSE 直连与 AG-UI `NEXT_PUBLIC_AGENT_URL`；后者默认 `${...}/agui`，如有独立值另设）
- 后端 CORS：`app.cors.allowed-origins=https://app.example.com`
  （`allowCredentials=true` 下源必须明确，不能用 `*`；多个用逗号分隔）

后端 WebFlux/Netty 本身不缓冲 SSE，若后端不经反代直接暴露则无需额外缓冲配置。

### 形态 B：同源 nginx 反代统一入口

`https://app.example.com` 下，nginx 把 `/api`、`/agui` 转发后端。此时 SSE 经 nginx，
**nginx 默认 `proxy_buffering on` 会缓冲 SSE**，必须显式关闭：

```nginx
# SSE / AG-UI 流式端点：关闭缓冲、放宽超时
location ~ ^/(api/ops/.+/(logs|events)/stream|agui)$ {
    proxy_pass http://dataweave_backend;
    proxy_http_version 1.1;
    proxy_set_header Connection "";        # keep-alive 长连接
    proxy_buffering off;                    # 关键：关闭缓冲
    proxy_cache off;
    proxy_read_timeout 3600s;              # SSE 长连接别被掐断
    chunked_transfer_encoding off;
}

# 其余 API
location /api/ {
    proxy_pass http://dataweave_backend;
    proxy_http_version 1.1;
}
```

同源形态下前端可不设 `NEXT_PUBLIC_BACKEND_URL`（走同源 + nginx），但需确保 `SSE_BASE`
解析到同源后端入口；CORS 同源时不触发，无需放行额外源。

## 上线检查清单

- [ ] 后端 `app.cors.allowed-origins` 设为生产前端域名（形态 A 必须）
- [ ] 前端 `NEXT_PUBLIC_BACKEND_URL`（及 `NEXT_PUBLIC_AGENT_URL`）指向浏览器可达的后端地址
- [ ] 反代对 SSE 路径 `proxy_buffering off` + 放宽 `proxy_read_timeout`（形态 B 必须）
- [ ] 验证：浏览器跑一个含 `sleep` 的 SHELL 任务，日志应**逐行滚屏**而非结束后一把出现
- [ ] 验证：AI 聊天回复应**逐字/逐段流式**而非整段一次性出现
