package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.interfaces.dto.TaskReportRequest;
import com.dataweave.master.application.WorkerReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 集群内部 REST 端点（task 3.4）：worker 状态回报。
 *
 * <p>distributed 模式下 worker 经此端点向任一 master 回报 started/finished/failed。
 * 鉴权：{@code Authorization: Bearer <cluster.auth.token>}（与 MCP 端点共用 Bearer 模式）。
 * all-in-one 模式下此端点也存在但不会被外部调用（进程内直调 {@link WorkerReportService}）。
 */
@RestController
@RequestMapping("/api/cluster")
public class ClusterController {

    private static final Logger log = LoggerFactory.getLogger(ClusterController.class);

    private final WorkerReportService reportService;
    private final String clusterToken;

    public ClusterController(WorkerReportService reportService,
                             @Value("${cluster.auth.token:}") String clusterToken) {
        this.reportService = reportService;
        this.clusterToken = clusterToken;
    }

    /**
     * Worker 任务状态回报：started / finished / failed。
     *
     * <p>请求头 {@code Authorization: Bearer <token>}，token 与 master 的 {@code cluster.auth.token} 一致。
     */
    @PostMapping("/report")
    public ResponseEntity<ApiResponse<String>> report(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody TaskReportRequest req) {
        // 鉴权
        if (clusterToken != null && !clusterToken.isBlank()) {
            String expected = "Bearer " + clusterToken;
            if (!expected.equals(auth)) {
                log.warn("[ClusterController] 鉴权失败：Authorization 头不匹配");
                return ResponseEntity.ok(ApiResponse.err(401, "鉴权失败"));
            }
        }

        // 解析 taskInstanceId
        String rawId = req.getTaskInstanceId();
        if (rawId == null || rawId.isBlank()) {
            return ResponseEntity.ok(ApiResponse.err(400, "taskInstanceId 不能为空"));
        }
        UUID taskInstanceId;
        try {
            taskInstanceId = UUID.fromString(rawId);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.err(400, "无效的 taskInstanceId: " + rawId));
        }

        String event = req.getEvent() != null ? req.getEvent().toLowerCase() : "";
        switch (event) {
            case "started" -> {
                reportService.reportStarted(taskInstanceId);
                return ResponseEntity.ok(ApiResponse.ok("reported:started"));
            }
            case "finished" -> {
                reportService.reportFinished(taskInstanceId, req.getExitCode(),
                        req.getTailLog() != null ? req.getTailLog() : "");
                return ResponseEntity.ok(ApiResponse.ok("reported:finished"));
            }
            case "failed" -> {
                reportService.reportFailed(taskInstanceId,
                        req.getFailureReason() != null ? req.getFailureReason() : "UNKNOWN",
                        req.getTailLog() != null ? req.getTailLog() : "");
                return ResponseEntity.ok(ApiResponse.ok("reported:failed"));
            }
            default -> {
                return ResponseEntity.ok(ApiResponse.err(400,
                        "未知事件类型: " + req.getEvent() + "，期望 started/finished/failed"));
            }
        }
    }
}
