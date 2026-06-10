package com.dataweave.master.application;

import com.dataweave.master.domain.AgentAction;
import com.dataweave.master.domain.TaskDiagnosis;
import com.dataweave.master.domain.TaskDiagnosisRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.domain.WorkerNode;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 默认平台动作执行器：按 action_type 执行 applyFix 四动作、任务重跑、节点受控执行。
 *
 * <p>node_exec 委托 {@link NodeExecGateway}（实现在 api 模块，section 3 接线；缺失时返回明确错误）。
 */
@Component
public class DefaultPlatformActionExecutor implements PlatformActionExecutor {

    private final TaskInstanceRepository instanceRepository;
    private final TaskDiagnosisRepository diagnosisRepository;
    private final FleetService fleetService;
    private final TaskService taskService;
    private final ObjectProvider<NodeExecGateway> nodeExecGateway;

    public DefaultPlatformActionExecutor(TaskInstanceRepository instanceRepository,
                                         TaskDiagnosisRepository diagnosisRepository,
                                         FleetService fleetService,
                                         TaskService taskService,
                                         ObjectProvider<NodeExecGateway> nodeExecGateway) {
        this.instanceRepository = instanceRepository;
        this.diagnosisRepository = diagnosisRepository;
        this.fleetService = fleetService;
        this.taskService = taskService;
        this.nodeExecGateway = nodeExecGateway;
    }

    @Override
    public ExecOutcome execute(AgentAction action) {
        String type = action.getActionType() == null ? "" : action.getActionType().toUpperCase();
        return switch (type) {
            case "APPLY_FIX_RERUN" -> rerun(action, null, "[fix] 原地重跑成功", "已原地重跑，运行成功。");
            case "APPLY_FIX_MIGRATE_NODE" -> migrate(action);
            case "APPLY_FIX_RERUN_MORE_MEMORY" -> rerunMoreMemory(action);
            case "APPLY_FIX_CAP_NODE_WEIGHT" -> capNodeWeight(action);
            case "TASK_RERUN" -> taskRerun(action);
            case "CREATE_TASK" -> createTask(action);
            case "NODE_EXEC" -> nodeExec(action);
            default -> new ExecOutcome(false, "不支持的动作类型：" + action.getActionType(),
                    json(Map.of("error", "unsupported_action", "actionType", action.getActionType())), null);
        };
    }

    // ---- applyFix / task rerun ----
    private ExecOutcome rerun(AgentAction action, String nodeOverride, String log, String message) {
        TaskDiagnosis diagnosis = diagnosisOf(action);
        Long taskId = diagnosis != null ? diagnosis.getTaskId() : null;
        String nodeCode = nodeOverride;
        if (nodeCode == null) {
            nodeCode = diagnosis != null && diagnosis.getWorkerNodeCode() != null
                    ? diagnosis.getWorkerNodeCode() : "node-1";
        }
        TaskInstance inst = rerunOnNode(taskId, nodeCode, log);
        resolveDiagnosis(diagnosis);
        return new ExecOutcome(true, message,
                json(Map.of("newInstanceId", inst.getId(), "node", nodeCode)), inst.getId());
    }

    private ExecOutcome migrate(AgentAction action) {
        String target = fleetService.pickLeastLoadedOnline()
                .map(WorkerNode::getNodeCode).orElse("node-online");
        TaskDiagnosis diagnosis = diagnosisOf(action);
        TaskInstance inst = rerunOnNode(diagnosis != null ? diagnosis.getTaskId() : null, target,
                "[fix] 迁移到 " + target + " 后重跑成功");
        resolveDiagnosis(diagnosis);
        return new ExecOutcome(true, "已将任务迁移到空闲节点 " + target + " 重跑，运行成功。",
                json(Map.of("newInstanceId", inst.getId(), "node", target)), inst.getId());
    }

    private ExecOutcome rerunMoreMemory(AgentAction action) {
        TaskDiagnosis diagnosis = diagnosisOf(action);
        String nodeCode = diagnosis != null && diagnosis.getWorkerNodeCode() != null
                ? diagnosis.getWorkerNodeCode() : "node-1";
        TaskInstance inst = rerunOnNode(diagnosis != null ? diagnosis.getTaskId() : null, nodeCode,
                "[fix] 调大 executor 内存后重跑成功");
        resolveDiagnosis(diagnosis);
        return new ExecOutcome(true, "已调大 executor 内存并在 " + nodeCode + " 重跑，运行成功。",
                json(Map.of("newInstanceId", inst.getId(), "node", nodeCode)), inst.getId());
    }

