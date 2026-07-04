# Quickstart: 资产目录页面规范化重设计

**Created**: 2026-07-05 | **Feature**: 043-asset-catalog-polish

## 前提

- 后端运行中（`docker compose --profile distributed up -d` 或 `./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2`）
- 前端运行中（`cd frontend && pnpm dev`）
- 浏览器打开 `http://localhost:4000`，admin/admin 登录，注入 JWT 到 `localStorage.setItem("dw.auth.token", "<token>")`

## 手验步骤（10 步）

### Step 1：打开资产目录

点击左侧导航 "Asset Catalog" → 页面加载，卡片网格以默认三列或两列呈现。

**验证**：卡片含图标、资产名（加粗）、限定名（灰色）、敏感度 Badge、状态 Badge、负责人、更新日期。

### Step 2：筛选敏感度

点击 toolbar segmented 控件中的 "INTERNAL" 或 "CONFIDENTIAL"。

**验证**：选中段高亮；非匹配卡片以 opacity 动画过渡；toolbar 中出现 "清除筛选" 按钮。

### Step 3：清除筛选

点击 toolbar 中的 "清除筛选" 按钮。

**验证**：所有 segmented 回到 "全部"；卡片网格恢复初始展示。

### Step 4：搜索资产

在搜索框中输入关键词（如 "GMV"）。

**验证**：卡片网格过滤为仅匹配项；搜索框有清除按钮。

### Step 5：展开卡片详情

点击一张资产卡片。

**验证**：卡片 border 高亮（蓝色）；卡片原地向下滑出详情区（motion 动画）；详情含描述、血缘、质量、元数据标签、操作按钮。

### Step 6：收起卡片

再次点击已展开的卡片，或点击另一张卡片。

**验证**：当前卡片收起（动画），若点击新卡片则新卡片展开；始终只展开一张。

### Step 7：编辑资产

在展开的卡片操作区点击 "编辑" 按钮 → Dialog 弹出预填表单 → 修改描述 → 提交。

**验证**：sonner toast 弹出成功提示；卡片详情刷新为新数据。

### Step 8：下线资产

在展开的卡片操作区点击 "下线" 按钮 → ConfirmDialog 弹出 → 确认。

**验证**：sonner toast 弹出 "已提交审批" 或 "操作成功"；列表刷新；资产状态 Badge 更新。

### Step 9：我的订阅

点击 toolbar 中的 "我的订阅" 按钮 → SubscriptionsDialog 弹出。

**验证**：有订阅时可退订；无订阅时显示空态。

### Step 10：编目资产

点击 toolbar 中的 "编目资产" 按钮 → AssetFormDialog 弹出 → 填写表单 → 提交。

**验证**：提交成功 dialog 关闭；卡片网格刷新出现新卡片。

## 门禁

- `pnpm typecheck`：0 错误
- `pnpm test`：vitest 全绿（既有 lib 测试不受影响 + 新增测试）
- `pnpm design:lint`：0 错误 0 警告
- `node scripts/check-i18n.mjs`：zh-CN/en-US key 集一致
