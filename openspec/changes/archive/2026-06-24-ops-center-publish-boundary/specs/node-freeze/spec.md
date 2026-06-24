## ADDED Requirements

### Requirement: 节点级 DAG 冻结 overlay

系统 SHALL 支持以任务流 DAG 的**节点**为粒度冻结/解冻：`POST /api/ops/workflows/{workflowId}/nodes/{nodeKey}/freeze`，body `{ frozen: boolean }`。冻结状态 MUST 存为**运维侧 overlay**（独立表，键 `(workflow_id, node_key)`），MUST NOT 写入发布快照 `dag_snapshot_json`——快照保持发布即不可变。冻结仅表示「调度物化时跳过该节点」，对已生成的在途实例不追溯改动（除非 overlay 作用域为实例级，见下）。所有写操作 MUST 经 `GatedActionService` 闸门并留痕。

#### Scenario: 冻结一个节点
- **WHEN** 运维对工作流 W 的节点 `node_key=N` 调用 freeze `{ frozen: true }`
- **THEN** 系统在 overlay 表写入 `(W, N, frozen=true)`，返回带 outcome 的结果，`dag_snapshot_json` 不被修改

#### Scenario: 解冻恢复调度
- **WHEN** 运维对已冻结节点调用 freeze `{ frozen: false }`
- **THEN** overlay 标记清除/置 false，后续物化恢复包含该节点

#### Scenario: 冻结状态不污染发布快照
- **WHEN** 一个被冻结节点所在工作流重新发布
- **THEN** 新 `dag_snapshot_json` 不含任何冻结标记，冻结仅存在于 overlay；发布前后 overlay 不变

### Requirement: 节点冻结的级联跳过语义

调度器在从快照 DAG 物化运行实例时 SHALL 叠加节点冻结 overlay。冻结节点 N MUST 被置为 `SKIPPED` 不执行；N 的**传递下游**（沿出边可达的后继闭包，**含弱依赖边**）MUST 一并 `SKIPPED`；不在 N 下游的节点（上游、旁支、不相干分支）MUST 照常执行。叶子节点被冻结时其无下游，故仅自身 `SKIPPED`；作为全图根的锚点（含 `VIRTUAL`）被冻结时其下游全闭包 `SKIPPED`，等价整条任务流不产出。

冻结优先级 MUST 高于依赖强弱：冻结的级联跳过 MUST 穿透弱依赖边——即便下游对冻结节点是弱依赖，下游仍 `SKIPPED` 不得运行。（区别于常态调度中弱依赖"上游跑完即放行下游"的语义；冻结是运维强制止血，下游一律不跑。）

#### Scenario: 冻结叶子节点只跳自身
- **WHEN** 运维冻结一个无下游的叶子节点 C 后该工作流被触发
- **THEN** 实例中 C 为 `SKIPPED`，其余节点正常执行

#### Scenario: 冻结中间节点级联跳下游
- **WHEN** 工作流为 `A→C`（强依赖），运维冻结 A 后触发
- **THEN** 实例中 A 与 C 均 `SKIPPED`，与 A 无依赖关系的节点照常执行

#### Scenario: 冻结根锚点整条不产出
- **WHEN** 一个 `VIRTUAL` 根锚点是全图唯一上游，运维冻结它后触发
- **THEN** 该锚点及其全部传递下游 `SKIPPED`，本次实例无有效产出

#### Scenario: 旁支不受影响
- **WHEN** DAG 为 `V→A→C` 与 `V→B→D` 两支，运维冻结 A 后触发
- **THEN** A、C `SKIPPED`，B、D 正常执行（V 非冻结、B 支不在 A 下游）

#### Scenario: 冻结级联穿透弱依赖边
- **WHEN** 工作流为 `A ──弱依赖──▶ C`，运维冻结 A 后触发
- **THEN** A 与 C 均 `SKIPPED`（冻结优先于弱依赖，下游不得运行）

### Requirement: 冻结作用域含定义级与实例级

节点冻结 overlay SHALL 同时支持定义级与实例级两种作用域。overlay 表键 MUST 含可空 `instance_id`：`instance_id IS NULL` 表示**定义级**（冻在周期任务流定义上，此后每个 cron 物化实例都跳该节点，直至解冻）；`instance_id` 非空表示**实例级**（仅对该条已生成实例的该节点生效）。物化某实例时 MUST 同时叠加「该工作流的定义级冻结」与「该实例的实例级冻结」，二者并集决定 `SKIPPED` 节点。前端入口:周期任务流列表偏定义级,DAG 实例视图点节点偏实例级。

#### Scenario: 定义级冻结影响后续每个实例
- **WHEN** 运维对工作流 W 的节点 N 做定义级冻结（`instance_id` 空），随后 W 被 cron 连续触发两次
- **THEN** 两次实例中 N 均 `SKIPPED`

#### Scenario: 实例级冻结只影响单次
- **WHEN** 运维对工作流 W 某实例 I 的节点 N 做实例级冻结（`instance_id=I`），下一次 cron 触发产生实例 J
- **THEN** I 中 N `SKIPPED`，J 中 N 正常执行
