package com.dataweave.api.application;

import com.dataweave.api.interfaces.dto.PageContext;
import com.dataweave.api.infrastructure.OpsMessages;
import com.dataweave.api.interfaces.dto.BatchOp;
import com.dataweave.api.interfaces.dto.InstanceQuery;
import com.dataweave.api.interfaces.dto.InstanceRow;
import com.dataweave.api.interfaces.dto.Page;
import com.dataweave.master.application.DiagnosisService;
import com.dataweave.master.application.FleetService;
import com.dataweave.master.application.LineageService;
import com.dataweave.master.application.MetricService;
import com.dataweave.master.application.QueryResult;
import com.dataweave.master.application.SqlExecutionService;
import com.dataweave.master.application.TaskService;
import com.dataweave.master.domain.AtomicMetric;
import com.dataweave.master.domain.MetricLineage;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDiagnosis;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.master.i18n.Messages;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则式意图路由器（mock Agent 引擎核心）。
 *
 * <p>把用户最后一条消息按关键词路由到 5 种意图：指标查询 / Text-to-SQL / 建任务 / 血缘 / 兜底。
 * 命中后调对应领域服务，产出 {@link AgentReply}（Markdown 文本 + 可选结构化结果）。
 *
 * <p>真模型能力已统一由 workhorse 大脑提供，此处仅保留规则兜底；
 * 编排器与领域服务调用方式不变。
 */
@Component
public class IntentRouter {

    private final MetricService metricService;
    private final LineageService lineageService;
    private final TaskService taskService;
    private final SqlExecutionService sqlExecutionService;
    private final FleetService fleetService;
    private final DiagnosisService diagnosisService;
    private final DataOpsBridge dataOpsBridge;
    private final OpsMessages opsMessages;
    private final ObjectMapper objectMapper;
    private final Messages messages;

    public IntentRouter(MetricService metricService,
                        LineageService lineageService,
                        TaskService taskService,
                        SqlExecutionService sqlExecutionService,
                        FleetService fleetService,
                        DiagnosisService diagnosisService,
                        DataOpsBridge dataOpsBridge,
                        OpsMessages opsMessages,
                        ObjectMapper objectMapper,
                        Messages messages) {
        this.metricService = metricService;
        this.lineageService = lineageService;
        this.taskService = taskService;
        this.sqlExecutionService = sqlExecutionService;
        this.fleetService = fleetService;
        this.diagnosisService = diagnosisService;
        this.dataOpsBridge = dataOpsBridge;
        this.opsMessages = opsMessages;
        this.objectMapper = objectMapper;
        this.messages = messages;
    }

    public AgentReply route(String message) {
        return route(message, new PageContext(null, null, null, null, null), Messages.DEFAULT_LOCALE);
    }

    /** 旧重载：保留行为不变，委托新签名并传默认 locale（中文）。 */
    public AgentReply route(String message, PageContext context) {
        return route(message, context, Messages.DEFAULT_LOCALE);
    }

