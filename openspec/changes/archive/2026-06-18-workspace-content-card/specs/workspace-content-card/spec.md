## ADDED Requirements

### Requirement: 内容区浮起卡

Workspace tab 条下方的视图内容容器 SHALL 渲染为一张浮起卡：`bg-sidebar` 底 + `border` + `shadow-lg` + `rounded-[var(--radius-lg)]` 圆角 + 左右下留边（`mx-3 mb-3`）。内容卡 MUST NOT 使用 Chrome 式弧形 tab 角，MUST NOT 改动 tab 药丸自身样式。

#### Scenario: 内容区是带边框背景的浮起卡

- **WHEN** 渲染任意激活的 Workspace tab（驾驶舱/任务流/数据新鲜度/业务报表）
- **THEN** tab 下方视图位于一张 `bg-sidebar` 底、带边框与阴影、圆角的浮起卡内，而非透明铺在页面背景上

#### Scenario: 内部白数据卡浮于卡面

- **WHEN** 内容卡里渲染 `bg-card` 数据卡（如驾驶舱的 StatCard / 表格卡）
- **THEN** 白数据卡浮在 `bg-sidebar` 卡面之上，层次可辨（亮色不再白对白）

### Requirement: 与 Agent 面板同材质配对

内容区浮起卡 SHALL 与左侧 Agent 对话面板（`agent-rail`）采用同一套表面规格（同 `bg-sidebar` + 同款 `border`/`shadow-lg`/`rounded-lg`），构成左右并列的一对抬升卡。

#### Scenario: 左右同底色

- **WHEN** 同时渲染左侧 Agent 面板与右侧内容卡（亮色或暗色）
- **THEN** 两者底色一致（同 `--sidebar` 取值），读成同材质的一对

### Requirement: 不引入额外内层表面层

视图内部数据卡 SHALL 保持 `bg-card`，MUST NOT 再为卡内表格/列表叠加额外的浮起内层面板（避免在 `bg-sidebar` 内容卡下形成「白叠白」冗余）。

#### Scenario: 无冗余内层面板

- **WHEN** 渲染含表格的数据卡（失败任务、Agent 诊断、实例表等）
- **THEN** 表格直接位于 `bg-card` 数据卡内，不存在额外的 `bg-surface-raised` 内嵌面板
