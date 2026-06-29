# Quickstart：验证 017 SQL 重梳理与 Schema 版本

**Feature**: 017-sql-schema-versioning

前置：实现已在 `017-sql-schema-versioning` 分支/worktree 完成（勿在 main 实现）。WSL2 下编译/测试**必须 `setsid` 脱离**（CLAUDE.md 硬规则）。

---

## 1. 编译（api 模块）

```bash
cd backend
setsid bash -c './mvnw -q -pl dataweave-api -am compile >/tmp/claude-1000/-home-wallfacers-project-data-weave/build.log 2>&1; echo $? >/tmp/claude-1000/-home-wallfacers-project-data-weave/build.exit' </dev/null >/dev/null 2>&1 & disown
# 轮询（单次秒回，勿前台 sleep 循环）：
[ -f /tmp/.../build.exit ] && echo "DONE exit=$(cat /tmp/.../build.exit)" || { echo running; tail -1 /tmp/.../build.log; }
```

## 2. 跑契约测试（H2，CI 默认）

```bash
cd backend
setsid bash -c './mvnw -pl dataweave-api test -Dtest=SchemaVersionIT >/tmp/.../t.log 2>&1; echo $? >/tmp/.../t.exit' </dev/null >/dev/null 2>&1 & disown
```
预期：C1–C6 全绿（版本单行 + SemVer + `0.0.1` + 无 `db/migration` + 无 `task_diagnosis/finding`）。
随后跑**完整 api 套件**确认无回归 / 无 masked-red：`./mvnw -pl dataweave-api test`（同样 setsid 脱离）。

## 3. PostgreSQL 真库验证（双库兼容）

```bash
cd backend
docker compose up -d                                  # PG :5432
./dev-install.sh
setsid bash -c './mvnw -pl dataweave-api spring-boot:run >/tmp/.../run.log 2>&1 &'   # 默认 PG
# 起来后：
curl -s localhost:8000/api/health                     # 健康
# 直连库查版本：
docker exec -i <pg> psql -U dataweave -d dataweave -c "SELECT * FROM schema_version;"
# 预期：恰好 1 行，version=0.0.1
```

## 4. 人工对账（漂移 / 清理）

- [ ] `backend/dataweave-api/src/main/resources/db/migration/` 目录**已不存在**。
- [ ] `schema.sql` 头部有 `Schema Version: 0.0.1` 声明，且真相源注释不再指向 `openspec/.../design-data-model.md`。
- [ ] `grep -n 'schema_version' schema.sql` → 有 `CREATE TABLE` + 单行 `INSERT '0.0.1'`。
- [ ] `grep -rni 'task_diagnosis\|finding' resources/*.sql` → 无（demo-data 已清或整删）。
- [ ] demo profile 启动不报缺表：`./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=demo`（若 demo 保留）。
- [ ] 库内 `schema_version.version` == schema.sql 头部声明 == 项目版本 `0.0.1`（三者一致）。

## 5. 合并前跨特性复核

- [ ] `git diff main <016-spark-runtime-parity> -- backend/dataweave-api/src/main/resources/` → 若 016 动过 schema.sql/data.sql，先合 016、把其结构改动并入权威 schema，再合 017，重跑 api 套件确认缝合（防「编译通过但 sibling 落地即破」）。

全部通过 = SC-001~SC-008 满足，特性闭环。
