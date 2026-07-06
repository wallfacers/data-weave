# Fix-F1: 把 TERMINAL 信号 append 并入完成同事务（恢复 R4 no-loss 构造保证）

> 来源：[audit.md](audit.md) §3-F1。
> 现状：`WorkerReportService.writeTerminalSignal` 与 `casTaskTerminal` 是两个独立 auto-commit 事务，偏离 R4/contracts「同事务 no-loss」承诺；崩溃/异常时丢信号，靠 Reconciler 兜底。
> 目标：完成状态推进（task_instance → SUCCESS/FAILED）与信号 append（readiness_signal INSERT）**原子提交**——要么都提交、要么都回滚。
> 行号基于 `dw-051` worktree 未提交快照（HEAD `a7adf92`），US1 定型后按实际行号对齐。

## 推荐方案 B：编程式 TransactionTemplate（与 SchedulerKernel 既有模式一致）

### 改动 1 — WorkerReportService 注入事务模板

构造器（WorkerReportService.java:49-75）加 `PlatformTransactionManager`，建 `txTemplate`：

```java
// 新增字段（与 SchedulerKernel 一致的模式）
private final org.springframework.transaction.support.TransactionTemplate txTemplate;

// 构造器新增参数（最后一个即可）：
//                               ...
//                               ReadinessSignalWriter readinessSignalWriter,
//                               PlatformTransactionManager txManager) {   ← 新增
    // ...
    this.readinessSignalWriter = readinessSignalWriter;
    this.txTemplate = new org.springframework.transaction.support.TransactionTemplate(txManager);  // 新增
}
```

### 改动 2 — reportFinished 终态段包编程式事务（WorkerReportService.java:89-108）

**关键**：事务**只包** `casTaskTerminal + writeTerminalSignal`；`writeLog/recordTaskCompletion/recordSyncedRows/recomputeWorkflow/wake` 留在事务外（避免长事务持锁 + neo4j/聚合调用不进正确性事务）。

```java
public void reportFinished(UUID taskInstanceId, Integer exitCode, String tailLog,
                           List<StatementMetric> statementMetrics) {
    TaskInstance ti = taskInstanceRepository.findById(taskInstanceId).orElse(null);
    if (ti == null) return;

    // 【修复】终态推进 + 信号 append 同事务（R4 no-loss：信号与完成同提交，崩溃不丢）
    final String fromState = ti.getState();
    boolean ok = Boolean.TRUE.equals(txTemplate.execute(status -> {
        if (!stateMachine.casTaskTerminal(taskInstanceId, fromState, InstanceStates.SUCCESS, null)) {
            return false;
        }
        writeTerminalSignal(ti);   // 同事务 INSERT readiness_signal；异常 → 回滚 casTaskTerminal → no-loss
        return true;
    }));
    if (!ok) { wake(); return; }

    writeLog(taskInstanceId, exitCode, tailLog);          // 事务外（辅助）
    recordTaskCompletion(taskInstanceId, "SUCCESS");      // 事务外（指标）
    recordSyncedRows(ti, statementMetrics);               // 事务外（neo4j 降级零阻断，不应进正确性事务）
    recomputeWorkflow(ti.getWorkflowInstanceId());        // 事务外（级联 STOPPED + 聚合，长路径）
    wake();
}
```

### 改动 3 — reportFailed 同样处理（WorkerReportService.java:171-188）

```java
public void reportFailed(UUID taskInstanceId, String failureReason, String tailLog) {
    TaskInstance ti = taskInstanceRepository.findById(taskInstanceId).orElse(null);
    if (ti == null) return;
    writeLog(taskInstanceId, null, tailLog);
    if (retryService.scheduleRetry(ti)) { wake(); return; }

    // 【修复】终态推进 + 信号 append 同事务（FAILED 也是 WEAK 依赖放行终态，同样 no-loss）
    final String fromState = ti.getState();
    txTemplate.executeWithoutResult(status -> {
        if (stateMachine.casTaskTerminal(taskInstanceId, fromState, InstanceStates.FAILED, failureReason)) {
            writeTerminalSignal(ti);
        }
    });

    recordTaskCompletion(taskInstanceId, "FAILED");
    recomputeWorkflow(ti.getWorkflowInstanceId());
    wake();
}
```

