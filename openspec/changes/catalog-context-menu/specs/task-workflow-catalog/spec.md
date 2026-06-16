# task-workflow-catalog Specification (delta: catalog-context-menu)

## MODIFIED Requirements

### Requirement: 叶子（任务/工作流）重命名与删除

`<CatalogTree>` SHALL 支持对任务与工作流叶子就地重命名与删除，入口经**右键上下文菜单**（不再渲染 hover 行内 ✏️/🗑️ 按钮），交互经 `Dialog`（非原生弹框）。重命名 MUST 调用对应写接口（任务 `PUT /api/tasks/{id}`、工作流 `PUT /api/workflows/{id}`）；删除 MUST 调用软删接口（`DELETE /api/tasks/{id}`、`DELETE /api/workflows/{id}`）并二次确认。成功后树 MUST 刷新。叶子行 MUST NOT 再渲染 hover 触发的行内操作按钮。

#### Scenario: 右键任务重命名

- **WHEN** 用户右键一个任务叶子选择「重命名」并在 Dialog 提交新名
- **THEN** 系统调用任务写接口更新名称，树刷新显示新名

#### Scenario: 右键删除工作流需确认

- **WHEN** 用户右键一个工作流叶子选择「删除」
- **THEN** 系统弹 `Dialog` 二次确认，确认后软删该工作流，树不再显示

#### Scenario: 叶子行不再有 hover 行内按钮

- **WHEN** 用户将鼠标悬停在任务或工作流叶子行上
- **THEN** 行内不出现 ✏️/🗑️ 操作按钮，重命名与删除仅经右键菜单触发

## ADDED Requirements

### Requirement: 类目树右键上下文菜单

`<CatalogTree>` SHALL 在节点上提供右键上下文菜单，菜单项按节点类型差异化呈现。菜单组件 MUST 封装 `@base-ui/react` 的 ContextMenu（base 风格，语义 token），交互式输入/确认仍统一经 `Dialog`（非原生弹框）。右键菜单 MUST NOT 干扰节点既有的点击打开与拖拽行为（点击开子 Tab、拖拽移动归属/拖任务上画布与右键弹菜单三者并存）。

#### Scenario: 右键文件夹显示文件夹菜单

- **WHEN** 用户右键一个文件夹节点
- **THEN** 弹出含「新建子文件夹 / 新建任务（在此）/ 新建工作流（在此）/ 重命名 / 删除」的上下文菜单

#### Scenario: 右键叶子显示叶子菜单

- **WHEN** 用户右键一个任务或工作流叶子
- **THEN** 弹出含「重命名 / 删除」的上下文菜单

#### Scenario: 右键空白区显示根级菜单

- **WHEN** 用户在类目树空白区域右键
- **THEN** 弹出含「新建根文件夹 / 新建任务 / 新建工作流」的上下文菜单

#### Scenario: 右键不破坏既有交互

- **WHEN** 用户对同一叶子分别执行左键点击、拖拽、右键
- **THEN** 左键打开对应子 Tab、拖拽触发移动归属或上画布、右键弹出菜单，三者互不干扰

### Requirement: 文件夹前端 CRUD 入口（含非空禁删置灰）

`<CatalogTree>` SHALL 经右键上下文菜单提供文件夹的创建子文件夹、重命名、删除入口，分别对接 `POST /api/catalog/nodes`、`PATCH /api/catalog/nodes/{id}`（含 `name`）、`DELETE /api/catalog/nodes/{id}`。对包含子文件夹或已归类资产的文件夹，「删除」菜单项 MUST 在前端依据节点自带的计数与子节点本地判定为**禁用态**并附 tooltip 说明，无需先发起请求再吃错误。成功后树 MUST 刷新。

#### Scenario: 右键重命名文件夹

- **WHEN** 用户右键文件夹选择「重命名」并在 Dialog 提交新名
- **THEN** 系统调用 `PATCH /api/catalog/nodes/{id}` 更新 `name`，树刷新显示新名

#### Scenario: 删除空文件夹

- **WHEN** 用户右键一个无子文件夹、无归类资产的文件夹选择「删除」并确认
- **THEN** 系统调用 `DELETE /api/catalog/nodes/{id}` 删除该文件夹，树不再显示

#### Scenario: 非空文件夹删除项置灰

- **WHEN** 用户右键一个仍含子文件夹或归类资产的文件夹
- **THEN** 菜单的「删除」项为禁用态并附 tooltip「请先清空或移走子项」，无法发起删除请求

### Requirement: 在文件夹内新建任务/工作流

`<CatalogTree>` SHALL 支持经右键菜单在指定文件夹内直接新建任务或工作流草稿，并**一步将草稿归入该文件夹**——创建请求 body MUST 携带目标 `catalogNodeId`（`POST /api/tasks`、`POST /api/workflows`）。创建任务收集名称与类型（SQL/SHELL），创建工作流仅收集名称，均经 `Dialog`。创建成功后系统 MUST 打开对应编辑/画布子 Tab 并刷新树。在空白区/未分类经「新建任务/工作流」创建时，草稿归入未分类（不带 `catalogNodeId`）。

#### Scenario: 在文件夹内新建任务

- **WHEN** 用户右键文件夹 F 选择「新建任务」，在 Dialog 填名称与类型并提交
- **THEN** 系统 `POST /api/tasks`（body 含 `catalogNodeId=F`）创建草稿，草稿显示在文件夹 F 下，并打开该任务编辑子 Tab

#### Scenario: 在文件夹内新建工作流

- **WHEN** 用户右键文件夹 F 选择「新建工作流」，在 Dialog 填名称并提交
- **THEN** 系统 `POST /api/workflows`（body 含 `catalogNodeId=F`）创建草稿，草稿显示在文件夹 F 下，并打开该工作流画布子 Tab

#### Scenario: 空白区新建任务落未分类

- **WHEN** 用户在空白区右键选择「新建任务」并提交
- **THEN** 系统创建的任务草稿不带 `catalogNodeId`，显示在「未分类」节点下
