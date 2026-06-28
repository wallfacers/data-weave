# Quickstart: Weft 子特性 D —— CLI + 本地 runtime

**前置**:`dw` 已构建(`cd cli && ./build.sh`);`DW_API`(默认 `:8000`)、`DW_TOKEN` 已配;本机有 JVM(本期 `dw run` 依赖)+ python3(跑 PYTHON 任务)。

## 一次完整闭环

```bash
# 1. 拉取项目为本地文件树(目录即类目树)
export DW_API=http://localhost:8000
export DW_TOKEN=<your-token>
mkdir demo && cd demo
dw pull demo                      # 写文件树 + .weft/state.json(baseline)

# 2. 配本地数据源(凭据本地,git-ignored)
cat > .weft/datasources.local.yaml <<'YAML'
warehouse_pg:
  typeCode: POSTGRESQL
  jdbcUrl: jdbc:postgresql://localhost:5432/warehouse
  username: dev
  password: devpass
YAML

# 3. 本机真跑调试(不碰服务器)
dw run etl/daily/build_dim.task.yaml     # 路径定位
dw run build_dim                          # 或任务名别名
echo $?                                    # 退出码忠实反映(失败非0)

# 4. 改定义后预览差异(只读)
dw diff                                    # added/modified/removed

# 5. 推回服务器(幂等覆盖+生成快照)
dw push                                    # 基线匹配则覆盖;过期需先 pull 或 --force
# 删除了被 ONLINE 工作流引用的任务 → 整单被拒(C 删除守卫),本地文件不变

# 6. 贴近生产验证:TEST 模式提交服务器,日志流回本地
dw run --test build_dim                    # gated TEST_RUN,实例日志直出终端
```

## 验证要点(对齐 SC)

| 检查 | 期望 | SC |
|---|---|---|
| pull→改→push→再 pull 到干净目录 | 两次文件树语义等价 | SC-001 |
| 同脚本 `dw run` vs 服务器执行器退出码 | 100% 一致 | SC-002 |
| 越权 pull/push/run --test | 非0 退出、无服务器副作用 | SC-003 |
| 失败任务 `dw run` | 非0 退出,不误报成功 | SC-004 |
| `dw diff` | 服务器零写入 | SC-005 |
| 不启 worker/master 调度 `dw run` | 本机真跑成功(需 JVM) | SC-006 |

## 测试落点

- **Go**:`cli/sync/*_test.go`(pull/push/diff 文件树 I/O、baseline、目标非空策略)、`cli/run/*_test.go`(任务定位、数据源解析、退出码透传、TEST 流消费)。
- **Java**:`PythonTaskExecutorTest`、`LocalRunMainParityTest`(同脚本 runner vs 服务器执行器退出码/输出/超时**逐项相等** = SC-002 黄金对照)。
- **禁**:`_skipped`/注释 `@Test`;后端真跑用 `-Dmaven.build.cache.enabled=false`。
