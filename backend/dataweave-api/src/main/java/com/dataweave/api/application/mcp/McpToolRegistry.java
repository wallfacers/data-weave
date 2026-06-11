package com.dataweave.api.application.mcp;

import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.ApprovalService;
import com.dataweave.master.application.DiagnosisService;
import com.dataweave.master.application.FleetService;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.application.LineageService;
import com.dataweave.master.application.MetricService;
import com.dataweave.master.application.OpsService;
import com.dataweave.master.application.TaskService;
import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.domain.AtomicMetric;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskDiagnosis;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 平台工具注册中心：平台能力的唯一真相源。查询工具直通 master 领域服务（与观测页 REST 同源）；
 * 写工具（create_task/task_rerun/node_exec）全部经 {@link GatedActionService} 闸门，无绕过路径。
 *
 * <p>输出超阈值时截断并附标记，完整输出存档到本地文件（M2 迁 MinIO），返回引用。
 */
@Component
public class McpToolRegistry {

    private final TaskDefRepository taskDefRepository;
    private final TaskInstanceRepository instanceRepository;
    private final FleetService fleetService;
    private final MetricService metricService;
    private final LineageService lineageService;
    private final DiagnosisService diagnosisService;
    private final GatedActionService gatedActionService;
    private final ApprovalService approvalService;
    private final TaskService taskService;
    private final OpsService opsService;
    private final ObjectMapper objectMapper;
    private final int outputThreshold;
    private final Path archiveDir;

    private final Map<String, McpTool> tools = new LinkedHashMap<>();

    public McpToolRegistry(TaskDefRepository taskDefRepository,
                           TaskInstanceRepository instanceRepository,
                           FleetService fleetService,
                           MetricService metricService,
                           LineageService lineageService,
                           DiagnosisService diagnosisService,
                           GatedActionService gatedActionService,
                           ApprovalService approvalService,
                           TaskService taskService,
                           OpsService opsService,
                           ObjectMapper objectMapper,
                           @Value("${mcp.output-threshold:8000}") int outputThreshold,
                           @Value("${mcp.archive-dir:${java.io.tmpdir}/dataweave-tool-outputs}") String archiveDir) {
        this.taskDefRepository = taskDefRepository;
        this.instanceRepository = instanceRepository;
        this.fleetService = fleetService;
        this.metricService = metricService;
        this.lineageService = lineageService;
        this.diagnosisService = diagnosisService;
        this.gatedActionService = gatedActionService;
        this.approvalService = approvalService;
        this.taskService = taskService;
        this.opsService = opsService;
        this.objectMapper = objectMapper;
        this.outputThreshold = outputThreshold;
        this.archiveDir = Path.of(archiveDir);
        registerTools();
    }

    public List<McpTool> list() {
        return new ArrayList<>(tools.values());
    }

    public boolean has(String name) {
        return tools.containsKey(name);
    }

    /** 执行工具：序列化结果 → 截断 + 存档。未知工具/异常 → isError 结果。 */
    public ToolResult call(String name, Map<String, Object> args) {
        McpTool tool = tools.get(name);
        if (tool == null) {
            return ToolResult.error("未知工具：" + name);
        }
        try {
            Object payload = tool.handler().apply(args == null ? Map.of() : args);
            String text = payload instanceof String s ? s : objectMapper.writeValueAsString(payload);
            return truncateAndArchive(name, text);
        } catch (IllegalArgumentException e) {
            return ToolResult.error("参数错误：" + e.getMessage());
        } catch (Exception e) {
            return ToolResult.error("工具执行失败：" + e.getMessage());
        }
    }

    // ============================ 工具注册 ============================

