# Quickstart: 监督席对话体验打磨（070）验证指南

端到端验证场景与运行命令。契约细节见 [contracts/](contracts/)，实体细节见 [data-model.md](data-model.md)。

## 前置

```bash
# 后端（零外部依赖 H2 模式；若改过 master/worker 先 ./dev-install.sh）
cd backend && ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2   # :8000

# 前端
cd frontend && pnpm install && pnpm dev    # :4000
```

登录后确认 localStorage 已有 `dw.auth.token` / `dw.auth.user`（含 `username`/`displayName`）。

## 场景验证

### S1 加载/连接三态（US2）
1. 打开监督席（默认首页视图）：首帧前应见居中 LoadingState，**不得**出现「暂无事故」。
2. 停掉后端进程：线程顶部出现「连接已断开，重连中」，已有消息不消失。
3. 重启后端：自动回到正常态。

### S2 流式 Markdown（US1）
1. 在任一事故线程发问，诱导 Agent 回复含代码块/列表（如「给我一段修复 SQL 并解释步骤」）。
2. 观察流式全程无版式跳动；结束后代码块带语言标签+高亮；点复制→剪贴板一致+2s 对勾。
3. 切换明暗主题：高亮即时随主题（无重高亮闪烁）。

### S3 发言与打断（US3）
1. 中文输入法组字中按 Enter：候选上屏、不发送。
2. 输入多行：输入框长高到约 8 行后内部滚动。
3. Agent 长输出中点停止键：输出终止，消息带「已打断」标记；检查后端 `agent_action` 有 `incident_agent_cancel` 记录且**非** PENDING_APPROVAL。
4. 断网发消息：toast 显示后端本地化错误、输入不丢。

### S4 身份与时间（US4）
1. 两个不同账号先后发言：各自视角本人右对齐、他人左对齐+首字母头像+displayName；界面无 "ui-user"。
2. 直接 curl 带伪造 actor 验证服务端忽略：
   ```bash
   curl -s -X POST http://localhost:8000/api/incidents/<id>/chat \
     -H "Authorization: Bearer $DW_TOKEN" -H "X-Project-Id: 1" -H "Content-Type: application/json" \
     -d '{"text":"hi","actor":"hacker"}'   # 落库 actor 应为 token 内 username
   ```
3. 同人 5 分钟内连发多条：合并分组仅首条带头像；跨日历史有日期分隔；悬停见精确时间。

### S5 滚动与分栏（US5）
1. Agent 输出中上滑：视口不被拽回，出现回底按钮；点击恢复跟随。
2. 拖拽 feed/thread 分栏后刷新：宽度保持；把 feed 拖到极限仍保有最小宽度。

## 自动化测试

```bash
# 前端单测（store 三态/分组纯函数/composer 状态机）
cd frontend && pnpm test

# 类型与双语键集
cd frontend && pnpm typecheck

# 浏览器门（需前后端已起；全局 @playwright/test）
node <globalPlaywright>/cli.js test frontend/e2e/supervision.spec.ts

# 后端（长跑命令按 CLAUDE.md 用 setsid 脱离；H2 全量回归基线 369 例）
cd backend && ./mvnw -pl dataweave-api,dataweave-master test
```

## 预期达成（对应 spec Success Criteria）

- SC-001 连接未确认前 0 次假空态；SC-002 代码块 100% 高亮+复制一致；SC-003 IME 0 误发；
- SC-004 新消息 100% 真实显示名；SC-005 上滑 0 次被拽回；SC-006 打断 100% 留痕 0 审批等待；
- SC-007 typecheck/i18n/回归全绿；SC-008 设计合规复查通过（含 DESIGN.md 公共组件目录登记）。