    /**
     * 消费逐消息页面上下文 + locale：诊断意图优先用上下文中的 instanceId 定位对象，免用户复述（缺口①）；
     * 面向用户的 markdown 文案按 locale 本地化；意图识别关键词中英双语。
     */
    public AgentReply route(String message, PageContext context, Locale locale) {
        String msg = message == null ? "" : message.trim();
        PageContext ctx = context != null ? context : new PageContext(null, null, null, null, null);
        Locale loc = locale != null ? locale : Messages.DEFAULT_LOCALE;
        if (msg.isEmpty()) {
            return fallback(loc);
        }

        // 0a) 诊断意图（优先于血缘，避免"失败"等关键词被吞）
        if (containsAny(msg, "诊断", "为什么失败", "失败原因", "为啥失败", "排查", "跑挂", "挂了", "为什么挂", "报错原因",
                "diagnose", "diagnosis", "why failed", "why did it fail", "why it failed", "root cause",
                "failure reason", "troubleshoot")) {
            return tryDiagnosis(ctx, loc);
        }

        // 0b) 查机器/集群意图
        if (containsAny(msg, "机器", "集群", "节点", "worker", "机器状态", "资源水位", "机器列表", "几台",
                "node", "cluster", "fleet", "machine", "resource usage")) {
            return tryFleet(loc);
        }

        // 0c) 运维意图（data-ops-center Stream C）：查失败实例 / 重跑建议 / 补数据建议
        if (containsAny(msg, "失败实例", "哪些失败了", "最近失败", "失败的实例",
                "failed instances", "what failed", "recent failures",
                "有哪些失败", "失败的有哪些", "show failed")) {
            return tryOpsFailedInstances(loc);
        }
        if (containsAny(msg, "补数据", "backfill", "回补", "重补数据")) {
            return tryOpsBackfillSuggestion(msg, loc);
        }

        // 1) 血缘意图（优先于纯指标查询，因为也含指标名）
        if (containsAny(msg, "血缘", "受哪些表影响", "影响", "上游", "下游", "来源表",
                "lineage", "upstream", "downstream", "source table", "depend on", "depends on", "impact")) {
            AgentReply lineage = tryLineage(msg, loc);
            if (lineage != null) {
                return lineage;
            }
        }

        // 2) 建任务意图
        if (containsAny(msg, "创建任务", "建任务", "建个任务", "新建任务", "创建一个任务",
                "create task", "create a task", "new task", "add task")
                || (msg.contains("任务") && containsAny(msg, "每天", "每日", "执行", "调度", "定时"))
                || (msg.toLowerCase().contains("task")
                    && containsAny(msg, "daily", "every day", "schedule", "scheduled", "run", "cron"))) {
            return createTask(msg, loc);
        }

        // 3) 指标查询（消息含已注册指标名）
        AgentReply metric = tryMetricQuery(msg, loc);
        if (metric != null) {
            return metric;
        }

        // 4) Text-to-SQL（预置问法）
        AgentReply sql = tryTextToSql(msg, loc);
        if (sql != null) {
            return sql;
        }

        // 5) 兜底
        return fallback(loc);
    }

    // ---- 诊断意图 ----
    private AgentReply tryDiagnosis(PageContext ctx, Locale loc) {
        java.util.UUID instanceId = ctx != null ? ctx.instanceIdAsUuid() : null;
        Optional<TaskDiagnosis> opt = instanceId != null
                ? Optional.of(diagnosisService.diagnoseInstance(instanceId, loc))
                : diagnosisService.diagnoseLatestFailure(loc);
        if (opt.isEmpty()) {
            return AgentReply.text(messages.get("agent.diagnosis.none", loc));
        }
        TaskDiagnosis d = opt.get();

        // 解析 suggestionsJson
        List<Map<String, Object>> suggestions = Collections.emptyList();
        if (d.getSuggestionsJson() != null && !d.getSuggestionsJson().isBlank()) {
            try {
                suggestions = objectMapper.readValue(d.getSuggestionsJson(),
                        objectMapper.getTypeFactory().constructCollectionType(List.class,
                                objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class)));
            } catch (Exception ignored) {
                suggestions = Collections.emptyList();
            }
        }

        // 拼 Markdown
        StringBuilder md = new StringBuilder();
        md.append(messages.get("agent.diagnosis.root_cause_heading", loc)).append("\n\n")
                .append(d.getRootCause() != null ? d.getRootCause() : messages.get("agent.diagnosis.unknown", loc))
                .append("\n\n");
        md.append(messages.get("agent.diagnosis.suggestions_heading", loc)).append("\n\n");
        if (suggestions.isEmpty()) {
            md.append(messages.get("agent.diagnosis.no_suggestions", loc)).append("\n");
        } else {
            for (Map<String, Object> s : suggestions) {
                Object label = s.get("label");
                md.append("- ").append(label != null ? label.toString() : s.toString()).append("\n");
            }
        }

