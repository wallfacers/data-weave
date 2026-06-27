# Data Model — Weft 子特性 C(pull/push API)

C **不新增持久化实体/表**。本文件定义:① 传输 DTO(瞬态记录);② 复用的领域聚合;③ push 落库匹配与快照映射规则。

---

## 1. 传输 DTO(瞬态,非持久化)

落点:`application/ProjectSyncDtos.java`(Java records)。HTTP 经 `ApiResponse<T>` 包裹。

### 1.1 SyncBundle(pull 响应体 / push 请求体的文件部分)
```
record SyncBundle(Map<String,String> files)   // 相对路径 → UTF-8 内容;= B 的 ProjectFileBundle 形态
```

### 1.2 PullResult(pull 响应 data)
| 字段 | 类型 | 含义 |
|------|------|------|
| `projectId` | Long | 项目 id |
| `bundle` | SyncBundle | B 序列化产出的文件集 |
| `baseline` | String | 不透明修订令牌(D4),push 回传做并发校验 |
| `fileCount` | int | 文件数(完整性校验,FR-017) |

### 1.3 PushCommand(push 请求 body)
| 字段 | 类型 | 必填 | 含义 |
|------|------|------|------|
| `files` | Map<String,String> | 是 | 文件集 |
| `baseline` | String | 否 | pull 时拿到的基线令牌;缺省=不做并发校验(等价首次推) |
| `force` | boolean | 否 | true=绕过陈旧基线校验强制覆盖(默认 false) |
| `expectedFileCount` | int | 否 | 完整性核对(防截断,FR-017) |
| `remark` | String | 否 | 写入版本快照备注 |

### 1.4 PushResult(push 响应 data)
| 字段 | 类型 | 含义 |
|------|------|------|
| `projectId` | Long | |
| `created` / `updated` / `deleted` | Counts | 按实体类型(task/workflow/catalog/tag)的增改删计数 |
| `snapshots` | List<SnapshotRef> | 本次生成的版本快照引用 `{entityType, entityId, name, versionNo}` |
| `newBaseline` | String | push 后的新修订令牌(供下次 push) |

### 1.5 DiffPreview(diff 响应 data,只读)
| 字段 | 类型 | 含义 |
|------|------|------|
| `added` / `modified` / `removed` | List<EntityRef> | 按实体粒度 `{entityType, identity(name/path/slug), displayName}` |
| `stale` | boolean | 当前 baseline 是否已落后于服务器(供 UI 提示) |

---

## 2. 复用的领域聚合(经 B 的 ProjectExport/ProjectImport 装配)

C 通过 B 的两个聚合 record 与领域层往返,**不直接拼 YAML**:

- **`ProjectExport`**(12 参,pull 侧装配)— 字段见 `filecontract/ProjectExport.java`:
  `project, catalogs[], tags[], entityTags[], tasks[], workflows[], workflowNodes[], workflowEdges[], taskSlugs{id→slug}, workflowSlugs{id→slug}, taskDatasourceCodes{taskId→dsName}, taskTargetDatasourceCodes{taskId→dsName}`。
- **`ProjectImport`**(push 侧反序列化产物)— 同构 + `warnings[]`;暴露 record accessor 与 `toExport()`。

### 2.1 pull 装配规则(领域 → ProjectExport)
| ProjectExport 字段 | 来源 |
|--------------------|------|
| `project` | `ProjectRepository.findById(pid)`(校验 tenant 归属) |
| `catalogs` | `CatalogNodeRepository.findByProjectIdAndDeleted(pid,0)` |
| `tags` | `TagRepository.findByProjectIdOrderByNameAsc(pid)` |
| `entityTags` | 对每个 tag `EntityTagRepository.findByTagId(tagId)` 汇总 |
| `tasks` | `TaskDefRepository.findByProjectId(pid)`(deleted=0 过滤) |
| `workflows` | `WorkflowDefRepository.findByProjectId(pid)`(deleted=0) |
| `workflowNodes` | 对每个 wf `WorkflowNodeRepository.findByWorkflowIdAndDeleted(wfId,0)` |
| `workflowEdges` | 对每个 wf `WorkflowEdgeRepository.findByWorkflowIdAndDeleted(wfId,0)` |
| `taskSlugs` / `workflowSlugs` | 由 name/catalogPath 生成稳定 slug(复用 B 的 `SlugRules`) |
| `taskDatasourceCodes` | `task.datasourceId → Datasource.name`(经 name 映射;**只取 name,不取连接字段**,D7/FR-003) |
| `taskTargetDatasourceCodes` | `task.targetDatasourceId → Datasource.name` |

