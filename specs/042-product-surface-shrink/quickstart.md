# Quickstart: 042 产品面收缩验证指南

## 前置

```bash
cd frontend && pnpm install
# 后端可选（视图打开验证需要）：cd backend && ./mvnw -pl dataweave-api spring-boot:run -Dspring-boot.run.profiles=h2
```

## 自动化门（全绿才算过）

```bash
cd frontend
pnpm typecheck        # 零错误：删除后无悬空引用
pnpm test             # vitest：导航不变量/权限过滤/快照降级用例
pnpm i18n:check       # 若无此脚本，跑仓库既有的 i18n 键集一致性检查方式
```

预期：typecheck 零错误；`nav-groups.test.ts` 的"入口∪详情=全集"不变量在 14 视图全集下通过；新增的 removed-view 快照降级用例通过。

## 浏览器验证（对照 [contracts/ui-surface.md](contracts/ui-surface.md)）

1. **导航清单**（SC-001）：登录后核对左侧导航 = 契约 §3 的 12 入口；中英两语言下均检索不到"指标市场/报表/数据服务/数据集成"。
2. **默认常驻标签**（US1-3）：清空 localStorage 后刷新，标签栏仅 freshness + metrics 两个常驻标签。
3. **深链降级**（SC-002）：逐个访问 `/?open=marketplace|reports|service|integration` → 均落默认视图，无白屏；访问 `/integration` `/service` `/metrics` → 重定向到 `/`。
4. **旧快照降级**（US2）：DevTools 中向工作区快照的 localStorage 键注入一个 `view: "reports"` 的标签（设为激活），刷新 → 该标签消失、激活态落在剩余标签、console 无异常。
5. **保留视图逐一打开**（SC-004）：契约 §3 全部 12 入口逐个点开正常渲染；catalog 的资产详情/订阅弹窗正常（共享 catalog-api 未被误伤）。

## 回归红线

- 任一语言出现裸键名（如 `views.marketplace`）→ i18n 删键不彻底或删多了。
- console 出现 `[workspace] 忽略未注册视图` 以外的报错 → 降级路径破了。
- catalog/quality/alerts/event-center 行为有任何变化 → 违反 FR-008。
