package com.dataweave.master.application;

import com.dataweave.master.application.OpsContracts.BackfillRequest;
import com.dataweave.master.application.OpsContracts.BackfillRunDetail;
import com.dataweave.master.application.OpsContracts.BackfillRunView;
import com.dataweave.master.application.OpsContracts.InstanceRow;
import com.dataweave.master.domain.BackfillRun;
import com.dataweave.master.domain.BackfillRunRepository;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowDefRepository;
import com.dataweave.master.i18n.BizException;
import com.dataweave.master.i18n.Messages;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 补数据（data-ops-center）：按 任务/工作流 × 日期区间生成 BACKFILL 实例，进度由子实例聚合派生。
 *
 * <p>生成侧为纯 INSERT（经 {@link WorkflowTriggerService}），认领/执行复用既有路径——BACKFILL 仅作
 * runMode 标识，不新增认领分支（design D3，死锁不变量不变）。{@code parallelism} M1 仅记录于批次
 * （跨 bizDate 运行并发由全局调度/worker 容量自然约束，硬节流留 M2，见 design 开放问题③）。
 */
@Service
public class BackfillService {

    /** 单次补数据日期跨度上限（防误操作生成海量实例）。 */
    private static final int MAX_DATE_SPAN = 366;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final BackfillRunRepository backfillRunRepository;
    private final WorkflowTriggerService triggerService;
    private final TaskDefRepository taskDefRepository;
    private final WorkflowDefRepository workflowDefRepository;
    private final JdbcTemplate jdbc;

    public BackfillService(BackfillRunRepository backfillRunRepository,
                           WorkflowTriggerService triggerService,
                           TaskDefRepository taskDefRepository,
                           WorkflowDefRepository workflowDefRepository,
                           JdbcTemplate jdbc) {
        this.backfillRunRepository = backfillRunRepository;
        this.triggerService = triggerService;
        this.taskDefRepository = taskDefRepository;
        this.workflowDefRepository = workflowDefRepository;
        this.jdbc = jdbc;
    }

    /** 发起补数据：校验 → 落 backfill_run → 逐 bizDate 生成实例 → 回填 total → 返回视图。 */
    public BackfillRunView submitBackfill(BackfillRequest req) {
        if (req == null || req.targetId() == null) {
            throw new BizException("backfill.target_required");
        }
        boolean isTask = "task".equalsIgnoreCase(req.targetType());
        boolean isWorkflow = "workflow".equalsIgnoreCase(req.targetType());
        if (!isTask && !isWorkflow) {
            throw new BizException("backfill.invalid_target_type", req.targetType());
        }
        List<LocalDate> dates = expandDates(req.dateStart(), req.dateEnd());

        String targetName;
        Long tenantId;
        Long projectId;
        WorkflowDef wf = null;
        if (isTask) {
            TaskDef def = taskDefRepository.findById(req.targetId())
                    .orElseThrow(() -> new IllegalStateException("Task def not found: " + req.targetId()));
            targetName = def.getName();
            tenantId = def.getTenantId();
            projectId = def.getProjectId();
        } else {
            wf = workflowDefRepository.findById(req.targetId())
                    .orElseThrow(() -> new IllegalStateException("Workflow def not found: " + req.targetId()));
            targetName = wf.getName();
            tenantId = wf.getTenantId();
            projectId = wf.getProjectId();
        }

        LocalDateTime now = LocalDateTime.now();
        BackfillRun run = new BackfillRun();
        run.setTenantId(tenantId);
        run.setProjectId(projectId);
        run.setTargetType(isTask ? "task" : "workflow");
        run.setTargetId(req.targetId());
        run.setTargetName(targetName);
        run.setDateStart(dates.get(0).format(ISO));
        run.setDateEnd(dates.get(dates.size() - 1).format(ISO));
        run.setIncludeDownstream(req.includeDownstream() ? 1 : 0);
        run.setParallelism(Math.max(1, req.parallelism()));
        run.setState("RUNNING");
        run.setTotal(0);
        run.setCreatedAt(now);
        run.setUpdatedAt(now);
        run.setDeleted(0);
        run.setVersion(0L);
        BackfillRun saved = backfillRunRepository.save(run);
        UUID runId = saved.getId();

        // bizDate 粒度节流（backfill-parallelism-throttle，design D2）：bizDate 升序前 parallelism 个放行（held=0），
        // 其余整批持有（held=1），由 BackfillPromoter 完成即晋升。held 在 INSERT 时即定（trigger 透传），
        // 根除「插入后→置 held 前」被抢认领的竞态。parallelism≥bizDate 总数时全部 held=0，退化为无节流。
        int parallelism = Math.max(1, req.parallelism());
        for (int i = 0; i < dates.size(); i++) {
            String bizDate = dates.get(i).format(ISO);
            int held = i < parallelism ? 0 : 1;
            if (isTask) {
                triggerService.triggerBackfillTaskRun(req.targetId(), bizDate, runId, held, Messages.DEFAULT_LOCALE);
            } else {
                // 工作流补数：物化整张 DAG（同 bizDate 内上下游就绪由调度就绪门自然串行 → includeDownstream 语义）。
                triggerService.trigger(wf, "BACKFILL", bizDate, null, Messages.DEFAULT_LOCALE,
                        "FULL", null, "BACKFILL", runId, held);
            }
        }

        // 回填 total（实际生成的子实例数；工作流目标每 bizDate 多个节点）。
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM task_instance WHERE backfill_run_id=? AND deleted=0", Integer.class, runId);
        saved.setTotal(total == null ? 0 : total);
        saved.setUpdatedAt(LocalDateTime.now());
        backfillRunRepository.save(saved);

        return toView(saved);
    }

