# Contract: dw run 退出码 + datasource + baseline

## 退出码

| 场景 | 退出码 |
|------|--------|
| 成功 | 0 (ExitOK) |
| 用法/参数错 | 2 (ExitUsage) |
| 认证失败 | 3 |
| 服务端错 | 4 |
| 网络错 | 5 |
| **任务执行失败**（runner 非零退出） | 6 (ExitRunFailed) —— 仅此场景 |
| **环境/前置错**（缺 JVM/worker classpath） | 7 (ExitEnvironment) —— 新增 |

- 不变量：0 成功语义不变；环境失败与任务失败码可区分（FR-016）。

## datasource 校验

- `dw run` 加载 `datasources.local.yaml` 时即校验必填字段（如 `jdbcUrl`），缺失在**运行前**报可定位错（含数据源名/字段名）（FR-017）。

## baseline 过期提示

- `dw push` 遇服务端 `project.sync.stale`(409) 时，CLI MUST 渲染可读提示：说明"基线过期"+推荐 `dw pull` 或 `dw push --force`（FR-018）。服务端检测/错误体不变。
