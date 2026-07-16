# Quickstart 验证指南：管家会话列表模式 + Markdown 回复

纯前端特性。后端沿用既有部署（companion + workhorse sidecar），无需重建。

## 前置

```bash
# 后端（已部署则跳过）：companion + workhorse sidecar 在线，schema 已灌
# 前端 dev
cd frontend && pnpm install && pnpm dev     # http://localhost:4000
```

- 需真实 workhorse 大脑在线（对话回复非兜底）；离线时验证降级路径（SYSTEM 报错入线程）。
- 浏览器门登录注入：`localStorage` 写 `dw.auth.token` / `dw.auth.user` / `dw.project.current`（见既有 `scratchpad/gate-*.mjs` 套路）。

## 后端零改动自证

```bash
# worktree 内后端目录应无 diff
git diff --stat -- backend/     # 期望：空
grep -n '"version"' backend/dataweave-api/src/main/resources/schema.sql | head -1   # 版本不变
```

## 静态门（每次前端改动后）

```bash
cd frontend
pnpm typecheck        # 零错误
pnpm design:lint      # DESIGN.md 约束通过
# i18n 双语键集一致（CI 校验）：zh-CN / en-US 新增键成对
```

## 单测门

```bash
cd frontend
pnpm vitest run lib/companion/store.test.ts        # 合并去重、addMessage 幂等、锚定回落
pnpm vitest run components/workspace/views/companion   # 线程渲染/问题行/空态（按新增组件补）
```

## US1 — 统一会话线程 + Markdown（P1，MVP）

1. `/?open=companion`，底部输入框发「用表格报告四领域巡检状态」。
2. **期望**：Vega 回复以 Markdown 表格渲染在**右侧下半区会话线程**，用户消息也在线程、时间正序；不塌陷为纯文本。
3. 再发一条让其返回代码块的指令 → 代码块含语言标签 + 复制按钮 + 项目双主题高亮。
4. 刷新页面 → 会话线程**加载出既往历史**（非空白），无重复条目。
5. 发送后流式中点「停止」→ 输出中止、该条保留已产出 + 中断标记，可继续发下一条。
6. （降级）workhorse 停机时发消息 → 线程出现可见 SYSTEM 兜底报错（非静默）。

## US2 — 待处理问题列表（P2）

1. 触发一轮产出多条不同严重度汇报的巡检（或用既有 mock/触发例程）。
2. **期望**：右侧**上半区**以列表行（非卡片）呈现全部未关闭问题，严重度色点 + 倒序 + 聚合计数 + 未读计数徽标；整块可折叠。
3. 点某行关闭 → 该行消失、未读计数减一；刷新后不复现（项目级共享关闭）。
4. 点「查看详情」→ 正确跳转（监督席/对象详情）。
5. 无未关闭汇报 → 上半区空态文案，线程仍可用。

## US3 — 点问题锚定追问（P3）

1. 问题列表点某问题 → 会话线程头部出现「已锚定：<问题标题>」+ 取消锚定入口。
2. 随后提问 → Vega 回答基于该问题上下文（非泛泛）。
3. 点「取消锚定」→ 回落全局对话，后续消息不带 reportId。
4. 切换到另一问题 → 其既往对话被加载并入线程。
5. （边界）当前锚定问题被他人关闭（SSE report:closed 命中）→ 提示「该问题已处置」并回落全局，不阻断进行中的对话。

## 主题门

- 亮/暗主题切换（`/system-settings` 或主题开关）→ 会话线程、Markdown（含代码高亮）、问题列表色点与文案均可读，即时生效无需刷新。

## 浏览器门产物

- 截图：`tmp/` 下 `073-thread-markdown.png` / `073-problem-list.png` / `073-anchor.png` / `073-theme-dark.png`。
- 断言：线程含 Markdown 元素（table/pre）、问题行数=汇报数、锚定头文案、无 `pageerror`、时间无裸 ISO `T`/微秒、占位符无 `…`。