    /** 补数据批次列表（按创建时间倒序分页）+ 各自聚合进度。 */
    public List<BackfillRunView> backfillRuns(int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 200);
        List<BackfillRun> runs = jdbc.query(
                "SELECT * FROM backfill_run WHERE deleted=0 ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, n) -> mapRun(rs), s, (long) p * s);
        List<BackfillRunView> views = new ArrayList<>(runs.size());
        for (BackfillRun r : runs) {
            views.add(toView(r));
        }
        return views;
    }

    /**
     * 补数据批次多维筛选 + 分页 + 各自聚合进度。
     * state CSV 多选（IN）；targetName 模糊；targetType 精确；bizDate 区间在 [date_start,date_end] 上重叠
     * （date_end≥from AND date_start≤to）；createdBy 精确。任一为空即不约束。
     */
    public OpsContracts.PageResult<BackfillRunView> queryBackfillRuns(
            String stateCsv, String targetName, String targetType,
            String bizDateFrom, String bizDateTo, Long createdBy, int page, int size) {
        int p = Math.max(0, page);
        int s = Math.min(Math.max(1, size), 200);
        StringBuilder where = new StringBuilder(" WHERE deleted=0 ");
        List<Object> args = new ArrayList<>();
        if (stateCsv != null && !stateCsv.isBlank()) {
            String[] states = stateCsv.split(",");
            String ph = String.join(",", java.util.Collections.nCopies(states.length, "?"));
            where.append("AND state IN (").append(ph).append(") ");
            for (String st : states) args.add(st.trim());
        }
        if (targetName != null && !targetName.isBlank()) {
            where.append("AND target_name LIKE CONCAT('%', ?, '%') ");
            args.add(targetName.trim());
        }
        if (targetType != null && !targetType.isBlank()) {
            where.append("AND target_type=? ");
            args.add(targetType.trim());
        }
        if (bizDateFrom != null && !bizDateFrom.isBlank()) {
            where.append("AND date_end >= ? ");
            args.add(bizDateFrom.trim());
        }
        if (bizDateTo != null && !bizDateTo.isBlank()) {
            where.append("AND date_start <= ? ");
            args.add(bizDateTo.trim());
        }
        if (createdBy != null) {
            where.append("AND created_by=? ");
            args.add(createdBy);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM backfill_run" + where, Long.class, args.toArray());
        long totalCount = total == null ? 0L : total;
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(s);
        pageArgs.add((long) p * s);
        List<BackfillRun> runs = jdbc.query(
                "SELECT * FROM backfill_run" + where + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                (rs, n) -> mapRun(rs), pageArgs.toArray());
        List<BackfillRunView> views = new ArrayList<>(runs.size());
        for (BackfillRun r : runs) {
            views.add(toView(r));
        }
        return new OpsContracts.PageResult<>(views, totalCount, p, s);
    }

    /** 单批次详情 + 其全部子实例。 */
    public BackfillRunDetail backfillRun(UUID runId) {
        BackfillRun run = backfillRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalStateException("Backfill run not found: " + runId));
        List<InstanceRow> instances = jdbc.query(
                "SELECT ti.id, ti.task_id, ti.workflow_instance_id, ti.run_mode, ti.state, ti.biz_date, "
                        + "ti.started_at, ti.finished_at, "
                        + "(SELECT td.name FROM task_def td WHERE td.id=ti.task_id) AS task_name, "
                        + "(SELECT wd.cron FROM workflow_instance wi "
                        + "  JOIN workflow_def wd ON wd.id=wi.workflow_id "
                        + "  WHERE wi.id=ti.workflow_instance_id) AS cron_expr "
                        + "FROM task_instance ti WHERE ti.backfill_run_id=? AND ti.deleted=0 "
                        + "ORDER BY ti.biz_date ASC, ti.id ASC",
                (rs, n) -> mapInstanceRow(rs), runId);
        return new BackfillRunDetail(toView(run), instances);
    }

    // ─── 内部：聚合进度 / 映射 ─────────────────────────────