### 2.2 push 落库规则(ProjectImport → 领域,见 §3)

---

## 3. push 落库匹配算法(D2)

`FileContract.deserialize` 给的合成 id **只用于 bundle 内连边**;落库按**身份键**匹配真实行。全程在 `@Transactional` 内,校验全过后才写(D5)。

```
解析 datasource: 建 name→datasourceId 映射(D3);未知名 → 拒绝(FR-007)
对 catalogs / tasks / workflows / tags 各做三态对账:
  本地∩服务器(身份键匹配) → UPDATE(回填真实 id,覆盖字段,hasDraftChange=1)
  本地 - 服务器            → INSERT(新行,status=DRAFT)
  服务器 - 本地            → DELETE(软删 deleted=1;task 经 D6 在线引用守卫)
workflow 的 node/edge:按 workflowId 删旧 → 插新(整体重建);
  node.taskId 经 task 身份键解析;edge.from/to 经 node.nodeKey 解析
entityTags:按 (entityType, entityId) 重建该实体的标签关联
落库后:对受影响(insert/update)的 task/workflow 调 D1 建快照内核
```

**身份键**:
| 实体 | 键 |
|------|----|
| Catalog | `path` |
| Task | `(catalogPath, slug)`(slug 来自 `taskSlugs`) |
| Workflow | `slug`(来自 `workflowSlugs`) |
| Tag | `name` |

**校验(写之前,全致命即整单拒绝,FR-005/006)**:
- `ProjectImport.warnings` 非空 → 拒绝(B 已检出缺必填/悬挂引用/类型错)。
- datasource 名解析失败 → 拒绝。
- 删除候选触发在线引用守卫(D6)→ 拒绝。
- 基线陈旧且非 force(D4)→ 拒绝。
- 文件完整性(fileCount/expectedFileCount)不符 → 拒绝(FR-017)。

---

## 4. 版本快照映射(D1)

| 源(领域 TaskDef/WorkflowDef) | TaskDefVersion / WorkflowDefVersion |
|------|------|
| Task: name/type/content/datasourceId/targetDatasourceId/paramsJson/timeoutSec/retryMax/priority/description | 同名复制 + `versionNo=currentVersionNo+1` + `publishedBy=TenantContext.userId()` + `publishedAt=now` |
| Workflow: nodes(经 `buildSnapshotJson` 钉每 node 的 `taskVersionNo`)+ edges | `dagSnapshotJson` + `versionNo+1` |

push **不**改 `status`(新建=DRAFT,既有保持);**不**跑"被引用任务须 ONLINE"闸门。

---

## 5. 仓储增补(最小)

| 仓储 | 增补方法 | 用途 |
|------|----------|------|
| `EntityTagRepository` | `deleteByEntityTypeAndEntityId(String,Long)`(可选,否则用现有逐条删) | push 重建实体标签 |
| `DatasourceRepository` | `findByProjectIdAndName(Long,String)`(可选,否则内存过滤 `findByProjectId`) | name→id 解析 |

> 其余查询均已存在(见 research.md 复用面索引),不增。

---

## 6. 隔离不变量(FR-012,贯穿)

每个 pull/push/diff 入口:`pid` 对应 project 的 `tenantId` 必须 == `TenantContext.tenantId()`,否则 `BizException("project.access_denied").withHttpStatus(403/404)`。越权不泄露除"无权"外的任何定义内容(US1-3)。
