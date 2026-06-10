package com.dataweave.api.infrastructure;

import com.dataweave.master.application.FleetService;
import com.dataweave.master.application.NodeExecGateway;
import com.dataweave.master.domain.WorkerNode;
import com.dataweave.worker.application.ControlledCommandExecutor;
import com.dataweave.worker.application.ControlledCommandExecutor.CommandResult;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link NodeExecGateway} 实现（master 定接口、api 接线）。master 侧职责：节点在线校验 + 派发转发。
 *
 * <p>MVP 单进程：在线校验后直接调用本机 {@link ControlledCommandExecutor}。多节点部署时此处替换为
 * 按 node 的 worker 端点 WebClient 调用（worker 暴露 {@code POST /internal/worker/exec}）。
 * agent_action 落库由 {@code GatedActionService} 在闸门入口完成（命令/节点/结果摘要）。
 */
@Component
public class WorkerNodeExecGateway implements NodeExecGateway {

    private final FleetService fleetService;
    private final ControlledCommandExecutor executor;

    public WorkerNodeExecGateway(FleetService fleetService, ControlledCommandExecutor executor) {
        this.fleetService = fleetService;
        this.executor = executor;
    }

    @Override
    public ExecResult exec(String nodeCode, String command) {
        if (nodeCode == null || nodeCode.isBlank()) {
            return offline("未指定目标节点");
        }
        Optional<WorkerNode> node = fleetService.node(nodeCode);
        if (node.isEmpty()) {
            return offline("节点不存在：" + nodeCode);
        }
        if (!"ONLINE".equalsIgnoreCase(node.get().getStatus())) {
            return offline("节点离线：" + nodeCode);
        }

        CommandResult r = executor.execute(command);
        return new ExecResult(r.success(), r.exitCode(), r.stdout(), r.stderr(), r.truncated(),
                r.message() + (r.truncated() ? "（输出已截断，原始 " + r.originalBytes() + " 字节）" : ""));
    }

    private ExecResult offline(String message) {
        return new ExecResult(false, null, "", "", false, message);
    }
}