    private void registerTools() {
        // ---- 查询工具（L0，直通领域服务）----
        register("query_task_definitions", "列出全部任务定义（与任务管理页 REST 同源）",
                schema(), args -> {
                    List<TaskDef> out = new ArrayList<>();
                    taskDefRepository.findAll().forEach(t -> {
                        if (t.getDeleted() == null || t.getDeleted() == 0) {
                            out.add(t);
                        }
                    });
                    return out;
                });

        register("query_task_instances", "列出任务实例，可选按 state 过滤（FAILED/SUCCESS/RUNNING…）",
                schema(prop("state", "string", "运行状态，可选")), args -> {
                    String state = str(args, "state");
                    List<TaskInstance> out = new ArrayList<>();
                    if (state != null && !state.isBlank()) {
                        out.addAll(instanceRepository.findByState(state));
                    } else {
                        instanceRepository.findAll().forEach(out::add);
                    }
                    return out;
                });

        register("query_fleet", "列出 worker 机器集群与资源水位",
                schema(), args -> fleetService.nodes());

        register("query_metric", "按指标 code 返回当前值与口径溯源",
                schema(req("code", "string", "指标 code，如 GMV")), args -> {
                    String code = required(args, "code");
                    Optional<AtomicMetric> m = metricService.findLatestByCode(code);
                    if (m.isEmpty()) {
                        return Map.of("found", false, "code", code);
                    }
                    AtomicMetric metric = m.get();
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("found", true);
                    r.put("name", metric.getName());
                    r.put("value", metricService.evaluate(metric));
                    r.put("exprSql", metric.getMeasureExpr());
                    r.put("sourceTable", metric.getSourceTable());
                    r.put("version", metric.getVersionNo());
                    return r;
                });

        register("query_lineage", "按指标 code 返回血缘链路（指标 → SQL → 物理表）",
                schema(req("code", "string", "指标 code，如 GMV")), args -> {
                    String code = required(args, "code");
                    return lineageService.lineageOf(code)
                            .map(p -> (Object) Map.of(
                                    "metric", p.metric().getName(),
                                    "exprSql", p.metric().getMeasureExpr(),
                                    "edges", p.edges()))
                            .orElse(Map.of("found", false, "code", code));
                });

        register("query_diagnosis", "诊断指定失败实例（缺省诊断最近一条失败实例）",
                schema(prop("instanceId", "string", "任务实例 id（UUID），可选")), args -> {
                    java.util.UUID instanceId = uuid(args, "instanceId");
                    Optional<TaskDiagnosis> d = instanceId != null
                            ? Optional.of(diagnosisService.diagnoseInstance(instanceId))
                            : diagnosisService.diagnoseLatestFailure();
                    return d.map(x -> (Object) x).orElse(Map.of("found", false));
                });

        // ---- 写工具（全部经 PolicyEngine 闸门）----
        register("create_task", "创建并上线一个任务（经策略闸门）",
                schema(req("name", "string", "任务名"),
                        req("content", "string", "执行内容（SQL）"),
                        prop("cron", "string", "cron 表达式，缺省每天 8 点")),
                args -> {
                    String name = required(args, "name");
                    String content = required(args, "content");
                    String cron = str(args, "cron");
                    if (cron == null || cron.isBlank()) {
                        cron = "0 0 8 * * ?";
                    }
                    ActionRequest req = ActionRequest.builder()
                            .toolName("create_task").actionType("CREATE_TASK")
                            .targetType("TASK").targetId(name)
                            .command(cron + "\n" + content)
                            .actor("agent").actorSource("AGENT")
                            .summary("建任务「" + name + "」 cron " + cron)
                            .build();
                    return gateText(gatedActionService.submit(req));
                });

        register("task_rerun", "重跑一个任务实例（经策略闸门）",
                schema(req("instanceId", "string", "任务实例 id（UUID）")),
                args -> {
                    java.util.UUID instanceId = requiredUuid(args, "instanceId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("task_rerun").actionType("TASK_RERUN")
                            .targetType("TASK_INSTANCE").targetId(String.valueOf(instanceId))
                            .actor("agent").actorSource("AGENT")
                            .summary("重跑实例 #" + instanceId)
                            .build();
                    return gateText(gatedActionService.submit(req));
                });

        register("node_exec", "在指定节点执行受控命令（白名单前缀 + 命令串安全解析，经策略闸门）",
                schema(req("nodeCode", "string", "目标节点 code"),
                        req("command", "string", "命令串（白名单前缀）")),
                args -> {
                    String nodeCode = required(args, "nodeCode");
                    String command = required(args, "command");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("node_exec").actionType("NODE_EXEC")
                            .targetType("NODE").targetId(nodeCode)
                            .command(command)
                            .actor("agent").actorSource("AGENT")
                            .summary("在 " + nodeCode + " 执行：" + command)
                            .build();
                    return gateText(gatedActionService.submit(req));
                });

        // ---- 审批续做：观察审批单状态/结果（不自行批准，人审在审批卡片）----
        register("approve_and_execute", "查看审批单状态与执行结果（人工批准在右舷审批卡片完成；此工具用于续做时确认结果）",
                schema(req("approvalId", "integer", "审批单 id")),
                args -> {
                    Long id = requiredLong(args, "approvalId");
                    Optional<AgentAction> a = approvalService.get(id);
                    if (a.isEmpty()) {
                        return Map.of("found", false, "approvalId", id);
                    }
                    AgentAction action = a.get();
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("approvalId", id);
                    r.put("status", action.getApprovalStatus());
                    r.put("level", action.getPolicyLevel());
                    r.put("summary", action.getSummary());
                    r.put("result", action.getResultJson());
                    if ("PENDING".equals(action.getApprovalStatus())) {
                        r.put("note", "仍待人工批准（右舷审批卡片）");
                    }
                    return r;
                });

        // ---- CRUD 工具（经策略闸门 L1）----
        register("update_task", "更新任务定义（仅 DRAFT 状态可改，经策略闸门）",
                schema(req("taskId", "integer", "任务 id"),
                        prop("name", "string", "新名称"),
                        prop("content", "string", "新内容（SQL）"),
                        prop("description", "string", "任务描述")),
                args -> {
                    Long taskId = requiredLong(args, "taskId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("update_task").actionType("UPDATE_TASK")
                            .targetType("TASK").targetId(String.valueOf(taskId))
                            .actor("agent").actorSource("AGENT")
                            .summary("更新任务 #" + taskId)
                            .build();
                    GateResult gr = gatedActionService.submit(req);
                    if (gr.pending() || "DENIED".equals(gr.outcome().name())) return gateText(gr);
                    TaskDef patch = new TaskDef();
                    if (str(args, "name") != null) patch.setName(str(args, "name"));
                    if (str(args, "content") != null) patch.setContent(str(args, "content"));
                    if (str(args, "description") != null) patch.setDescription(str(args, "description"));
                    return taskService.update(taskId, patch);
                });

        register("delete_task", "软删除任务（仅 DRAFT 可删，经策略闸门）",
                schema(req("taskId", "integer", "任务 id")),
                args -> {
                    Long taskId = requiredLong(args, "taskId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("delete_task").actionType("DELETE_TASK")
                            .targetType("TASK").targetId(String.valueOf(taskId))
                            .actor("agent").actorSource("AGENT")
                            .summary("删除任务 #" + taskId)
                            .build();
                    GateResult gr = gatedActionService.submit(req);
                    if (gr.pending() || "DENIED".equals(gr.outcome().name())) return gateText(gr);
                    taskService.softDelete(taskId);
                    return Map.of("deleted", true, "taskId", taskId);
                });

        register("pause_instance", "暂停工作流实例（经策略闸门）",
                schema(req("instanceId", "string", "工作流实例 id（UUID）")),
                args -> {
                    java.util.UUID instanceId = requiredUuid(args, "instanceId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("pause_instance").actionType("PAUSE_INSTANCE")
                            .targetType("WORKFLOW_INSTANCE").targetId(String.valueOf(instanceId))
                            .actor("agent").actorSource("AGENT")
                            .summary("暂停实例 #" + instanceId)
                            .build();
                    GateResult gr = gatedActionService.submit(req);
                    if (gr.pending() || "DENIED".equals(gr.outcome().name())) return gateText(gr);
                    return opsService.pauseWorkflow(instanceId);
                });

        register("resume_instance", "恢复工作流实例（经策略闸门）",
                schema(req("instanceId", "string", "工作流实例 id（UUID）")),
                args -> {
                    java.util.UUID instanceId = requiredUuid(args, "instanceId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("resume_instance").actionType("RESUME_INSTANCE")
                            .targetType("WORKFLOW_INSTANCE").targetId(String.valueOf(instanceId))
                            .actor("agent").actorSource("AGENT")
                            .summary("恢复实例 #" + instanceId)
                            .build();
                    GateResult gr = gatedActionService.submit(req);
                    if (gr.pending() || "DENIED".equals(gr.outcome().name())) return gateText(gr);
                    return opsService.resumeWorkflow(instanceId);
                });

        register("kill_instance", "终止工作流实例（经策略闸门）",
                schema(req("instanceId", "string", "工作流实例 id（UUID）")),
                args -> {
                    java.util.UUID instanceId = requiredUuid(args, "instanceId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("kill_instance").actionType("KILL_INSTANCE")
                            .targetType("WORKFLOW_INSTANCE").targetId(String.valueOf(instanceId))
                            .actor("agent").actorSource("AGENT")
                            .summary("终止实例 #" + instanceId)
                            .build();
                    GateResult gr = gatedActionService.submit(req);
                    if (gr.pending() || "DENIED".equals(gr.outcome().name())) return gateText(gr);
                    return opsService.killWorkflow(instanceId);
                });

        // ---- 调度类写工具（distributed-scheduler-m1，全部经闸门）----
        register("test_run", "单任务测试运行：下发草稿内容到 worker，不入正式统计（经策略闸门，留痕）",
                schema(req("taskId", "integer", "任务定义 id"),
                        prop("bizDate", "string", "业务日期 yyyy-MM-dd，可选")),
                args -> {
                    Long taskId = requiredLong(args, "taskId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("test_run").actionType("TEST_RUN")
                            .targetType("TASK").targetId(String.valueOf(taskId))
                            .command(str(args, "bizDate"))
                            .actor("agent").actorSource("AGENT")
                            .summary("测试运行任务 #" + taskId)
                            .build();
                    return gateText(gatedActionService.submit(req));
                });

        register("trigger_workflow", "手动触发工作流执行（经策略闸门）",
                schema(req("workflowId", "integer", "工作流定义 id"),
                        prop("bizDate", "string", "业务日期 yyyy-MM-dd，可选")),
                args -> {
                    Long workflowId = requiredLong(args, "workflowId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("trigger_workflow").actionType("TRIGGER_WORKFLOW")
                            .targetType("WORKFLOW").targetId(String.valueOf(workflowId))
                            .command(str(args, "bizDate"))
                            .actor("agent").actorSource("AGENT")
                            .summary("手动触发工作流 #" + workflowId)
                            .build();
                    return gateText(gatedActionService.submit(req));
                });

        register("resume_workflow", "断点恢复失败的工作流实例：保留成功节点，从失败点续跑（经策略闸门）",
                schema(req("instanceId", "string", "工作流实例 id（UUID）")),
                args -> {
                    java.util.UUID instanceId = requiredUuid(args, "instanceId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("resume_workflow").actionType("RESUME_WORKFLOW")
                            .targetType("WORKFLOW_INSTANCE").targetId(instanceId.toString())
                            .actor("agent").actorSource("AGENT")
                            .summary("断点恢复实例 " + instanceId)
                            .build();
                    return gateText(gatedActionService.submit(req));
                });

        register("rerun_workflow", "整流重跑工作流实例：全节点重置后重跑（经策略闸门）",
                schema(req("instanceId", "string", "工作流实例 id（UUID）")),
                args -> {
                    java.util.UUID instanceId = requiredUuid(args, "instanceId");
                    ActionRequest req = ActionRequest.builder()
                            .toolName("rerun_workflow").actionType("RERUN_WORKFLOW")
                            .targetType("WORKFLOW_INSTANCE").targetId(instanceId.toString())
                            .actor("agent").actorSource("AGENT")
                            .summary("整流重跑实例 " + instanceId)
                            .build();
                    return gateText(gatedActionService.submit(req));
                });
    }

    private Object gateText(GateResult gr) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("outcome", gr.outcome().name());
        r.put("level", gr.level());
        r.put("message", gr.message());
        if (gr.pending()) {
            r.put("approvalId", gr.actionId());
            r.put("requiresConfirmation", gr.requiresConfirmation());
            r.put("summary", gr.summary());
        }
        if (gr.resultInstanceId() != null) {
            r.put("resultInstanceId", gr.resultInstanceId());
        }
        return r;
    }

    // ============================ 截断 + 存档 ============================

    private ToolResult truncateAndArchive(String toolName, String text) {
        if (text == null) {
            return ToolResult.ok("");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= outputThreshold) {
            return ToolResult.ok(text);
        }
        String ref = archive(toolName, text);
        String head = text.substring(0, Math.min(text.length(), outputThreshold));
        String marked = head + "\n\n[truncated] 原始 " + bytes.length + " 字节，完整输出存档："
                + (ref != null ? ref : "(存档失败)");
        return new ToolResult(marked, false, true, ref);
    }

    private String archive(String toolName, String text) {
        try {
            Files.createDirectories(archiveDir);
            Path file = archiveDir.resolve(toolName + "-" + Integer.toHexString(text.hashCode()) + ".txt");
            Files.writeString(file, text, StandardCharsets.UTF_8);
            return file.toString();
        } catch (IOException e) {
            return null;
        }
    }

    // ============================ helpers ============================

    private void register(String name, String description, Map<String, Object> schema,
                          java.util.function.Function<Map<String, Object>, Object> handler) {
        tools.put(name, new McpTool(name, description, schema, handler));
    }

    private Map<String, Object> schema(Map<String, Object>... props) {
        Map<String, Object> properties = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Map<String, Object> p : props) {
            String name = (String) p.get("__name");
            boolean isRequired = Boolean.TRUE.equals(p.get("__required"));
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("type", p.get("type"));
            def.put("description", p.get("description"));
            properties.put(name, def);
            if (isRequired) {
                required.add(name);
            }
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private Map<String, Object> prop(String name, String type, String description) {
        return field(name, type, description, false);
    }

    private Map<String, Object> req(String name, String type, String description) {
        return field(name, type, description, true);
    }

    private Map<String, Object> field(String name, String type, String description, boolean required) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("__name", name);
        m.put("__required", required);
        m.put("type", type);
        m.put("description", description);
        return m;
    }

    private String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : v.toString();
    }

    private String required(Map<String, Object> args, String key) {
        String v = str(args, key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("缺少必填参数 " + key);
        }
        return v;
    }

    private Long lng(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(v.toString().trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " 必须为整数");
        }
    }

    private Long requiredLong(Map<String, Object> args, String key) {
        Long v = lng(args, key);
        if (v == null) {
            throw new IllegalArgumentException("缺少必填参数 " + key);
        }
        return v;
    }

    private java.util.UUID uuid(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v == null) {
            return null;
        }
        try {
            return java.util.UUID.fromString(v.toString().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(key + " 必须为 UUID");
        }
    }

    private java.util.UUID requiredUuid(Map<String, Object> args, String key) {
        java.util.UUID v = uuid(args, key);
        if (v == null) {
            throw new IllegalArgumentException("缺少必填参数 " + key);
        }
        return v;
    }
}
