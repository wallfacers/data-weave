# Contract: GPT-5.6 Teacher Client（httpx 裸 POST）

## 接口

`clients.py` 新增后端 + 注册两个 client，复用现有 `LlmClient` / `SYSTEM_PROMPT` / `_parse_lineage_json` / `_user_msg`。

```
_openai_raw_backend(base_url_env, key_env, model_env_default) -> call(model, user) -> dict
```

- **实现**：httpx.Client POST `${base_url}/chat/completions`，header `Authorization: Bearer $key` + `Content-Type: application/json`，body `{model, messages:[{system},{user}]}`。**不注入任何 `x-stainless-*` 头**（触发中转站 WAF）。
- **不传 temperature**（gpt-5.6 reasoning 档，冒烟测证 temperature=0 被 WAF 连带拦；裸 POST 默认即可）。
- **返回**：`_parse_lineage_json(content)` + `_usage`（`prompt_tokens`/`completion_tokens`）。
- **错误**：非 200 / 解析失败 → `{"reads":[], "writes":[], "_error": ...}`（该 teacher 对该条弃权）。

## 注册

```
m_gpt      = LlmClient("m_gpt",      _openai_raw_backend("GPT_BASE_URL","GPT_API_KEY"), env GPT_MODEL default "gpt-5.6-sol")   # gold 裁判
m_gpt_bulk = LlmClient("m_gpt_bulk", _openai_raw_backend("GPT_BASE_URL","GPT_API_KEY"), "gpt-5.6-luna")                          # silver bulk 便宜档
```

- 仅当 `GPT_API_KEY` 存在时注册（缺失 → `[warn] skip`，同 m1/m2 模式）。

## 契约测试（`test_gpt_backend.py`，mock httpx）

1. **不含 stainless 头**：断言 POST 请求头无 `x-stainless-*`。
2. **解析血缘 JSON + usage**：mock 200 返回含 reads/writes/usage → 结构正确、`_usage` 抓到。
3. **非 200 弃权**：mock 403 → 返回空 reads/writes + `_error`，不抛。
4. **解析失败弃权**：mock 200 但 content 非 JSON → 空 + `_error`。
5. **不注册当无 key**：unset GPT_API_KEY → `load_clients()` 无 m_gpt。
