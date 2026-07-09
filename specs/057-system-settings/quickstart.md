# Quickstart: 全局系统设置——AI Agent 配置统收

**Feature**: 057-system-settings

## 构建

后端（mvnd 快速本地构建，install 到 `~/.m2` 供 `spring-boot:run` 拾取新类）：
```bash
cd backend && ./dev-install.sh
```
前端：
```bash
cd frontend && pnpm install
```

## 运行

```bash
# 后端（默认 PG；零外部依赖加 -Dspring-boot.run.profiles=h2 跳过 docker compose）
cd backend && docker compose up -d && ./mvnw -pl dataweave-api spring-boot:run   # :8000
cd frontend && pnpm dev                                                             # :4000
```

## 验证（手测路径）

1. `admin/admin` 登录 → JWT 注入 `localStorage` → 工作区左侧 **admin 组 → 系统设置**。
2. 顶部看到「**配置**」tab（与 用户/角色/项目 并列，下划线式）；既有三个 tab 行为不变。
3. 切「配置」→ 左导航有「**AI Agent**」分区，选中 → 右侧内联表单。
4. 填 OpenAI 协议 + baseUrl + model + apiKey → **测试连接**（成功带延迟 / 失败带脱敏原因）→ **保存**。
5. 重新打开 → apiKey 脱敏回显 `sk-…xxxx`；留空保存 → 原密钥不变（留空=不改）。
6. 到**任一项目** push 一个需要 AI 抽取的脚本任务 → 血缘图谱短时内出现「AI 推断」边（全局配置已对该项目生效——SC-001/SC-002）。
7. **禁用**全局配置 → 再 push → AI 通道旁路，其余通道正常，push 不阻断（SC-005）。
8. 血缘探索器工具栏 → 原 AI Agent 配置按钮**已移除**（单一真相源在 系统设置 → 配置 → AI Agent，FR-015）。
9. 非管理员账号 → 系统设置入口不可见（沿用 `project:manage` 过滤），无法触及「配置」tab。

## 自动化测试

- **后端**：
  - `AgentLineageConfigServiceTest`：tenant 单例 upsert / 脱敏 `sk-…xxxx` / apiKey 留空不改 / 缺失或禁用 → 旁路。
  - Controller WebTestClient：`/api/settings/agent-config` 全局读写、去 projectId、admin 门（非 admin 拒绝，对齐 `/api/users`）、`/calls` 按 task 过滤。
- **前端**：
  - `config-sections.test.ts`：`id` 唯一性 + `filterVisibleSections` 纯函数（仿 `nav-groups.test.ts`）。
  - 浏览器验证：配置 tab 渲染、左导航选中切换、可拖拽调宽、表单 test/save、脱敏回显。

## 设计合规自查（交付前）
- [ ] 顶部 tab 用 `Tabs` 组件（非手写下划线）；既有三个 tab 内容不变。
- [ ] ConfigShell 布局对齐 `DataDevIdeShell`；`DwScroll` / `--card-spacing` / 语义 token；无区域分割线。
- [ ] 数值字段用 `Input`（大范围，非 `Stepper`）；协议用 `DropdownSelect`；启用用 `Switch`。
- [ ] i18n：`settingsView` 命名空间新增 key，zh-CN / en-US 双 bundle 同步（CI 静态校验 `t("key")`）。
- [ ] 血缘工具栏旧入口已移除。
