# Contract: EMAIL 通道配置（AlertChannel.config_json）

EMAIL 类型 `AlertChannel` 的 `config_json` 形态。SMTP 连接参数**不在此**，由应用配置 `spring.mail.*` 注入。

## config_json schema

```json
{
  "recipients": ["ops@example.com", "oncall@example.com"],
  "cc": ["lead@example.com"],
  "subjectPrefix": "[Weft Alert]"
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `recipients` | 是 | 收件人列表，至少一个有效地址 |
| `cc` | 否 | 抄送列表 |
| `subjectPrefix` | 否 | 邮件主题前缀，缺省用默认前缀 |

## 邮件正文契约（最低含字段）

EMAIL 投递的邮件正文 MUST 含：规则名、严重度、触发指标值、发生时间、关联对象（如 metric_key）。具体排版实现自定，但上述字段不可缺。

## SMTP 连接（spring.mail.*，不在 config_json）

```yaml
spring:
  mail:
    host: smtp.example.com
    port: 587
    username: ...
    password: ...
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
```

## 校验与降级

- `recipients` 为空 / 无 `spring.mail.host` 配置 → `DispatchResult.notConfigured`（不抛错、不假成功）。
- SMTP 连接/认证失败 → `DispatchResult.failed(reason)`，不阻断其它通道。
