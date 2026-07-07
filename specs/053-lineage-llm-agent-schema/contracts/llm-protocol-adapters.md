# Contract: 双协议 LLM 适配（Anthropic / OpenAI）

## C1. 归一接口

```java
interface LlmProtocolAdapter {
    String protocol();                         // "ANTHROPIC" | "OPENAI"
    HttpRequest buildRequest(AgentLineageConfig cfg, String prompt, String apiKeyPlain);
    AgentExtraction parseResponse(String body);   // 归一为统一结构；解析失败抛→上层降级
}
```

`LlmAgentClient` 按 `cfg.protocol()` 选适配器，`HttpClient.send`（超时=`cfg.timeout_ms`），把 `AgentExtraction` 返给 `AgentLineageExtractor`。两协议对上层语义等价（FR-002）。

## C2. Anthropic 协议（Messages API）

- 端点：`{base_url}/v1/messages`
- 头：`x-api-key: {key}`、`anthropic-version: 2023-06-01`、`content-type: application/json`
- 请求要点：`model`、`max_tokens`、`system`（抽取指令 + schema 接地）、`messages:[{role:user, content: 脚本/SQL}]`；结构化输出用 `tools` + `tool_choice`（强制调用 `emit_lineage` 工具，参数即 `{reads, writes, columnEdges}`）。
- 响应：`content[]` 中 `type=tool_use` 的 `input` = 结构化血缘。

## C3. OpenAI 协议（Chat Completions）

- 端点：`{base_url}/v1/chat/completions`
- 头：`Authorization: Bearer {key}`、`content-type: application/json`
- 请求要点：`model`、`messages:[{role:system,...},{role:user,...}]`、`response_format:{type:json_schema, json_schema:{...}}`（强制 JSON 输出）。
- 响应：`choices[0].message.content` = JSON 字符串，解析为 `{reads, writes, columnEdges}`。

## C4. 提示构造（LineageExtractionPrompt）

- 系统指令：从给定脚本/SQL 抽取读表、写表及（可解析时）字段级派生；**只输出文本中真实出现的表**；不确定不臆造（宁缺毋滥）。
- **schema 接地（US3/FR-016）**：若 `DatasourceBoundCatalog` 解析到候选表真实列清单，注入提示（`表名 → [列...]`），并约束字段边列名必须来自该集合。
- 输出 schema：`{reads: string[], writes: string[], columnEdges: [{srcTable, srcColumn, dstTable, dstColumn}], confidence: number}`。

## C5. 安全（FR-020）

- `apiKeyPlain` 仅在 `buildRequest` 入参内即用即弃，来自 `DatasourceEncryptor.decrypt(config.apiKeyEnc)`。
- 请求/响应日志 MUST 脱敏 key 与（可选）脚本内容；异常信息不得含明文 key。
