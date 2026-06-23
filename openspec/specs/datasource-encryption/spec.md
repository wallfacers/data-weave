# datasource-encryption Specification

## Purpose
TBD - created by archiving change datasource-management. Update Purpose after archive.
## Requirements
### Requirement: 密码写入时自动加密
系统 SHALL 在数据源创建和更新时，对密码字段使用 AES-256-GCM 算法加密后存入 `password_enc` 列。加密 MUST 使用随机 12 字节 IV，密文格式为 `base64(iv + ciphertext + tag)`。主密钥从环境变量 `DATASOURCE_MASTER_KEY`（32 字节 hex）读取。

#### Scenario: 创建数据源时加密密码
- **WHEN** 创建数据源，提交明文密码 `"my_secret_pw"`
- **THEN** `password_enc` 列存储的值是 base64 编码的 AES-GCM 密文，不等于 `"my_secret_pw"`

#### Scenario: 更新数据源时重新加密
- **WHEN** 更新数据源，提交新的明文密码 `"new_pw"`
- **THEN** `password_enc` 列更新为新的 AES-GCM 密文

#### Scenario: 主密钥未配置时拒绝启动
- **WHEN** 环境变量 `DATASOURCE_MASTER_KEY` 未设置且应用启动
- **THEN** 应用启动失败，日志输出"DATASOURCE_MASTER_KEY is required"

### Requirement: 密码读取时自动解密
系统 SHALL 在需要将密码传递给执行器或连通性测试时，自动解密 `password_enc` 列的密文。解密 MUST 使用与加密相同的主密钥。解密失败 MUST 抛出明确异常，不返回密文原文。

#### Scenario: 执行任务时解密密码
- **WHEN** `DatasourceResolver` 解析数据源连接配置
- **THEN** 返回的密码是解密后的明文，执行器直接使用

#### Scenario: 解密失败
- **WHEN** `password_enc` 列的密文被篡改或主密钥不匹配
- **THEN** 抛出 `DatasourceDecryptException`，消息为"数据源密码解密失败"，不返回密文原文

### Requirement: 前端永不暴露密码明文或密文
系统 SHALL 在所有面向前端的 API 响应中对密码字段脱敏。`GET` 接口返回的密码字段 MUST 为 `"******"` 或不存在。前端提交的空密码 MUST 被识别为"不修改密码"。

#### Scenario: 列表接口密码脱敏
- **WHEN** 客户端请求 `GET /api/datasources`
- **THEN** 每个数据源的密码字段为 `"******"`

#### Scenario: 详情接口密码脱敏
- **WHEN** 客户端请求 `GET /api/datasources/1`
- **THEN** 密码字段为 `"******"`

#### Scenario: 编辑时不传密码
- **WHEN** 客户端提交 `PUT /api/datasources/1`，body 中 `password` 字段为空字符串或不存在
- **THEN** 密码保持原值不变，`password_enc` 列不更新

### Requirement: 密码字段长度安全
`password_enc` 列 MUST 能够容纳 AES-GCM 加密后的最大密文（IV 12B + 明文 + Tag 16B，base64 后约 1.33 倍）。列定义 `VARCHAR(512)` MUST 足以存储加密后的密码（明文最大 256 字符）。

#### Scenario: 长密码加密存储
- **WHEN** 创建数据源，提交 200 字符的明文密码
- **THEN** 加密后的 base64 密文长度不超过 512 字符，正常存入数据库

