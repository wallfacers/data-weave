package com.dataweave.api.application;

import com.dataweave.api.interfaces.dto.PageContext;
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
import com.dataweave.master.domain.WorkerNode;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则式意图路由器（mock Agent 引擎核心）。
 *
 * <p>把用户最后一条消息按关键词路由到 5 种意图：指标查询 / Text-to-SQL / 建任务 / 血缘 / 兜底。
 * 命中后调对应领域服务，产出 {@link AgentReply}（Markdown 文本 + 可选结构化结果）。
 *
 * <p>真模型接缝：后期用 {@link LlmClient} 做意图分类 + 槽位抽取 + SQL 生成，替换这里的关键词规则，
 * 编排器与领域服务调用方式不变。
 */
@Component
public class IntentRouter {

    private final MetricService metricService;
    private final LineageService lineageService;
    private final TaskService taskService;
    private final SqlExecutionService sqlExecutionService;
    @SuppressWarnings("unused")
    private final LlmClient llmClient; // 预留：后期做真实意图识别/SQL 生成
    private final FleetService fleetService;
    private final DiagnosisService diagnosisService;
    private final ObjectMapper objectMapper;

    public IntentRouter(MetricService metricService,
                        LineageService lineageService,
                        TaskService taskService,
                        SqlExecutionService sqlExecutionService,
                        LlmClient llmClient,
                        FleetService fleetService,
                        DiagnosisService diagnosisService,
                        ObjectMapper objectMapper) {
        this.metricService = metricService;
        this.lineageService = lineageService;
        this.taskService = taskService;
        this.sqlExecutionService = sqlExecutionService;
        this.llmClient = llmClient;
        this.fleetService = fleetService;
        this.diagnosisService = diagnosisService;
        this.objectMapper = objectMapper;
    }

    public AgentReply route(String message) {
        return route(message, new PageContext(null, null, null, null, null));
    }

    /** 消费逐消息页面上下文：诊断意图优先用上下文中的 instanceId 定位对象，免用户复述（缺口①）。 */
    public AgentReply route(String message, PageContext context) {
        String msg = message == null ? "" : message.trim();
        PageContext ctx = context != null ? context : new PageContext(null, null, null, null, null);
        if (msg.isEmpty()) {
            return fallback();
        }

        // 0a) 诊断意图（优先于血缘，避免"失败"等关键词被吞）
        if (containsAny(msg, "诊断", "为什么失败", "失败原因", "为啥失败", "排查", "跑挂", "挂了", "为什么挂", "报错原因")) {
            return tryDiagnosis(ctx);
        }

        // 0b) 查机器/集群意图
        if (containsAny(msg, "机器", "集群", "节点", "worker", "机器状态", "资源水位", "机器列表", "几台")) {
            return tryFleet();
        }

        // 1) 血缘意图（优先于纯指标查询，因为也含指标名）
        if (containsAny(msg, "血缘", "受哪些表影响", "影响", "上游", "下游", "来源表")) {
            AgentReply lineage = tryLineage(msg);
            if (lineage != null) {
                return lineage;
            }
        }

        // 2) 建任务意图
        if (containsAny(msg, "创建任务", "建任务", "建个任务", "新建任务", "创建一个任务")
                || (msg.contains("任务") && containsAny(msg, "每天", "每日", "执行", "调度", "定时"))) {
            return createTask(msg);
        }

        // 3) 指标查询（消息含已注册指标名）
        AgentReply metric = tryMetricQuery(msg);
        if (metric != null) {
            return metric;
        }

        // 4) Text-to-SQL（预置问法）
        AgentReply sql = tryTextToSql(msg);
        if (sql != null) {
            return sql;
        }

        // 5) 兜底
        return fallback();
    }