### 改动 4 — writeTerminalSignal 去掉 try-catch 吞异常（WorkerReportService.java:245-262）

**必须改**：当前 try-catch 吞掉 INSERT 异常（log.warn 返回），若保留则信号失败仍会被事务当作成功提交 → 又回到丢信号。修复后让异常向上传播 → `txTemplate` 回滚 → `casTaskTerminal` 也回滚 → no-loss。

```java
/** 051: 写 TERMINAL 信号。由 reportFinished/reportFailed 在终态事务内调用；
 *  异常向上传播触发事务回滚（no-loss：完成与信号原子）。 */
private void writeTerminalSignal(TaskInstance ti) {
    Long wfId = null;
    if (ti.getWorkflowInstanceId() != null) {
        var rows = jdbc.query(
                "SELECT workflow_id FROM workflow_instance WHERE id = ?",
                (rs, n) -> (Long) rs.getObject("workflow_id"),
                ti.getWorkflowInstanceId());
        if (!rows.isEmpty()) wfId = rows.get(0);
    }
    readinessSignalWriter.writeTerminal(
            ti.getTenantId(), ti.getProjectId(), ti.getId(),
            wfId, ti.getWorkflowInstanceId(),
            ti.getWorkflowNodeId(), ti.getBizDate());
    // 不再 try-catch：INSERT 失败 → 异常传播 → 外层 txTemplate 回滚 casTaskTerminal → no-loss
}
```

同时**删掉** WorkerReportService.java:104 那条不实的注释（"同事务写 TERMINAL 信号（no-loss...）"），改由代码真实承担该语义。

## 不推荐方案 A：方法级 @Transactional

- Spring AOP 陷阱：同类内部 `this.method()` 调用 @Transactional **不生效**（代理被绕过）。`reportFinished` 若要包住 `casTaskTerminal`（在 InstanceStateMachine bean）+ `writeTerminalSignal`（本类），整方法加 @Transactional 可行，但会**把 `recomputeWorkflow`（级联 STOPPED + computeAndUpdate 聚合）/ `recordSyncedRows`（neo4j 远程调用）一起包进长事务**——持锁久、吞吐退化、逼近死锁风险。
- 若要精细分事务边界（只包两步），须 self-injection（`@Lazy @Autowired WorkerReportService self`）或拆独立 bean——复杂度高于方案 B，无收益。
- 结论：编程式 TransactionTemplate（方案 B）精确控制边界、无代理陷阱、与 SchedulerKernel 既有风格一致，优于 A。

## 权衡与副作用（修后须知晓）

1. **可用性取舍（符合 R4）**：`readiness_signal` INSERT 持续失败（如表故障）会阻塞任务到达终态（casTaskTerminal 跟随回滚）——正确性优于可用性，这是 R4 no-loss 的固有代价。若不可接受，才走"best-effort + Reconciler 兜底"并修订 R4 措辞（即 F1 不修路线）。
2. **回滚假事件（既有设计，非新增）**：`casTaskTerminal` 内部 `publishTaskState`/`publishAlertSignalForTask` 在 UPDATE 后立即发事件（InstanceStateMachine.java:147、229）；修复后若事务回滚，这些已发事件成"假事件"。但 InstanceStateMachine 既有注释（217-233）已声明"事件仅作 UI 辅助，回滚偶发误差由前端下次拉取自愈"——非 F1 引入，可接受。如要彻底，可把事件发布挪到事务提交后（`TransactionSynchronization.afterCommit`），属单独优化项。
3. **Maintainer 侧无需改**：信号领取/重算/UPDATE unmet 路径不变；no-loss 只保证"信号必随完成提交"，下游重算仍异步。

## 修后验证

- T025（idempotency 单测）补一条：完成提交后 `readiness_signal` 必有对应 TERMINAL 行（同事务原子）。
- T026 用 [crash-injection-runbook.md](crash-injection-runbook.md) §0 的**严格 no-loss 口径**：崩溃前已提交的完成，其信号 `processed` 必在重启后收敛为 1（不再依赖 Reconciler 兜底窗口）。
- 回到 audit.md §1 ③ 复核：移除 §3-F1，③ 改为"完成事务同事务 append 信号、不锁下游行"完全满足。
