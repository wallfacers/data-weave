package com.dataweave.api.interfaces;

import com.dataweave.worker.application.ControlledCommandExecutor;
import com.dataweave.worker.application.ControlledCommandExecutor.CommandResult;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * worker 受控执行端点（worker 进程的 HTTP 面）。MVP 单进程内暴露；多节点部署时每个 worker 各自托管。
 * 真实派发由 master 侧 {@code WorkerNodeExecGateway} 在线校验后转发到对应 worker 的此端点。
 *
 * <p>注：类名与 worker 模块内 {@code WorkerExecController} 冲突，此 MVP 端点改名为 ApiMvpWorkerExecController。
 * Phase 3 完成后由 worker 模块的正式实现替代，本类可删除。
 */
@RestController("apiMvpWorkerExecController")
public class ApiMvpWorkerExecController {

    private final ControlledCommandExecutor executor;

    public ApiMvpWorkerExecController(ControlledCommandExecutor executor) {
        this.executor = executor;
    }

    @PostMapping(value = "/internal/worker/exec",
            consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public CommandResult exec(@RequestBody ExecRequest request) {
        return executor.execute(request.command());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExecRequest(String command) {
    }
}
