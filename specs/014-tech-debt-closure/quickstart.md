# Quickstart：014 验收复跑

四项收口的硬验收。证伪式核验——禁信"全绿"自述，真跑真看真数。

## US1 + US2 前端验收

```bash
cd frontend && pnpm install

# 1. 类型检查零错（US1 悬空引用清干净 + US2 文案键可静态解析）
pnpm typecheck            # 期望：0 error

# 2. i18n 键平价（两 bundle 同键集）—— 跑既有校验
pnpm lint                 # 含 i18n 键平价 CI 检查；期望通过
#   或直接比对两 bundle 键集相等（zh-CN.json vs en-US.json）

# 3. 全仓确认举手台符号已清零（US1）
grep -rn "useOpsAlertsStore\|ops-alerts-store\|ops-alert-card\|__MOCK_OPS_ALERT__\|useMockAlertInjector" frontend/ \
  | grep -v "specs/" ; echo "期望：无输出（除 specs 文档外零引用）"

# 4. 全仓确认 ops 区无残留硬编码中文 UI 串（US2，须含非正文属性）
#   人工 sweep components/workspace/views/ops/ + ops-view.tsx，排除注释行
```

### 浏览器验证（中英 locale 各一遍）

```bash
pnpm dev   # http://localhost:4000
```

1. admin/admin 登录拿 JWT，注入 `localStorage['dw.auth.token']`；深链 `/?open=ops`。
2. **举手台消失（US1）**：ops 视图右栏不再有"Agent 举手台"告警 rail；顶条今日大盘 + 主舞台 Tab（周期/手动任务流列表、实例、补数据）照常，布局完整无空栏/错位。
3. **批量文案正确（US2）**：进任务流实例面板，多选实例触发批量动作区——
   - 中文 locale：`批量重跑 / 批量置成功 / 批量停止`、`最多选中 100 个实例`、`最多选中 100 个实例（当前 N 个）` 显示原中文。
   - 英文 locale（切 UI 语言）：上述全部显示对应英文、**无中文残留**；动态数量 N 正确嵌入、语序自然。

## US3 后端验收

```bash
# 删目录前后构建均通过
ls backend/dataweave-alert 2>/dev/null && echo "未删" || echo "已删（期望）"
grep -c "dataweave-alert" backend/pom.xml ; echo "期望：0（根 modules 不含 alert）"

cd backend && ./dev-install.sh    # 期望：BUILD SUCCESS，master/worker/api 全编译安装
```

> WSL2 长命令硬规则：若 dev-install 偏慢，按 CLAUDE.md `setsid` 脱离 + 单次秒回轮询，禁前台 sleep 循环。

## US4 文档验收

- `docs/architecture.md` 血缘小节可读到"push 路径当前不建表级血缘 = 有意延迟 + 发布期 neo4j 图库重做"的明确记载。
- `CLAUDE.md` 血缘导航行带有指向该已知缺口的注记。
- 无任何运行期代码 diff（US4 纯文档）。

## 整体收口判据

- pnpm typecheck 0 error；i18n 键平价通过；举手台符号全仓零引用。
- 浏览器中英两 locale：举手台消失 + 批量文案各语言正确。
- `backend/dataweave-alert/` 不存在；`./dev-install.sh` 一次构建通过；根 pom modules 不变。
- 架构文档 + CLAUDE.md 血缘缺口记载到位；US4 零代码改动。
- 全程零 DB schema / 零同步 API 契约 / 零调度·权限内核改动。
