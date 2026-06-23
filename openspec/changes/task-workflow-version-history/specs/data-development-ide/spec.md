## MODIFIED Requirements

### Requirement: 任务编辑子 Tab 与按类型语法高亮

编辑子 Tab SHALL 在主区内提供任务的完整编辑能力，布局为左侧 Monaco 代码编辑器（按 `type` 切换语法高亮：SQL→`sql`，SHELL→`shell`）+ 右侧栏。右侧栏 MUST 拆分为 [配置 | 版本历史] 两个 tab：配置 tab 包含名称、类型、优先级、描述、调度参数（name→表达式）与替换预览、超时、重试等字段；版本历史 tab 展示发布版本列表及操作（详见 `specs/version-history/spec.md`）。保存 MUST 经任务写接口（草稿）并支持发布。

#### Scenario: SQL 任务高亮
- **WHEN** 用户打开一个 `type=SQL` 的任务编辑子 Tab
- **THEN** 脚本区以 SQL 语法高亮渲染内容

#### Scenario: SHELL 任务高亮
- **WHEN** 用户打开一个 `type=SHELL` 的任务编辑子 Tab
- **THEN** 脚本区以 shell 语法高亮渲染内容

#### Scenario: 编辑后保存草稿
- **WHEN** 用户在编辑子 Tab 修改脚本/配置后点击保存
- **THEN** 系统经任务写接口保存草稿，成功后清除脏标记

#### Scenario: 右侧栏 tab 切换
- **WHEN** 用户在编辑子 Tab 点击右侧栏"版本历史"tab
- **THEN** 右侧栏内容切换为版本历史列表，配置 tab 隐藏但状态保留
