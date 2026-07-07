# Quickstart: 血缘目录接地（Catalog Grounding）

最短路径在 H2 上跑通 grounding 三态 + 系统排除 + 审计落库，无需 Docker。

## 前置

```bash
cd backend
./mvnw -q -pl dataweave-master compile   # 改动后编译门（零错误再继续）
```

## 单测跑通（H2 in-memory，DDL 兼容）

```bash
# 三态探针：真表 PRESENT / 不存在 ABSENT / 连不上 UNKNOWN
setsid bash -c 'cd backend && ./mvnw -pl dataweave-master -Dtest=TableExistenceProbeTest test \
  -Dspring-boot.run.profiles=h2 >/tmp/claude-1000/-home-wallfacers-project-data-weave/*/scratchpad/probe.log 2>&1; \
  echo $? >probe.exit' </dev/null >/dev/null 2>&1 & disown
# 轮询：[ -f probe.exit ] && echo "DONE exit=$(cat probe.exit)" || { echo running; }
```

关键断言（证 US1/US2 核心）：
- `dw.orders`（H2 建表）→ `probeTable` = **PRESENT** → ADOPTED、confidence 升 CONFIRMED。
- `tmp_stage` / `ghost_tbl`（未建，推断类来源）→ **ABSENT** → DROPPED，`lineage_grounding_disposition` 各落一行 `verdict=ABSENT, disposition=DROPPED`。
- 坏 jdbcUrl / 不可达端口 → **UNKNOWN** → RETAINED（原样保留，边集不变）。
- `information_schema.columns` → **SYSTEM_EXCLUDED** → EXCLUDED（推断类）。
- 同一批候选来自 `SQL_PARSED`（确定性）且 ABSENT → **RETAINED**（不剔，FR-011）。

## 集成验证（enricher 异步阶段）

`GroundingEnricherIntegrationIT`（`@SpringBootTest` + H2）：
1. 建绑定数据源的任务，脚本抽取候选含 真表 + CTE + 幻觉。
2. push → `LineageEnrichmentTrigger.request` 发事件 → 异步 grounding。
3. 断言：短时后血缘图谱 `dw.orders` 边 confidence=CONFIRMED、`tmp_stage`/`ghost_tbl` 边不在；审计表有对应 DROPPED 行。
4. **AI-off 仍接地**：即使 `lineage_agent_config.enabled=0`，grounding 仍执行（kill-switch 默认 on）；解绑数据源则全 RETAINED、边集与无 grounding 一致。

## US3 带目录精度夹具

`GroundedPrecisionFixtureTest`：H2 建已知全表集合（真表 N 张），喂混入 CTE/临时/幻觉/系统表的候选，对比 grounding on/off 的 precision：
- off：precision = 含全部 FP 的基线。
- on：FP（ABSENT+系统）100% 剔除、真表 100% 保留 → precision 显著升、recall 不降。
- 同种子两次运行结论一致（SC-007 可复现）；报告结论段声明"提升依赖目录可达、不迁移到无目录 gold-A"。

## Schema 变更自检

```bash
# 确认版本 bump 与三处一致（DB 行 / 文件头 / 项目版本）
grep -n "0.12.0\|lineage_grounding_disposition" backend/dataweave-api/src/main/resources/schema.sql
```

## 门禁

- 后端改动后：`./mvnw -q -pl dataweave-master compile` 零错误。
- 新特性必须有测试；grounding 三态 + 系统排除 + 审计落库 + AI-off 接地 + 精度夹具 全绿方为完成。
- 长命令走 setsid 脱离 + 单次秒回轮询（WSL2 硬规则）。
