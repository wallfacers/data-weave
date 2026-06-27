# Quickstart — Weft 子特性 C(pull/push API)

验证 pull→edit→push→pull 闭环 + 隔离 + 校验 + 并发。后端 h2 profile 无需 Docker。

## 启动
```bash
cd backend && ./dev-install.sh
./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2   # :8000
```
取 JWT:`POST /api/auth/login`(admin/admin),后续请求带 `Authorization: Bearer <token>`。

## 1. Pull
```bash
curl -s -X POST :8000/api/projects/1/pull -H "Authorization: Bearer $TOK" | jq .data
# 期望:data.bundle.files 含 project.yaml/tags.yaml + 各 *.task.yaml/*.sql/*.flow.yaml
#       data.baseline 非空;文件里 datasource 仅逻辑名,无 host/password
```

## 2. 本地改一个任务(改超时)后 Push
```bash
# 取 files,改某 task.yaml 的 timeoutSec,带 baseline 回推
curl -s -X POST :8000/api/projects/1/push -H "Authorization: Bearer $TOK" \
  -H 'Content-Type: application/json' \
  -d '{"files": {...改后...}, "baseline": "<pull 拿到的>", "expectedFileCount": N, "remark": "改超时"}' | jq .data
# 期望:updated.task>=1;snapshots 含该 task 新 versionNo;newBaseline 变化
#       该 task status 仍非 ONLINE(push 不自动上线,Q1)
```

## 3. 再 Pull 到干净比对(round-trip)
```bash
curl -s -X POST :8000/api/projects/1/pull -H "Authorization: Bearer $TOK" | jq -S .data.bundle.files > after.json
# 期望:改动忠实保留;未改部分与 push 前语义等价(B 的 R3 字节稳定背书)
```

## 4. 负路径(校验/隔离/并发/删除)
| 验证 | 操作 | 期望 |
|------|------|------|
| 无效定义全有或全无 | push 一个缺 `name` 的 task.yaml | `project.sync.invalid` 可定位;服务器侧定义不变 |
| 未知数据源 | task 写 `datasource: 不存在` | `project.sync.unknown_datasource` 指明 task+名,不落库 |
| 越权 | 用 tenant A 的 token push tenant B 的 project | `project.access_denied` |
| 陈旧并发 | A pull → B push 改 → A 用旧 baseline 非 force push | `project.sync.stale`;加 `force:true` 后通过 |
| 删除守卫 | 删一个被 ONLINE workflow 引用的 task 后 push | `project.sync.delete_referenced` 整单拒 |
| diff 只读 | push 前调 /diff | 返回增改删清单;服务器侧 0 变化 |

## 5. 自动化测试(权威验收)
```bash
cd backend
./mvnw -pl dataweave-master test -Dtest='ProjectSyncServiceTest'
./mvnw -pl dataweave-api    test -Dtest='ProjectSyncControllerTest'
# 注:maven-build-cache 会短路;改了源码才真跑。-Dtest 用显式类名(包通配不灵)
```

## 验收映射
SC-001 round-trip(步 3)· SC-002 越权(步 4)· SC-003 全有或全无(步 4)· SC-004 每 push 生成快照(步 2)· SC-005 零凭据(步 1)· SC-006 diff 只读(步 4)· SC-007 陈旧拒绝(步 4)。
