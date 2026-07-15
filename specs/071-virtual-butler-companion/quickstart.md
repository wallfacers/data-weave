# Quickstart 验证指南: 虚拟管家监督席

按用户故事切片验证;契约细节见 [contracts/companion-api.md](contracts/companion-api.md),表结构见 [data-model.md](data-model.md)。

## 前置

```bash
# 后端(PG + Redis;或 -Dspring-boot.run.profiles=h2 免 Docker)
cd backend && docker compose up -d && ./dev-install.sh
./mvnw -pl dataweave-api spring-boot:run          # :8000

# workhorse sidecar(可选——不起则验证 MockBrain 降级路径)
cd ~/workspace/github/workhorse/workhorse-agent && ./workhorse-agent serve --port 8300

# 前端
cd frontend && pnpm install && pnpm dev            # :4000
```

## US1 — 视图与状态(P1)

1. 登录后导航打开「虚拟管家」→ 3 秒内出现机器人形象(呼吸/眨眼/注视跟随)+ 顶部概况(SC-001)。
2. 制造异常:`POST /api/companion/routines/{任务失败}/trigger`(或直接插一条 DANGER `patrol_report` 种子)→ SSE 在线 5 秒内形象转警觉、异常计数 +1(SC-002)。
3. 关闭全部异常汇报 → 形象回落待机、计数归零。
4. **主题**(FR-021):切换亮/暗主题 → 3D 场景即时换色不刷新;两种主题下截图核对状态色/文案可辨。
5. 无权限账号打开 → 权限不足提示,无数据渲染。
6. 降级:浏览器禁用 WebGL(或强制探测失败)→ 2D 能量球形象,卡片/概况功能不受影响。

## US2 — 巡检与汇报(P2)

1. `GET /api/companion/routines` → 四领域 seed 齐全、默认启用。
2. 手动触发一轮 → `patrol_run` 落 CLAIMED→RUNNING→终态,产出 `patrol_report`(即使"一切正常"也有 OK 汇报)。
3. 视图侧:新卡片按严重度置顶、管家播报字幕、未读徽标 +1。
4. A 用户关闭卡片 → B 用户(另开浏览器/标签)实时消失(项目级共享)。
5. 停掉 workhorse 再触发 → 产出 INFO"未完成"汇报,不静默(SC-007)。
6. 离线补看:关闭视图期间触发两轮 → 重开视图 snapshot 完整补齐。

## US3 — 对话与派活(P3)

1. 底部输入框发查询指令 → 形象转思考→播报,流式富文本回复,首片段 <5s(SC-005)。
2. 卡片内展开对话追问 → 回复内容锚定该汇报上下文(比对全局会话回答同一问题的差异)。
3. 流式中点停止 → 1 秒内中止,`end{interrupted:true}`,会话可继续。
4. 发写操作指令(如"重跑失败任务")→ 回复明示进入审批;核对 `agent_action` 有记录、闸门放行前无实际变更(SC-006);按 outcome 分流(默认 L2 → PENDING_APPROVAL,勿只看 code===0)。
5. 中文 IME 组字中 Enter 不误发;停 workhorse 后发消息 → 本地化降级提示(非空白)。

## US4 — 例程治理(P4)

1. `PATCH` 停用「代码质量」→ 下一周期不产出该领域汇报,其余领域正常。
2. 改 cron → 概况里"下轮巡检时间"随之变化。
3. `GET .../runs` → 每轮触发时间/耗时/结论/汇报关联可见。
4. 双 master(distributed profile)下同一 fire time 只执行一次(UNIQUE 幂等防重)。

## 回归门(每个 US 收口必跑)

- `cd backend && ./mvnw -q -pl dataweave-master,dataweave-api compile` + 相关 `*Test`/`*IT`(H2 与 PG 各验一遍 DDL)。
- `cd frontend && pnpm typecheck && pnpm design:lint`;i18n 双 bundle key 齐全。
- 浏览器门:playwright 实跑上述场景截图(SSE 场景必须真浏览器,curl 骗不出缓冲/断线行为)。
- 调度路径改动(PatrolScheduler)须在真实并发下发下验证:双 master 起两实例观察 `patrol_run` 无重复、无 straggler。
```
