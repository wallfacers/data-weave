# File Contract Schemas (007-weft-file-contract)

这些 JSON Schema(draft 2020-12)描述 Weft 文件契约中各 YAML 文档的**元数据形状**,是 Java 参考实现(`com.dataweave.master.filecontract`)与未来 Go CLI(D)共同遵循的机器可读契约。

| Schema | 适用文件 | 实体 |
|---|---|---|
| `project.schema.json` | `project.yaml` | 项目 |
| `tags.schema.json` | `tags.yaml` | 标签调色板 |
| `folder.schema.json` | `_folder.yaml` | 类目节点 |
| `task.schema.json` | `<slug>.task.yaml` | 任务(脚本体在独立 `<slug>.<ext>`) |
| `workflow.schema.json` | `<slug>.flow.yaml` | 任务流 + DAG |

约定(非 schema 可表达但属契约):
- **身份 = 文件路径/文件名**(`[a-z0-9_-]+`),不在元数据存 key。
- **确定性输出**:键固定顺序、集合稳定排序、null 省略、LF、indent 2。
- **前向兼容**:`additionalProperties` 在读侧按「忽略未知字段」处理(schema 标 false 仅用于校验生成物,不用于拒绝未来字段)。
- **脚本体**不在 YAML 内,是独立原生语言文件。