    private ExecOutcome capNodeWeight(AgentAction action) {
        TaskDiagnosis diagnosis = diagnosisOf(action);
        String nodeCode = diagnosis != null ? diagnosis.getWorkerNodeCode() : action.getTargetId();
        resolveDiagnosis(diagnosis);
        return new ExecOutcome(true, "已为节点 " + nodeCode + " 设置调度权重上限，后续将减少该节点的任务并发（mock 生效）。",
                json(Map.of("node", String.valueOf(nodeCode))), null);
    }

    // ---- 任务实例重跑（MCP task_rerun / CLI dw task rerun）----
    private ExecOutcome taskRerun(AgentAction action) {
        Long instanceId = parseLong(action.getTargetId());
        TaskInstance src = instanceId == null ? null : instanceRepository.findById(instanceId).orElse(null);
        Long taskId = src != null ? src.getTaskId() : null;
        String node = src != null && src.getWorkerNodeCode() != null ? src.getWorkerNodeCode()
                : fleetService.pickLeastLoadedOnline().map(WorkerNode::getNodeCode).orElse("node-1");
        TaskInstance inst = rerunOnNode(taskId, node, "[rerun] 重跑实例 #" + action.getTargetId() + " 成功");
        return new ExecOutcome(true, "已重跑任务实例 #" + action.getTargetId() + "，新实例 #" + inst.getId() + " 运行成功。",
                json(Map.of("newInstanceId", inst.getId(), "node", node, "sourceInstanceId", String.valueOf(action.getTargetId()))),
                inst.getId());
    }

    // ---- 建任务并上线（MCP create_task）。command 编码为 "cron\ncontent"，targetId 为任务名 ----
    private ExecOutcome createTask(AgentAction action) {
        String name = action.getTargetId() != null ? action.getTargetId() : "自然语言任务";
        String cmd = action.getCommand() == null ? "" : action.getCommand();
        int nl = cmd.indexOf('\n');
        String cron = nl >= 0 ? cmd.substring(0, nl) : "0 0 8 * * ?";
        String content = nl >= 0 ? cmd.substring(nl + 1) : (cmd.isBlank() ? "select count(*) from orders" : cmd);
        TaskService.TaskCreation c = taskService.createAndOnline(name, "SQL", content, cron);
        return new ExecOutcome(true, "已创建并上线任务「" + name + "」（cron " + cron + "），并 mock 推进一条成功实例。",
                json(Map.of("taskId", c.task().getId(), "name", name, "cron", cron, "instanceId", c.instanceId())),
                c.instanceId());
    }

    // ---- node_exec（委托 gateway）----
    private ExecOutcome nodeExec(AgentAction action) {
        NodeExecGateway gateway = nodeExecGateway.getIfAvailable();
        if (gateway == null) {
            return new ExecOutcome(false, "node_exec 网关未接线（worker-exec 未部署）",
                    json(Map.of("error", "node_exec_gateway_absent")), null);
        }
        NodeExecGateway.ExecResult r = gateway.exec(action.getTargetId(), action.getCommand());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("exitCode", r.exitCode());
        result.put("stdout", r.stdout());
        result.put("stderr", r.stderr());
        result.put("truncated", r.truncated());
        return new ExecOutcome(r.success(), r.message(), json(result), null);
    }

    // ---- helpers ----
    private TaskDiagnosis diagnosisOf(AgentAction action) {
        if (!"DIAGNOSIS".equalsIgnoreCase(action.getTargetType())) {
            return null;
        }
        Long id = parseLong(action.getTargetId());
        return id == null ? null : diagnosisRepository.findById(id).orElse(null);
    }

    private void resolveDiagnosis(TaskDiagnosis diagnosis) {
        if (diagnosis != null) {
            diagnosis.setStatus("RESOLVED");
            diagnosis.setUpdatedAt(LocalDateTime.now());
            diagnosisRepository.save(diagnosis);
        }
    }

    private TaskInstance rerunOnNode(Long taskId, String nodeCode, String log) {
        LocalDateTime now = LocalDateTime.now();
        TaskInstance inst = new TaskInstance();
        inst.setTenantId(1L);
        inst.setProjectId(1L);
        inst.setTaskId(taskId);
        inst.setRunMode("NORMAL");
        inst.setState("SUCCESS");
        inst.setAttempt(1);
        inst.setWorkerNodeCode(nodeCode);
        inst.setStartedAt(now);
        inst.setFinishedAt(now);
        inst.setLog(log);
        inst.setCreatedAt(now);
        inst.setUpdatedAt(now);
        inst.setDeleted(0);
        inst.setVersion(0L);
        return instanceRepository.save(inst);
    }

    private Long parseLong(String s) {
        if (s == null) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String json(Map<String, ?> o) {
        return Json.obj(o);
    }
}