    // ---- 诊断意图 ----
    private AgentReply tryDiagnosis(PageContext ctx) {
        java.util.UUID instanceId = ctx != null ? ctx.instanceIdAsUuid() : null;
        Optional<TaskDiagnosis> opt = instanceId != null
                ? Optional.of(diagnosisService.diagnoseInstance(instanceId))
                : diagnosisService.diagnoseLatestFailure();
        if (opt.isEmpty()) {
            return AgentReply.text("当前没有失败的任务实例可诊断。");
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
        md.append("## 根因\n\n").append(d.getRootCause() != null ? d.getRootCause() : "(未知)").append("\n\n");
        md.append("### 修复建议\n\n");
        if (suggestions.isEmpty()) {
            md.append("（暂无建议）\n");
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
    private AgentReply tryFleet() {
        List<WorkerNode> nodes = fleetService.nodes();
        List<String> columns = List.of("nodeCode", "status", "cpu", "mem", "disk", "loadAvg", "runningTasks");

        // Markdown 表格
        StringBuilder md = new StringBuilder();
        md.append("| 节点 | 状态 | CPU% | 内存% | 磁盘% | load | 运行任务 |\n");
        md.append("| --- | --- | --- | --- | --- | --- | --- |\n");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (WorkerNode n : nodes) {
            md.append("| ").append(fmt(n.getNodeCode()))
                    .append(" | ").append(fmt(n.getStatus()))
                    .append(" | ").append(fmtPct(n.getCpu()))
                    .append(" | ").append(fmtPct(n.getMem()))
                    .append(" | ").append(fmtPct(n.getDisk()))
                    .append(" | ").append(fmt(n.getLoadAvg()))
                    .append(" | ").append(fmt(n.getRunningTasks()))
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
    private AgentReply tryMetricQuery(String msg) {
        // 已注册指标识别：MVP 直接尝试已知指标名 GMV
        for (String name : List.of("GMV")) {
            if (msg.toUpperCase().contains(name)) {
                Optional<AtomicMetric> m = metricService.findLatestByCode(name);
                if (m.isPresent()) {
                    AtomicMetric metric = m.get();
                    Object value = metricService.evaluate(metric);
                    String md = "指标 **" + metric.getName() + "** 的当前值为 **" + fmt(value) + "**。\n\n"
                            + "口径溯源：\n"
                            + "- 口径 SQL：`" + metric.getMeasureExpr() + "`\n"
                            + "- 来源表：`" + metric.getSourceTable() + "`\n"
                            + "- 版本：v" + metric.getVersionNo();
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
            Pattern.compile("(多少条|有多少|count|计数|条数|行数|几条)", Pattern.CASE_INSENSITIVE);

    private AgentReply tryTextToSql(String msg) {
        String table = "orders"; // MVP 预置业务表
        boolean mentionsOrders = msg.toLowerCase().contains("orders") || msg.contains("订单");
        String generatedSql = null;

        if (COUNT_PATTERN.matcher(msg).find() && (mentionsOrders || msg.contains("表"))) {
            generatedSql = "select count(*) from " + table;
        } else if (mentionsOrders && containsAny(msg, "查", "看", "列", "明细", "数据", "select")) {
            generatedSql = "select * from " + table;
        }

        if (generatedSql == null) {
            return null;
        }

        String reject = sqlExecutionService.rejectReason(generatedSql);
        if (reject != null) {
            return AgentReply.text("已生成 SQL：`" + generatedSql + "`，但未通过只读校验：" + reject);
        }

        QueryResult result = sqlExecutionService.query(generatedSql);
        String md = "已将问题转换为 SQL：\n\n```sql\n" + generatedSql + "\n```\n\n执行结果：\n\n"
                + toMarkdownTable(result);

        Map<String, Object> structured = new LinkedHashMap<>();
        structured.put("kind", "table");
        structured.put("sql", generatedSql);
        structured.put("columns", result.columns());
        structured.put("rows", result.rows());
        return new AgentReply(md, structured).opening("sql-workbench");
    }

    // ---- 建任务 ----
    private static final Pattern HOUR_PATTERN = Pattern.compile("每天\\s*(\\d{1,2})\\s*点|每日\\s*(\\d{1,2})\\s*点");
    private static final Pattern SQL_CONTENT_PATTERN = Pattern.compile("`([^`]+)`");

    private AgentReply createTask(String msg) {
        // 解析执行小时 -> cron
        int hour = 8;
        Matcher hm = HOUR_PATTERN.matcher(msg);
        if (hm.find()) {
            String h = hm.group(1) != null ? hm.group(1) : hm.group(2);
            try {
                hour = Integer.parseInt(h);
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
        String name = "自然语言任务-" + System.currentTimeMillis() % 100000;
        if (msg.contains("GMV")) {
            name = "GMV 统计任务";
        }

        var creation = taskService.createAndOnline(name, "SQL", content, cron);
        TaskDef task = creation.task();

        String md = "已创建并上线任务：\n"
                + "- 任务名：**" + task.getName() + "**\n"
                + "- 类型：`" + task.getType() + "`\n"
                + "- 调度（cron）：`" + creation.cron() + "`（每天 " + hour + ":00）\n"
                + "- 执行内容：`" + task.getContent() + "`\n"
                + "- 状态：**" + task.getStatus() + "**\n\n"
                + "已 mock 推进一条运行实例至 SUCCESS。";

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
    private AgentReply tryLineage(String msg) {
        for (String name : List.of("GMV")) {
            if (msg.toUpperCase().contains(name)) {
                Optional<LineageService.LineagePath> path = lineageService.lineageOf(name);
                if (path.isPresent()) {
                    AtomicMetric metric = path.get().metric();
                    List<MetricLineage> edges = path.get().edges();

                    StringBuilder md = new StringBuilder();
                    md.append("指标 **").append(metric.getName()).append("** 的影响链路（指标 → SQL → 物理表）：\n\n");
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
                    md.append("\n\n受影响的物理表：");
                    if (edges.isEmpty()) {
                        md.append("（无记录）");
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

    // ---- 兜底 ----
    private AgentReply fallback() {
        String md = "我是 DataWeave Agent（当前为 MVP 规则 mock 引擎）。我现在支持以下问法：\n\n"
                + "- **诊断**：如「帮我诊断为什么失败」——分析最近失败实例并给出修复建议。\n"
                + "- **查机器**：如「看看集群机器状态」——列出 worker 节点与资源水位。\n"
                + "- **指标查询**：如「GMV 是多少」——返回指标值与口径溯源。\n"
                + "- **Text-to-SQL**：如「orders 表有多少条」「查一下 orders」——生成只读 SQL 并返回表格。\n"
                + "- **建任务**：如「创建一个任务，每天 8 点执行 `select count(*) from orders`」——建任务并上线。\n"
                + "- **血缘问答**：如「GMV 受哪些表影响」——返回「指标 → SQL → 物理表」链路。\n\n"
                + "请换一种上述问法再试。";
        return AgentReply.text(md);
    }

    // ---- helpers ----
    private boolean containsAny(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) {
                return true;
            }
        }
        return false;
    }

    private String fmt(Object value) {
        return value == null ? "(无数据)" : value.toString();
    }

    private String toMarkdownTable(QueryResult result) {
        if (result.columns().isEmpty()) {
            return "(空结果)";
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
