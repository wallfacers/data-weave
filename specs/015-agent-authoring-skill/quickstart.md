# Quickstart / 验证指南: Weft 任务创作 Skill + dev-loop

本指南给出 golden path 的**双层验证**运行方式（spec Q5）。详细字段见 [data-model.md](data-model.md) 与 [contracts/](contracts/)。

## 前置

- 后端可起（H2 profile，零外部依赖）：`cd backend && ./dev-install.sh && ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2`
- `dw` CLI 已构建：`cd cli && ./build.sh`；设 `DW_API`（默认 `http://localhost:8000`）、`DW_TOKEN`（统一 Bearer 凭据）。

---

## 层一：CI 确定性 E2E（无 LLM key，进 CI）

**目的**：钉住 golden path 管路、防回归。`agent` 步由脚本化 dw 命令链承载（遵循 Skill 同一步骤序）。

**运行**：
```bash
cd cli && go test ./...        # 含 golden path 脚本化链（复用 sync_e2e_test.go 地基）
cd backend && ./mvnw -pl dataweave-api test -Dtest='*AuthMerge*,*Cli*'   # 认证合并 H2 测试
```

**预期闭环**（脚本化）：
1. `dw pull <project>` → 本地得文件树（exit 0）
2. 写一个最小任务（仿 `.claude/skills/weft-task-authoring/examples/`）
3. `dw run <task>` → 本地真跑、输出日志（exit 0）；缺 worker classpath → exit 7（环境错，可区分）
4. `dw diff` → 显示新增（exit 0）
5. `dw push` → 落库（exit 0）；基线过期 → 可读提示（先 pull / --force）
6. `dw run --test <task>` → 服务器 TEST 提交、日志回流（exit 0）

**断言**：各步退出码符合 [dw-runtime-contract.md](contracts/dw-runtime-contract.md)；统一 Bearer 凭据对 CLI 与项目/ops 端点均通；写操作过 PolicyEngine + 审计。

---

## 层二：真 LLM 验收仪式（需 key，不进 CI）

**目的**：忠实证明"用 AI agent 从零写任务并 push 上线"。

**前置**：
- 开发者本地提供真本地 agent（workhorse + 真实 LLM 配置：key/endpoint）。
- Workhorse 配置参考 `specs/009-weft-cli-runtime/quickstart.md`（workhorse 连接后端 + LLM）。
- Weft 任务创作 Skill 位于 `.claude/skills/weft-task-authoring/SKILL.md`，随仓库 clone 即得，无需额外安装。

**步骤**：
1. 在 Weft 仓库工作副本中起本地 agent（加载项目级 Skill，agent 自动发现 `.claude/skills/`）。
2. 对 agent 说"创建一个新任务 …"（自然语言诉求，agent 应自动加载 `weft-task-authoring` Skill）。
3. 观察 golden path 完整闭环：
   - agent 自动加载 Skill（无需手工粘贴说明）
   - 据 Skill 写出结构合法的任务文件（字段齐全、引用自洽、占位符语法正确）
   - `dw run` 本地跑通（exit 0）
   - `dw diff` 显示差异
   - `dw push` 推回服务器，正确读 GateResult 三态（含删除/高危 push → 挂起待审批，不误判为失败）
   - `dw run --test` 服务器 TEST 验证通过
4. 验证任务最终在服务器侧可见（`dw task list` 或 Web UI）。

**通过判据**（对应 SC-001/SC-004）：
- agent 据 Skill 首次产出文件即结构合法（字段齐全、引用自洽、占位符语法正确）。
- 全程从干净工作副本一次跑通，**零依赖任何已删引导脚本/缺失文件**（SC-002）。
- 任务最终在服务器侧可见。

**故障排查**：
- agent 未自动加载 Skill：检查 `.claude/skills/weft-task-authoring/SKILL.md` frontmatter `description` 是否覆盖用户意图
- `dw run` 报 exit 7（环境错误）：检查 JVM 和 `DW_WORKER_CP` 是否设置
- `dw push` 返回 PENDING_APPROVAL：非失败，等待管理员在 Web UI 审批

**注意**：本仪式 **不进 CI**；无 key 环境 CI 靠层一保持绿（FR-025a）。

---

## 残留清理验证

```bash
test ! -f deploy/workhorse/serve-local.sh && echo "断脚本已删"
test ! -f openspec/specs/workhorse-supervisor/spec.md && echo "残留 spec 已删"
cd backend && ./dev-install.sh && echo "后端构建通过"
# 全仓无悬空引用（指向已删物）：
grep -rn "serve-local.sh\|workhorse-supervisor\|merge-config.py" --include='*.md' --include='*.java' --include='*.go' . | grep -v specs/015 || echo "无悬空引用"
```
