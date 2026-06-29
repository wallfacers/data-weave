package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.Locales;
import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.domain.LogArchiveStorage;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import com.dataweave.master.i18n.BizException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * `dw` CLI 的 REST 契约。读类直通领域查询；写类（rerun）经 {@link GatedActionService} 闸门——
 * 人手工执行与 agent 经 node_exec 同闸同审计（dw-cli spec）。
 * 认证：优先 Bearer JWT（由 JwtAuthFilter 统一处理），过渡期兼容 X-DW-Token。
 */
@RestController
@RequestMapping("/api/cli")
public class CliController {

    private final TaskDefRepository taskDefRepository;
    private final TaskInstanceRepository instanceRepository;
    private final GatedActionService gatedActionService;
    private final LogArchiveStorage logArchive;
    private final String cliToken;

    public CliController(TaskDefRepository taskDefRepository,
                         TaskInstanceRepository instanceRepository,
                         GatedActionService gatedActionService,
                         LogArchiveStorage logArchive,
                         @Value("${cli.auth.token:dataweave-local-cli-token}") String cliToken) {
        this.taskDefRepository = taskDefRepository;
        this.instanceRepository = instanceRepository;
        this.gatedActionService = gatedActionService;
        this.logArchive = logArchive;
        this.cliToken = cliToken;
    }

    @GetMapping("/tasks")
    public ApiResponse<List<TaskDef>> tasks() {
        List<TaskDef> out = new ArrayList<>();
        taskDefRepository.findAll().forEach(t -> {
            if (t.getDeleted() == null || t.getDeleted() == 0) {
                out.add(t);
            }
        });
        return ApiResponse.ok(out);
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<TaskDef> task(@PathVariable Long id) {
        TaskDef task = taskDefRepository.findById(id)
                .orElseThrow(() -> new BizException("task.not_found", id).withHttpStatus(404));
        return ApiResponse.ok(task);
    }

    @GetMapping("/tasks/{taskId}/instances")
    public ApiResponse<List<TaskInstance>> instances(@PathVariable Long taskId) {
        return ApiResponse.ok(instanceRepository.findByTaskId(taskId));
    }

    @GetMapping("/instances/{id}/logs")
    public ApiResponse<Map<String, Object>> logs(@PathVariable UUID id) {
        TaskInstance inst = instanceRepository.findById(id)
                .orElseThrow(() -> new BizException("task_instance.not_found", id).withHttpStatus(404));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("instanceId", id);
        r.put("state", inst.getState());
        r.put("workerNodeCode", inst.getWorkerNodeCode());

        // 尝试从归档读取完整日志；若归档不存在，回退到 task_instance.log 字段
        String logContent = null;
        if (inst.getBizDate() != null) {
            String bizDate = inst.getBizDate().toString();
            String key = String.format("logs/%s/%s/%d.log", bizDate, id, inst.getAttempt());
            var archived = logArchive.get(key);
            if (archived.isPresent()) {
                logContent = archived.get();
            }
        }
        if (logContent == null) {
            logContent = inst.getLog();
        }
        r.put("log", logContent);
        return ApiResponse.ok(r);
    }

    @PostMapping("/instances/{id}/rerun")
    public ApiResponse<GateResult> rerun(@PathVariable UUID id,
                            @RequestHeader(value = "X-DW-Token", required = false) String xDwToken,
                            ServerWebExchange exchange) {
        String actor = requireToken(xDwToken, exchange);
        ActionRequest req = ActionRequest.builder()
                .toolName("task_rerun").actionType("TASK_RERUN")
                .targetType("TASK_INSTANCE").targetId(String.valueOf(id))
                .actor(actor).actorSource("CLI")
                .summary("CLI 重跑实例 #" + id)
                .build();
        return ApiResponse.ok(gatedActionService.submit(req, Locales.uiLocale(exchange.getRequest().getHeaders())));
    }

    /**
     * 认证校验：优先 Bearer JWT（exchange attributes，由 JwtAuthFilter 注入），
     * 否则回退 X-DW-Token（过渡期兼容旧 CLI）。
     * @return actor 标识（用于审计）
     */
    private String requireToken(String xDwToken, ServerWebExchange exchange) {
        // 优先 Bearer JWT
        Object userId = exchange.getAttribute("userId");
        if (userId != null) {
            String username = String.valueOf(exchange.getAttributeOrDefault("username", "jwt-user"));
            return "cli:" + username;
        }
        // 过渡期兼容：X-DW-Token
        if (xDwToken != null && !xDwToken.isBlank() && xDwToken.equals(cliToken)) {
            return "cli:" + mask(xDwToken);
        }
        throw new BizException("cli.auth.invalid").withHttpStatus(401);
    }

    private String mask(String token) {
        if (token == null || token.length() < 4) {
            return "****";
        }
        return "****" + token.substring(token.length() - 4);
    }
}
