# Contract: 系统 / 元数据 Schema 分类器

## C1. `SystemNamespaceClassifier`（新增）

```java
/** 判定候选限定名是否落在数据源引擎的系统/元数据命名空间。 */
boolean isSystem(String engineTypeCode, String qualifiedName);
```

- 从 `qualifiedName` 取 schema 段（`parseQualifiedName` 或按 `.` 取倒数第二段）；无 schema 段 → 取不到系统 schema → false（裸表名不误判系统）。
- schema 段与引擎系统集合**大小写不敏感**比对，命中 → true。

## C2. 内置系统命名空间集合（按引擎 typeCode）

| 引擎 typeCode | 系统 schema 集合 |
|---|---|
| 通用（所有引擎叠加） | `information_schema` |
| `POSTGRES` | `pg_catalog`, `pg_toast`, `information_schema` |
| `MYSQL` / `MARIADB` / `STARROCKS` / `DORIS` | `mysql`, `sys`, `performance_schema`, `information_schema` |
| `SQLSERVER` | `sys`, `information_schema` |
| `ORACLE` | `sys`, `system` |
| `H2` | `information_schema` |
| 其它 / 未知 | 仅通用 `information_schema` |

## C3. 可配置覆盖

- `lineage.grounding.system-schemas`（可选）：逗号分隔追加集合，合并进内置集（不替换），供长尾引擎/自定义系统 schema 补充。

## 不变量

- 系统排除只在 grounding 阶段生效，且对确定性来源边**只留痕不剔除**（与 ABSENT 剔除同受 FR-011 约束）。
- 分类纯字符串判定，零 IO、零异常逃逸。

**测试锚点**：`isSystem("POSTGRES","pg_catalog.pg_class")`=true；`isSystem("POSTGRES","dw.orders")`=false；`isSystem("MYSQL","information_schema.columns")`=true；`isSystem("H2","PUBLIC.orders")`=false；裸名 `isSystem("POSTGRES","orders")`=false。
