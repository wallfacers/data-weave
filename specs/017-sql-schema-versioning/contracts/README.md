# Contracts — 017 SQL 脚本重梳理与严格 Schema 版本设计

本特性为 **SQL 资源治理**，按 FR-014 **不新增任何 HTTP/REST API、不改动现有 API 行为**，故无 OpenAPI/接口契约。

本目录的「契约」是**数据库 schema 契约**——即权威 schema 的版本戳与不变量，记录于 [schema-version.contract.md](schema-version.contract.md)，由 `SchemaVersionIT` 集成测试守护。
