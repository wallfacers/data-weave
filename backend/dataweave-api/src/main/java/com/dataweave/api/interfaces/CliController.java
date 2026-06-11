package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.master.application.ActionRequest;
import com.dataweave.master.application.GateResult;
import com.dataweave.master.application.GatedActionService;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.TaskInstanceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * `dw` CLI 的 REST 契约。读类直通领域查询；写类（rerun）经 {@link GatedActionService} 闸门——
 * 人手工执行与 agent 经 node_exec 同闸同审计（dw-cli spec）。写类需 token（无 token → 401）。
 */
@RestController
@RequestMapping("/api/cli")
public class CliController {

    private final TaskDefRepository taskDefRepository;
    private final TaskInstanceRepository instanceRepository;
    private final GatedActionService gatedActionService;
    private final String cliToken;

    public CliController(TaskDefRepository taskDefRepository,
                         TaskInstanceRepository instanceRepository,
                         GatedActionService gatedActionService,
                         @Value("${cli.auth.token:dataweave-local-cli-token}") String cliToken) {
        this.taskDefRepository = taskDefRepository;
        this.instanceRepository = instanceRepository;
        this.gatedActionService = gatedActionService;
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "任务不存在：" + id));
        return ApiResponse.ok(task);
    }

    @GetMapping("/tasks/{taskId}/instances")
    public ApiResponse<List<TaskInstance>> instances(@PathVariable Long taskId) {
        return ApiResponse.ok(instanceRepository.findByTaskId(taskId));
    }

    @GetMapping("/instances/{id}/logs")
    public ApiResponse<Map<String, Object>> logs(@PathVariable UUID id) {
        TaskInstance inst = instanceRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "实例不存在：" + id));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("instanceId", id);
        r.put("state", inst.getState());
        r.put("workerNodeCode", inst.getWorkerNodeCode());
        r.put("log", inst.getLog());
        return ApiResponse.ok(r);
    }

    @PostMapping("/instances/{id}/rerun")
    public ApiResponse<GateResult> rerun(@PathVariable UUID id,
                            @RequestHeader(value = "X-DW-Token", required = false) String token) {
        requireToken(token);
        ActionRequest req = ActionRequest.builder()
                .toolName("task_rerun").actionType("TASK_RERUN")
                .targetType("TASK_INSTANCE").targetId(String.valueOf(id))
                .actor("cli:" + mask(token)).actorSource("CLI")
                .summary("CLI 重跑实例 #" + id)
                .build();
        return ApiResponse.ok(gatedActionService.submit(req));
    }

    private void requireToken(String token) {
        if (token == null || token.isBlank() || !token.equals(cliToken)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少或错误的 X-DW-Token");
        }
    }

    private String mask(String token) {
        if (token == null || token.length() < 4) {
            return "****";
        }
        return "****" + token.substring(token.length() - 4);
    }
}
