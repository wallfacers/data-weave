# Quickstart: 013 验收闭环复跑

本特性的硬验收 = 复跑发现缺陷时的同一条 E2E 闭环，确认从"红"翻"绿"。

## 前置

```bash
# 后端（H2，免 docker）
cd backend && ./dev-install.sh && setsid bash -c \
  './mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2 >/tmp/be.log 2>&1' </dev/null >/dev/null 2>&1 & disown
# CLI
cd cli && ./build.sh
export DW_API=http://localhost:8000
export DW_TOKEN=$(curl -s -XPOST $DW_API/api/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | sed -E 's/.*"token":"([^"]+)".*/\1/')
```

## 验收步骤与期望（对齐 SC）

```bash
# 1. 按 code 拉取（US4/SC-006：此前永远失败）
mkdir -p /tmp/j/demo && cd /tmp/j/demo && dw pull demo
#   期望：成功；输出含项目 code

# 2. 拉取无损（US1/SC-001）：服务端 20 任务 = 本地 20 个 .task.yaml
ls *.task.yaml | wc -l            # 期望 20（修复前 16）
curl -s -H "Authorization: Bearer $DW_TOKEN" "$DW_API/api/tasks?size=200" \
  | python3 -c "import sys,json;print(len(json.load(sys.stdin)['data']['items']))"   # 期望 20
#   期望：两数相等；6 个中文 ECHO 节点（抽取-/清洗-/质检-/指标-/加载-/归档-）各有独立文件，无 `-.task.yaml` 坍缩

# 3. 差异忠实不失明（US2/SC-003）
dw diff                            # 期望：无差异（且步骤2已证未丢实体 → 是真一致，非失明）
rm <某中文任务>.task.yaml && dw diff   # 期望：恰好该任务列为"删除"，其余不误列
#   （验证后还原文件）

# 4. 往返身份稳定（US3/SC-002）：无改动 push 后再 pull 到干净目录
dw push                            # 期望：成功；不因碰撞误触删除守卫
mkdir -p /tmp/j2/demo && cd /tmp/j2/demo && dw pull demo
diff -rq /tmp/j/demo /tmp/j2/demo --exclude=.weft   # 期望：无差异（两次文件树语义等价）
```

## 后端测试锚点（必证伪，禁缓存掩盖）

```bash
cd backend
./mvnw -pl dataweave-master test -Dmaven.build.cache.enabled=false \
  -Dtest=SlugRulesTest,ProjectSyncRoundtripTest
# 读 surefire-reports 真数；确认 Skipped:0、无弱化断言
```

- `SlugRulesTest`：退化（纯中文/纯标点/`-`/`--`）、撞名消歧确定性、大小写碰撞、可移植字符集。
- `ProjectSyncRoundtripTest`（H2，中文碰撞夹具）：pull 实体数守恒、diff 零改动无差异且未丢、删一个恰报一个、pull→push→pull 身份稳定不误删。

## CLI 回归

```bash
cd cli && go test ./sync/    # 期望 ok（含 resolve-by-code 真契约 items）
```

## 收尾

```bash
pkill -f spring-boot:run     # 停 H2 后端
```