        // 结构化结果
        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("kind", "diagnosis");
        structured.put("id", d.getId());
        structured.put("title", d.getTitle());
        structured.put("rootCause", d.getRootCause());
        structured.put("workerNodeCode", d.getWorkerNodeCode());
        structured.put("context", d.getContextJson());
        structured.put("suggestions", suggestions);
        Map<String, Object> uiParams = new LinkedHashMap<>();
        if (d.getTaskInstanceId() != null) {
            uiParams.put("instanceId", d.getTaskInstanceId());
        }
        return new AgentReply(md.toString(), structured, "dataweave.diagnosis")
                .opening("diagnosis", uiParams.isEmpty() ? null : uiParams);
    }

    // ---- 查机器/集群意图 ----
    private AgentReply tryFleet(Locale loc) {
        List<WorkerNode> nodes = fleetService.nodes();
        List<String> columns = List.of("nodeCode", "status", "cpu", "mem", "disk", "loadAvg", "runningTasks");

        // Markdown 表格
        StringBuilder md = new StringBuilder();
        md.append(messages.get("agent.fleet.table_header", loc)).append("\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WorkerNode n : nodes) {
            md.append("| ").append(fmt(n.getNodeCode(), loc))
                    .append(" | ").append(fmt(n.getStatus(), loc))
                    .append(" | ").append(fmtPct(n.getCpu()))
                    .append(" | ").append(fmtPct(n.getMem()))
                    .append(" | ").append(fmtPct(n.getDisk()))
                    .append(" | ").append(fmt(n.getLoadAvg(), loc))
                    .append(" | ").append(fmt(n.getRunningTasks(), loc))
                    .append(" |\n");

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("nodeCode", n.getNodeCode());
            row.put("status", n.getStatus());
            row.put("cpu", n.getCpu());
            row.put("mem", n.getMem());
            row.put("disk", n.getDisk());
            row.put("loadAvg", n.getLoadAvg());
            row.put("runningTasks", n.getRunningTasks());
            rows.add(row);
        }

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("kind", "fleet");
        structured.put("columns", columns);
        structured.put("rows", rows);
        return new AgentReply(md.toString(), structured, "dataweave.fleet").opening("fleet");
    }

    private String fmtPct(Double value) {
        return value == null ? "-" : String.format("%.1f", value);
    }

    // ---- 指标查询 ----
    private AgentReply tryMetricQuery(String msg, Locale loc) {
        // 已注册指标识别：MVP 直接尝试已知指标名 GMV
        for (String name : List.of("GMV")) {
            if (msg.toUpperCase().contains(name)) {
                Optional<AtomicMetric> m = metricService.findLatestByCode(name);
                if (m.isPresent()) {
                    AtomicMetric metric = m.get();
                    Object value = metricService.evaluate(metric);
                    String md = messages.get("agent.metric.value", loc, metric.getName(), fmt(value, loc)) + "\n\n"
                            + messages.get("agent.metric.source_heading", loc) + "\n"
                            + messages.get("agent.metric.source_sql", loc, metric.getMeasureExpr()) + "\n"
                            + messages.get("agent.metric.source_table", loc, metric.getSourceTable()) + "\n"
                            + messages.get("agent.metric.version", loc, metric.getVersionNo());
                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("kind", "metric");
                    structured.put("name", metric.getName());
                    structured.put("value", value);
                    structured.put("exprSql", metric.getMeasureExpr());
                    structured.put("sourceTable", metric.getSourceTable());
                    structured.put("version", metric.getVersionNo());
                    return new AgentReply(md, structured).opening("reports");
                }
            }
        }
        return null;
    }

    // ---- Text-to-SQL ----
    private static final Pattern COUNT_PATTERN =
            Pattern.compile("(多少条|有多少|count|计数|条数|行数|几条|how many|number of|row count)",
                    Pattern.CASE_INSENSITIVE);

    private AgentReply tryTextToSql(String msg, Locale loc) {
        String table = "orders"; // MVP 预置业务表
        String lower = msg.toLowerCase();
        boolean mentionsOrders = lower.contains("orders") || msg.contains("订单");
        boolean mentionsTable = msg.contains("表") || lower.contains("table");
        String generatedSql = null;

        if (COUNT_PATTERN.matcher(msg).find() && (mentionsOrders || mentionsTable)) {
            generatedSql = "select count(*) from " + table;
        } else if (mentionsOrders && containsAny(msg, "查", "看", "列", "明细", "数据", "select",
                "show", "list", "view", "query", "rows", "data")) {
            generatedSql = "select * from " + table;
        }

        if (generatedSql == null) {
            return null;
        }

        String reject = sqlExecutionService.rejectReason(generatedSql);
        if (reject != null) {
            return AgentReply.text(messages.get("agent.sql.rejected", loc, generatedSql, reject));
        }

        QueryResult result = sqlExecutionService.query(generatedSql);
        String md = messages.get("agent.sql.converted", loc) + "\n\n```sql\n" + generatedSql + "\n```\n\n"
                + messages.get("agent.sql.result_heading", loc) + "\n\n"
                + toMarkdownTable(result, loc);

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("kind", "table");
        structured.put("sql", generatedSql);
        structured.put("columns", result.columns());
        structured.put("rows", result.rows());
        return new AgentReply(md, structured).opening("sql-workbench");
    }

    // ---- 建任务 ----
    private static final Pattern HOUR_PATTERN = Pattern.compile(
            "每天\\s*(\\d{1,2})\\s*点|每日\\s*(\\d{1,2})\\s*点"
                    + "|(?:daily|every day)\\s*at\\s*(\\d{1,2})|at\\s*(\\d{1,2})\\s*(?:daily|every day|o'clock)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_CONTENT_PATTERN = Pattern.compile("`([^`]+)`");

    private AgentReply createTask(String msg, Locale loc) {
        // 解析执行小时 -> cron
        int hour = 8;
        Matcher hm = HOUR_PATTERN.matcher(msg);
        if (hm.find()) {
            String h = null;
            for (int g = 1; g <= hm.groupCount(); g++) {
                if (hm.group(g) != null) {
                    h = hm.group(g);
                    break;
                }
            }
            try {
                if (h != null) {
                    hour = Integer.parseInt(h);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        String cron = "0 0 " + hour + " * * ?";

        // 解析执行内容（反引号包裹的 SQL；否则给占位）
        String content;
        Matcher cm = SQL_CONTENT_PATTERN.matcher(msg);
        if (cm.find()) {
            content = cm.group(1);
        } else {
            content = "select count(*) from orders";
        }

        // 任务名：简单从消息派生
        String name = messages.get("agent.task.name_nl", loc, System.currentTimeMillis() % 100000);
        if (msg.contains("GMV")) {
            name = messages.get("agent.task.name_gmv", loc);
        }

        var creation = taskService.createAndOnline(name, "SQL", content, cron);
        TaskDef task = creation.task();

        String md = messages.get("agent.task.created", loc) + "\n"
                + messages.get("agent.task.field_name", loc, task.getName()) + "\n"
                + messages.get("agent.task.field_type", loc, task.getType()) + "\n"
                + messages.get("agent.task.field_cron", loc, creation.cron(), hour) + "\n"
                + messages.get("agent.task.field_content", loc, task.getContent()) + "\n"
                + messages.get("agent.task.field_status", loc, task.getStatus()) + "\n\n"
                + messages.get("agent.task.mock_advanced", loc);

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("kind", "task");
        structured.put("id", task.getId());
        structured.put("name", task.getName());
        structured.put("type", task.getType());
        structured.put("cron", creation.cron());
        structured.put("content", task.getContent());
        structured.put("status", task.getStatus());
        return new AgentReply(md, structured)
                .opening("task-flow", Map.of("highlightTaskId", task.getId()));
    }

    // ---- 血缘 ----
    private AgentReply tryLineage(String msg, Locale loc) {
        for (String name : List.of("GMV")) {
            if (msg.toUpperCase().contains(name)) {
                Optional<LineageService.LineagePath> path = lineageService.lineageOf(name);
                if (path.isPresent()) {
                    AtomicMetric metric = path.get().metric();
                    List<MetricLineage> edges = path.get().edges();

                    StringBuilder md = new StringBuilder();
                    md.append(messages.get("agent.lineage.intro", loc, metric.getName())).append("\n\n");
                    md.append("`").append(metric.getName()).append("`")
                            .append(" → `").append(metric.getMeasureExpr()).append("`");
                    List<Map<String, Object>> rows = new ArrayList<>();
                    for (MetricLineage e : edges) {
                        md.append(" → `").append(e.getDownstreamId())
                                .append("`(").append(e.getDownstreamType()).append(")");
                        Map<String, Object> r = new LinkedHashMap<>();
                        r.put("downstreamType", e.getDownstreamType());
                        r.put("downstreamId", e.getDownstreamId());
                        rows.add(r);
                    }
                    md.append("\n\n").append(messages.get("agent.lineage.affected_tables", loc));
                    if (edges.isEmpty()) {
                        md.append(messages.get("agent.lineage.no_record", loc));
                    } else {
                        for (MetricLineage e : edges) {
                            md.append(" `").append(e.getDownstreamId()).append("`");
                        }
                    }

                    Map<String, Object> structured = new LinkedHashMap<>();
                    structured.put("kind", "lineage");
                    structured.put("metric", metric.getName());
                    structured.put("exprSql", metric.getMeasureExpr());
                    structured.put("columns", List.of("downstreamType", "downstreamId"));
                    structured.put("rows", rows);
                    return new AgentReply(md.toString(), structured).opening("lineage");
                }
            }
        }
        return null;
    }

    // ---- 运维意图 (data-ops-center Stream C) ----

    /** 查询最近失败实例，返回列表 + 重跑建议。 */
    private AgentReply tryOpsFailedInstances(Locale loc) {
        try {
            var q = new InstanceQuery(null, "FAILED", null, null, 1, 10);
            Page<InstanceRow> page = dataOpsBridge.queryInstances(q);
            if (page.items().isEmpty()) {
                return AgentReply.text(opsMessages.get("ops.agent.no_failed", loc));
            }

            StringBuilder md = new StringBuilder();
            md.append(opsMessages.get("ops.agent.failed_instances", loc)).append("\n\n");
            md.append("| 实例 ID | 任务 | 业务日期 | 运行模式 |\n");
            md.append("| --- | --- | --- | --- |\n");
            List<Map<String, Object>> rows = new ArrayList<>();
            for (InstanceRow r : page.items()) {
                String idShort = r.id().toString().substring(0, 8);
                md.append("| ").append(idShort).append("...")
                        .append(" | ").append(r.taskDefName() != null ? r.taskDefName() : "task-" + r.taskDefId())
                        .append(" | ").append(r.bizDate() != null ? r.bizDate() : "-")
                        .append(" | ").append(r.runMode())
                        .append(" |\n");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", r.id().toString());
                row.put("taskDefId", r.taskDefId());
                row.put("taskDefName", r.taskDefName());
                row.put("bizDate", r.bizDate());
                row.put("runMode", r.runMode());
                rows.add(row);
            }

            md.append("\n").append(opsMessages.get("ops.agent.rerun_suggestion", loc)).append("\n");
            for (InstanceRow r : page.items()) {
                md.append("- #").append(r.id().toString().substring(0, 8)).append("...")
                        .append(" (").append(r.taskDefName() != null ? r.taskDefName() : "task-" + r.taskDefId())
                        .append(")\n");
            }

            Map<String, Object> structured = new LinkedHashMap<>();
            structured.put("kind", "failed-instances");
            structured.put("total", page.total());
            structured.put("rows", rows);
            structured.put("suggestedAction", Map.of("op", "rerun"));

            return new AgentReply(md.toString(), structured)
                    .opening("ops", Map.of("tab", "instances", "filter", Map.of("state", "FAILED")));
        } catch (Exception e) {
            return AgentReply.text(opsMessages.get("ops.agent.no_failed", loc));
        }
    }

    /** 补数据建议：从消息解析日期区间，给出 backfill 建议。 */
    private AgentReply tryOpsBackfillSuggestion(String msg, Locale loc) {
        // 尝试从消息中提取日期区间（yyyy-MM-dd 格式）
        Pattern datePattern = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})");
        Matcher m = datePattern.matcher(msg);
        String dateStart = null;
        String dateEnd = null;
        List<String> dates = new ArrayList<>();
        while (m.find()) {
            dates.add(m.group(1));
        }
        if (dates.size() >= 2) {
            dateStart = dates.get(0);
            dateEnd = dates.get(1);
        } else if (dates.size() == 1) {
            dateStart = dates.get(0);
            dateEnd = dates.get(0);
        }

        StringBuilder md = new StringBuilder();
        md.append(opsMessages.get("ops.agent.backfill_suggestion", loc)).append("\n\n");

        if (dateStart != null) {
            md.append("- ").append(opsMessages.get("ops.agent.failed_instances", loc))
                    .append(": ").append(dateStart).append(" ~ ").append(dateEnd).append("\n");
            md.append("- 并发度建议: 1\n");
        } else {
            md.append("- 请指定补数据的日期范围，例如：补数据 2026-06-20 ~ 2026-06-23\n");
            md.append("- 并发度建议: 1（默认）\n");
        }

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("kind", "backfill-suggestion");
        structured.put("dateStart", dateStart);
        structured.put("dateEnd", dateEnd);
        structured.put("parallelism", 1);

        Map<String, Object> uiParams = new LinkedHashMap<>();
        uiParams.put("tab", "backfill");
        if (dateStart != null) {
            uiParams.put("dateStart", dateStart);
            uiParams.put("dateEnd", dateEnd);
        }
        return new AgentReply(md.toString(), structured)
                .opening("ops", uiParams);
    }

    // ---- 兜底 ----
    private AgentReply fallback(Locale loc) {
        String md = messages.get("agent.help.intro", loc) + "\n\n"
                + messages.get("agent.help.diagnosis", loc) + "\n"
                + messages.get("agent.help.fleet", loc) + "\n"
                + messages.get("agent.help.metric", loc) + "\n"
                + messages.get("agent.help.sql", loc) + "\n"
                + messages.get("agent.help.task", loc) + "\n"
                + messages.get("agent.help.lineage", loc) + "\n\n"
                + messages.get("agent.help.retry", loc);
        return AgentReply.text(md);
    }

    // ---- helpers ----
    private boolean containsAny(String s, String... keys) {
        String lower = s.toLowerCase();
        for (String k : keys) {
            // 中文 key 大小写无关；英文 key 统一用小写匹配（关键词词典已全小写）
            if (k.equals(k.toLowerCase()) ? lower.contains(k) : s.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private String fmt(Object value, Locale loc) {
        return value == null ? messages.get("agent.common.no_data", loc) : value.toString();
    }

    private String toMarkdownTable(QueryResult result, Locale loc) {
        if (result.columns().isEmpty()) {
            return messages.get("agent.sql.empty_result", loc);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("| ").append(String.join(" | ", result.columns())).append(" |\n");
        sb.append("| ").append(result.columns().stream().map(c -> "---").reduce((a, b) -> a + " | " + b).orElse("---")).append(" |\n");
        for (Map<String, Object> row : result.rows()) {
            sb.append("| ");
            List<String> cells = new ArrayList<>();
            for (String col : result.columns()) {
                Object v = row.get(col);
                cells.add(v == null ? "" : v.toString());
            }
            sb.append(String.join(" | ", cells)).append(" |\n");
        }
        return sb.toString();
    }
}
