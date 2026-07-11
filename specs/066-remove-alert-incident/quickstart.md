# Quickstart: 验证命令

**Feature**: 066-remove-alert-incident | **Date**: 2026-07-12

## 1. 后端编译（全模块）

```bash
cd backend && ./dev-install.sh
```

## 2. 后端测试（真跑，防 build-cache 假绿）

```bash
cd backend
setsid bash -c './mvnw clean -Dmaven.build.cache.enabled=false \
  -pl dataweave-master,dataweave-api,dataweave-worker -am test >build.log 2>&1; echo $? >build.exit' \
  </dev/null >/dev/null 2>&1 & disown
# 轮询（单次秒回）：
[ -f build.exit ] && echo "DONE exit=$(cat build.exit)" || { echo running; tail -1 build.log; }
```

- 认 `Tests run: N>0`，不信 `BUILD SUCCESS` 空话。
- WSL2 长命令必须 setsid 脱离（CLAUDE.md 硬规则）。

## 3. 前端

```bash
cd frontend && pnpm typecheck && pnpm test
```

## 4. 调度并发核验（因动 InstanceStateMachine）

按 CLAUDE.md 调度硬规则，跑 every-minute cron 端到端，确认：
- `started_at − created_at ≈ 0`（root 节点）
- 根节点 `attempt = 1`
- **零**「跳过下发」/「中止执行」stragglers
- `task_instance` 无 attempt-count 与延迟异常相关

## 5. schema 启动验证

```bash
# H2（零外部依赖）
cd backend && ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2
# health: GET /api/health

# PostgreSQL
docker compose up -d && ./mvnw -pl dataweave-api spring-boot:run
```

## 6. grep 零命中验证（删除完整性）

```bash
# 后端无 alert/quality/AlertSignal 残留
grep -rniE "AlertSignal|com\.dataweave\.alert|com\.dataweave\.master\.quality|com\.dataweave\.api\.quality" \
  backend --include='*.java' | grep -v /target/   # 应零

# schema 无 alert_* 表
grep -niE "alert_rule|alert_channel|alert_event|alert_notification|alert_silence|alert_route|alert_poll_fire" \
  backend/dataweave-api/src/main/resources/schema.sql   # 应零

# data.sql 无 ALERT_*/QUALITY_* 策略
grep -niE "ALERT_RULE_WRITE|ALERT_TEST_SEND|QUALITY_RULE_WRITE|QUALITY_RUN" \
  backend/dataweave-api/src/main/resources/data.sql   # 应零

# 前端无 alerts/quality 残留
grep -rniE "alerts-view|AlertsView|alert-api|quality-view" frontend | grep -v node_modules   # 应零

# i18n parity
diff <(jq -r 'paths|join(".")' frontend/messages/zh-CN.json|sort) \
     <(jq -r 'paths|join(".")' frontend/messages/en-US.json|sort)   # 应空
```
