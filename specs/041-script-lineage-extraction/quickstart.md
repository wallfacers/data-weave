# Quickstart: 041 脚本任务血缘解析 — 手工验证

## 启动

```bash
cd backend && docker compose up -d && ./dev-install.sh
./mvnw -pl dataweave-api spring-boot:run          # 需 neo4j（血缘）；纯 h2 验证抽取单测即可
cd frontend && pnpm dev                            # http://localhost:4000
```

## P1：内嵌 SQL（确定边）

1. `dw pull` 项目后新建 Python 任务，content：
   ```python
   sql = "INSERT INTO dw.orders SELECT id, amount FROM ods.orders"
   spark.sql(sql)
   ```
   > 注意：字面量直接内联（`spark.sql("INSERT …")`）与变量中转均须可抽取。
2. `dw push` → 后端日志应现脚本血缘抽取记录，push 不因任何抽取失败变红。
3. 前端 `/?open=lineage` → 选 `dw.orders`：可见 `ods.orders → dw.orders` **实线**边（confidence=CONFIRMED，source=SCRIPT_SQL），字段 id/amount 列级边在 COLUMN 粒度可见。
4. Shell 任务同验：content 含 `hive -e "INSERT INTO t2 SELECT a FROM t1"`。

## P2：程序接口推断（虚线边）+ 未解析提示

1. Python 任务 content：
   ```python
   df = spark.read.table("ods.users")
   df.write.saveAsTable("dw.users_clean")
   tbl = f"dw.tmp_{date}"
   other.to_sql(tbl, conn)          # 动态表名 → 不出边，出提示
   ```
2. push 后图谱：`ods.users → dw.users_clean` **虚线**边（UNVERIFIED/SCRIPT_INFERRED）；点击该边 → 详情面板显示来源"推断"+ 未解析提示 `DYNAMIC_TABLE L4`。
3. `curl -H "Authorization: Bearer $T" -H "X-Project-Id: 1" localhost:8000/api/lineage/tasks/{id}/hints` 应返回该提示。

## P3：人工修正闭环

1. 以 ADMIN 登录（admin/admin），边详情面板点**剔除** → 边消失；重新 `dw push` 同任务 → 边**不复现**（抑制重放）。
2. 点**撤销**（corrections 列表内）→ 再 push → 边恢复虚线。
3. 点**确认** → 边变实线且 humanState=CONFIRMED。
4. 以 VIEWER 角色登录 → 面板按钮不可见（只读）。
5. 每次修正在 `agent_action` 表留痕：`SELECT action_type, approval_status FROM agent_action ORDER BY id DESC LIMIT 3;`

## P4：小模型通道（训练→评估→接入）

```bash
cd ml/lineage-extractor && pip install -r requirements.txt
python data/synth_pipeline.py --out data/out/         # 合成 ~10k 训练 + ≥200 heldout（形态隔离）
python train/sft_qlora.py --data data/out/train.jsonl # 12G GPU，QLoRA，固定 seed
python eval/evaluate.py --data data/out/heldout.jsonl # 退出码 0 = 过 SC-006 门槛
python publish.py                                     # 发布 HF 私有 repo（模型+数据集+评估卡）
uvicorn serve.app:app --port 8500                     # 推理 sidecar
```

1. backend 配置 `lineage.model.endpoint=http://localhost:8500` 重启。
2. push 一个规则不覆盖的写表脚本（如自定义封装 `write_to_warehouse(df, "dw.model_only_tbl")` 模板之外形态）→ 图谱现虚线边，边详情来源=「模型推断」+ modelVersion。
3. **降级验证**：停掉 sidecar → 重 push → push 成功、该边回落为规则/SQL 通道结果，日志留痕（SC-007）。
4. 幻觉防线：构造脚本文本中不存在表名的模型输出（mock）→ 校验拒收不入图。

## 回归红线

- SQL 类型任务血缘行为零变化（既有 lineage 测试全绿）。
- push 一个语法错误的 Python 脚本 → push 成功，hint 表现 `PARSE_FAIL` 行。
- `schema_version` 三处一致 = 0.7.0（DB 行 / schema.sql 头 / 项目版本）。

## 测试命令

```bash
# 后端（WSL2 必须 setsid 脱离，见 CLAUDE.md 硬规则）
setsid bash -c 'cd backend && ./mvnw -pl dataweave-master,dataweave-api test -Dmaven.build.cache.enabled=false >build.log 2>&1; echo $? >build.exit' </dev/null >/dev/null 2>&1 & disown
# 前端
cd frontend && pnpm typecheck && pnpm test
```