    private BackfillRunView toView(BackfillRun r) {
        Map<String, Integer> byState = new HashMap<>();
        jdbc.query("SELECT state, COUNT(*) AS c FROM task_instance WHERE backfill_run_id=? AND deleted=0 GROUP BY state",
                rs -> { byState.put(rs.getString("state"), rs.getInt("c")); }, r.getId());
        int success = byState.getOrDefault("SUCCESS", 0);
        int failed = byState.getOrDefault("FAILED", 0);
        int total = byState.values().stream().mapToInt(Integer::intValue).sum();
        int running = total - success - failed;
        String derived = deriveState(running, success, failed);
        // 节流可观测（backfill-parallelism-throttle）：activeDates=放行且未全部终态的 bizDate 数；heldDates=持有待晋升数。
        Integer activeDates = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT biz_date) FROM task_instance WHERE backfill_run_id=? AND deleted=0 "
                        + "AND COALESCE(backfill_held,0)=0 AND state NOT IN ('SUCCESS','FAILED')",
                Integer.class, r.getId());
        Integer heldDates = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT biz_date) FROM task_instance WHERE backfill_run_id=? AND deleted=0 "
                        + "AND COALESCE(backfill_held,0)=1",
                Integer.class, r.getId());
        return new BackfillRunView(r.getId(), r.getTargetType(), r.getTargetId(), r.getTargetName(),
                r.getDateStart(), r.getDateEnd(),
                r.getParallelism() == null ? 1 : r.getParallelism(),
                r.getIncludeDownstream() != null && r.getIncludeDownstream() == 1,
                derived, total, success, failed, running,
                r.getCreatedAt() != null ? r.getCreatedAt().toString() : null,
                activeDates == null ? 0 : activeDates, heldDates == null ? 0 : heldDates);
    }

    /** RUNNING（仍有在跑）→ 否则全成 SUCCESS / 全败 FAILED / 部分失败 PARTIAL。 */
    private static String deriveState(int running, int success, int failed) {
        if (running > 0) {
            return "RUNNING";
        }
        if (failed == 0) {
            return "SUCCESS";
        }
        if (success == 0) {
            return "FAILED";
        }
        return "PARTIAL";
    }

    private List<LocalDate> expandDates(String start, String end) {
        if (start == null || start.isBlank() || end == null || end.isBlank()) {
            throw new BizException("backfill.dates_required");
        }
        LocalDate s = LocalDate.parse(start.trim(), ISO);
        LocalDate e = LocalDate.parse(end.trim(), ISO);
        if (e.isBefore(s)) {
            throw new BizException("backfill.date_order");
        }
        long span = Duration.between(s.atStartOfDay(), e.atStartOfDay()).toDays() + 1;
        if (span > MAX_DATE_SPAN) {
            throw new BizException("backfill.date_span", MAX_DATE_SPAN);
        }
        List<LocalDate> dates = new ArrayList<>();
        for (LocalDate d = s; !d.isAfter(e); d = d.plusDays(1)) {
            dates.add(d);
        }
        return dates;
    }

    private BackfillRun mapRun(java.sql.ResultSet rs) throws java.sql.SQLException {
        BackfillRun r = new BackfillRun();
        r.setId(rs.getObject("id", UUID.class));
        r.setTenantId((Long) rs.getObject("tenant_id"));
        r.setProjectId((Long) rs.getObject("project_id"));
        r.setTargetType(rs.getString("target_type"));
        r.setTargetId((Long) rs.getObject("target_id"));
        r.setTargetName(rs.getString("target_name"));
        r.setDateStart(rs.getString("date_start"));
        r.setDateEnd(rs.getString("date_end"));
        r.setIncludeDownstream((Integer) rs.getObject("include_downstream"));
        r.setParallelism((Integer) rs.getObject("parallelism"));
        r.setState(rs.getString("state"));
        r.setTotal((Integer) rs.getObject("total"));
        r.setCreatedAt(rs.getObject("created_at", LocalDateTime.class));
        return r;
    }

    private InstanceRow mapInstanceRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        UUID id = rs.getObject("id", UUID.class);
        UUID wiId = rs.getObject("workflow_instance_id", UUID.class);
        Long taskId = (Long) rs.getObject("task_id");
        LocalDateTime startedAt = rs.getObject("started_at", LocalDateTime.class);
        LocalDateTime finishedAt = rs.getObject("finished_at", LocalDateTime.class);
        Long durationMs = (startedAt != null && finishedAt != null)
                ? Duration.between(startedAt, finishedAt).toMillis() : null;
        return new InstanceRow(id, taskId, rs.getString("task_name"), wiId,
                rs.getString("run_mode"), rs.getString("state"), rs.getString("biz_date"),
                startedAt != null ? startedAt.toString() : null,
                finishedAt != null ? finishedAt.toString() : null, durationMs,
                rs.getString("cron_expr"));
    }
}
