## ADDED Requirements

### Requirement: 弱依赖就绪以上游自然跑完为准,手动停止不放行

调度器认领 NORMAL/BACKFILL 候选实例时,对弱依赖边(`strength=WEAK`)的上游就绪判定 SHALL 仅在上游处于「自然跑完」终态时放行下游:`SUCCESS`(含手动置成功)或 `FAILED`。手动停止产生的 `STOPPED` 终态 MUST NOT 视为弱依赖就绪——手动停止是中止而非跑完,其弱依赖下游 MUST NOT 被认领运行(停留 `WAITING`,不自动 `SKIPPED`)。强依赖(`STRONG`)仍 MUST 仅在上游 `SUCCESS` 时放行,行为不变。

#### Scenario: 弱依赖上游成功放行下游
- **WHEN** `A ──弱──▶ C`,A 进入 `SUCCESS`(正常成功或手动置成功)
- **THEN** C 可被认领运行

#### Scenario: 弱依赖上游失败放行下游
- **WHEN** `A ──弱──▶ C`,A 进入 `FAILED`
- **THEN** C 可被认领运行(弱依赖非阻塞)

#### Scenario: 弱依赖上游手动停止不放行下游
- **WHEN** `A ──弱──▶ C`,A 被单点手动停止进入 `STOPPED`(MANUAL_STOP)
- **THEN** C MUST NOT 被认领运行,停留 `WAITING`

#### Scenario: 强依赖仍只认成功
- **WHEN** `A ──强──▶ C`,A 进入 `FAILED` 或 `STOPPED`
- **THEN** C 不被认领运行(强依赖只在 `SUCCESS` 放行)
