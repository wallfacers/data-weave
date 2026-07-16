# Feature Specification: Companion Workhorse 生产收口(容器化 + 安全收紧 + 巡检真实数据)

**Feature Branch**: `072-companion-workhorse-prod`

**Created**: 2026-07-16

**Status**: Draft

**Input**: User description: "071 companion 真 workhorse 生产收口——sidecar 容器化部署、跨域/认证收紧、巡检接平台真实数据。当前 workhorse 跑宿主进程、平台容器经 host.docker.internal 回连、allow_null_origin 通配放行、mcp 空致巡检兜底,均待生产级收口。"

## User Scenarios & Testing *(mandatory)*

### User Story 1 - 标准化容器部署 companion 全栈 (Priority: P1)

运维人员用一条标准部署命令拉起 companion 全栈(平台服务 + workhorse sidecar):workhorse 作为受管容器服务与平台同网络,无需手工在宿主启动 sidecar 进程、无需 host→容器回连的临时网络桥接。

**Why this priority**: 当前 workhorse 跑在宿主、平台容器经 `host.docker.internal` 回连,是联调临时方案——不可复制、隔离弱、暴露宿主端口。容器化是「生产可部署」的前提,优先级最高。

**Independent Test**: 用标准部署命令起全栈,workhorse 容器 healthy,平台用服务名寻址到 sidecar,companion 对话可用。

**Acceptance Scenarios**:

1. **Given** 全栈未起, **When** 运维执行标准部署命令, **Then** workhorse 作为受管服务与平台一同起、进入 healthy。
2. **Given** 全栈已起, **When** 平台调用 companion, **Then** 经服务名寻址 sidecar(非宿主回连),推理正常返回。
3. **Given** 任意宿主环境, **When** 用相同部署命令, **Then** 得到一致的网络拓扑(可复制,无环境特定回连 hack)。

---

### User Story 2 - sidecar 通信安全收紧 (Priority: P2)

sidecar 的推理端点仅对显式授权的调用方(白名单来源 + 持有共享密钥)开放,通配/匿名来源被拒;sidecar 凭据只存于后端受管配置,不出库、不下发前端。

**Why this priority**: 当前 `allow_null_origin` 通配放行 + 无来源白名单,任何能触达端点的来源可调推理;不符合 constitution IV「sidecar 凭据不出后端」与生产安全最小授权原则。

**Independent Test**: 未授权来源(无密钥 / 非白名单)调推理端点被拒;授权来源通;凭据在代码库/镜像/前端均不可读。

**Acceptance Scenarios**:

1. **Given** sidecar 已起, **When** 无共享密钥的请求调推理端点, **Then** 被拒(未授权)。
2. **Given** sidecar 已起, **When** 来源不在白名单的请求, **Then** 被拒(跨域拦截)。
3. **Given** 代码库、容器镜像、前端产物, **When** 审查 sidecar 凭据, **Then** secret 均不可读(仅运行时受管配置注入)。

---

### User Story 3 - 巡检基于平台真实数据 (Priority: P3)

companion 巡检经平台工具查询项目真实数据(任务失败 / 节点状态 / 数据质量 / 代码质量),产出有据汇报,而非因无数据通道兜底「未完成」。

**Why this priority**: 当前巡检无平台工具通道,只能兜底 INFO「未完成」,管家无法真正履职。接通真实数据是 companion 价值闭环。

**Independent Test**: 触发一轮巡检,workhorse 经平台工具查到真实数据,汇报引用真实对象(实例 / 节点 / 表)。

**Acceptance Scenarios**:

1. **Given** 项目存在异常数据, **When** 触发巡检, **Then** 汇报基于真实查询结果(非兜底),并引用真实对象。
2. **Given** 平台工具通道不通, **When** 触发巡检, **Then** 优雅兜底「未完成」(不阻塞、不臆测)。

---

### Edge Cases

- sidecar 容器启动失败(LLM 凭据无效 / 出网不通)→ 平台照常起、降级无汇报,不阻塞调度内核。
- 巡检时平台工具不可用(工具端点不通)→ 兜底 INFO「未完成」,不臆造问题。
- 非 docker 部署(裸机调试)→ 保留本地调试通道,不强制容器形态。
- 多租户:当前 sidecar 密钥绑定单一租户;多租户动态切换为已知局限(后续 feature)。

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: companion sidecar MUST 作为受管容器服务运行,与平台其他服务同网络,平台用服务名寻址(非宿主进程 + 回连桥接)。
- **FR-002**: sidecar 凭据(auth 共享密钥 / LLM 密钥 / 平台工具密钥)MUST 仅经运行时受管配置注入,不进代码库、镜像产物或前端(constitution IV ①)。
- **FR-003**: sidecar 推理端点 MUST 仅对显式白名单来源 + 持有共享密钥的调用方开放;通配来源与匿名请求 MUST 被拒。
- **FR-004**: sidecar 经平台工具发起的一切写操作 MUST 走 PolicyEngine 写闸门并留 `agent_action` 审计(constitution IV ②、V)。
- **FR-005**: sidecar 不可用时,平台调度内核与运行态观测 MUST 不受影响(降级为无汇报,不阻塞)(constitution IV ③)。
- **FR-006**: 巡检 MUST 能经平台只读工具查询项目真实数据(任务 / 节点 / 质量 / 代码四领域),产出有据汇报。
- **FR-007**: 巡检在平台工具不可用时 MUST 优雅兜底(产「未完成」汇报),不臆测、不阻塞。
- **FR-008**: 非 docker 的本地裸机调试通道 MUST 保留,不强制容器形态。

### Key Entities *(include if feature involves data)*

- **companion sidecar(workhorse)**: 外部 agent 进程,承载巡检 / 对话推理;服务端零推理(constitution IV 不可让渡内核)。
- **平台 MCP 工具(dataweave__\*)**: sidecar 经 MCP 调用的平台能力(只读查询为主,写动作经闸门)。
- **sidecar 凭据**: auth 共享密钥、LLM provider 密钥、平台工具密钥;仅存后端受管配置。

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 运维用单条标准部署命令起 companion 全栈(含 sidecar 受管服务),无需手工启宿主进程或配置回连桥接。
- **SC-002**: 代码库、容器镜像、前端产物中均无可读 sidecar 凭据(仅运行时注入,可审计验证)。
- **SC-003**: 未授权调用方(无密钥 / 非白名单来源)调推理端点 100% 被拒。
- **SC-004**: 项目存在异常时,巡检汇报引用真实数据对象(非「未完成」兜底)。
- **SC-005**: sidecar 停止时,平台任务调度与运行态观测面板照常(无阻塞、无降级误报)。

## Assumptions

- 单租户(tenant=1)本地 / 默认;多租户的 sidecar 密钥动态切换为已知局限,后续 feature 处理。
- workhorse 预编译二进制在构建上下文可用;纯净 CI 环境需先获取二进制。
- LLM provider 为 DeepSeek(经 Anthropic 兼容端点,已于 071 确定)。
- 平台 MCP 端点的共享密钥本地用默认值,生产部署覆盖受管配置。
- constitution IV「运行态 sidecar 例外面」已批准(1.3.0),本 feature 在其授权范围内细化生产形态,不改变不可让渡内核。
