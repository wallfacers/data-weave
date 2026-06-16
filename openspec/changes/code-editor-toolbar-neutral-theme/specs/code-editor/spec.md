## ADDED Requirements

### Requirement: 复用型代码编辑器与主题接管

复用型代码编辑器 SHALL 基于 Monaco，由 Shiki 经 `shikiToMonaco` 接管 tokenizer 与主题，主题随 `next-themes` 亮/暗实时切换，并复用 `lib/syntax-palette.ts` 的同一套 `buildSyntaxTheme()`（与 chat 代码块同源）。编辑器 SHALL 在 highlighter 预加载完成后再渲染、在 `beforeMount` 同步注册项目主题，`theme` prop SHALL 始终为项目主题名，绝不回落到 Monaco 内建 `light`/`vs`。

#### Scenario: 主题跟随亮暗切换
- **WHEN** 用户在亮/暗主题间切换
- **THEN** 编辑器底色、前景与语法 token 实时重设为对应主题，无 "Theme `light` not found" 报错，无主题缺失白屏

### Requirement: 可选操作工具栏

代码编辑器 SHALL 提供一套**默认关闭、由 prop 显式开启**的操作工具栏；未开启时组件行为与既有内联用法一致。开启后工具栏 SHALL 提供：**复制全部**、**粘贴**、**格式化**、**清空**、**查找** 五组操作。工具栏按钮 SHALL 用 hugeicons 图标 + base `Button`（`size="sm" variant="ghost"`），遵守前端栈约束。

#### Scenario: 默认不显示工具栏
- **WHEN** 调用方未开启工具栏 prop
- **THEN** 编辑器仅渲染代码区，无工具栏，既有 chat/内联用法不受影响

#### Scenario: 开启后五组操作可见可用
- **WHEN** 调用方开启工具栏且编辑器可写
- **THEN** 复制全部 / 粘贴 / 格式化 / 清空 / 查找 五个入口渲染并可触发

#### Scenario: 格式化与查找走 Monaco 原生能力
- **WHEN** 用户点「格式化」或「查找」
- **THEN** 分别触发 Monaco 的 format 与 find action（查找弹出原生 Ctrl+F 面板），无需服务端参与

#### Scenario: 清空需二次确认
- **WHEN** 用户点「清空」
- **THEN** 经确认后编辑器内容置空，并通过 onChange 同步给调用方

### Requirement: 剪贴板权限优雅降级

复制 SHALL 经 `navigator.clipboard.writeText` 写入剪贴板。粘贴 SHALL 经 `navigator.clipboard.readText` 读取并插入到当前光标处。当浏览器拒绝读剪贴板（无权限 / 非安全上下文 / 浏览器默认禁读）时，粘贴 SHALL NOT 静默失败，SHALL toast 提示改用 `Ctrl+V`；编辑器原生 `Ctrl+C`/`Ctrl+V` 快捷键 SHALL 始终作为兜底可用。当 `navigator.clipboard` API 不存在时，复制/粘贴按钮 SHALL 置灰并带 tooltip 说明，不在点击后才暴露失败。

#### Scenario: 粘贴被浏览器拦截时降级
- **WHEN** 用户点「粘贴」但 `readText()` 抛权限/安全上下文异常
- **THEN** 弹出 toast 提示「浏览器拦截了读取剪贴板，请用 Ctrl+V」，编辑器内容不变，Ctrl+V 仍可正常粘贴

#### Scenario: 剪贴板 API 不可用时按钮置灰
- **WHEN** 运行环境无 `navigator.clipboard`
- **THEN** 复制/粘贴按钮呈禁用态并带解释性 tooltip，格式化/清空/查找仍可用

#### Scenario: 复制成功反馈
- **WHEN** 用户点「复制全部」且 `writeText` 成功
- **THEN** 全文写入系统剪贴板并 toast 成功提示

### Requirement: 只读编辑器操作收敛

当编辑器为 `readOnly` 时，工具栏 SHALL 仅保留 复制 / 查找，隐藏 粘贴 / 格式化 / 清空（这些是写操作，对只读视图无意义）。

#### Scenario: 只读时隐藏写操作
- **WHEN** 编辑器以 readOnly 渲染且开启工具栏
- **THEN** 仅复制与查找入口可见，粘贴/格式化/清空不渲染
