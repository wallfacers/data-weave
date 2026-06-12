package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.master.application.OpsService;
import com.dataweave.master.application.OpsService.DashboardSummary;
import com.dataweave.master.application.OpsService.LogChunk;
import com.dataweave.master.application.RecoveryService;
import com.dataweave.master.application.SchedulerMetrics;
import com.dataweave.master.application.SchedulerMetrics.MetricsSnapshot;
import com.dataweave.master.application.SlaService;
import com.dataweave.master.domain.EventBus;
import com.dataweave.master.domain.LogArchiveStorage;
import com.dataweave.master.domain.LogBus;
import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.domain.TaskInstance;
import com.dataweave.master.domain.WorkflowInstance;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 调度运维 / 驾驶舱查询 REST 端点：全局概况、任务定义、运行实例、失败清单、系统指标。
 *
 * <p>供前端驾驶舱首页（{@code /}）、调度运维页（{@code /ops}）、数据开发页（{@code /tasks}）拉取。
 * MVP 阶段读侧走 REST，写侧（建任务/诊断/修复）统一走 Agent（{@code /agui}）。
 */
@RestController
@RequestMapping("/api/ops")
public class OpsController {

    private final OpsService opsService;
    private final RecoveryService recoveryService;
    private final SchedulerMetrics metrics;
    private final SlaService slaService;
    private final LogBus logBus;
    private final LogArchiveStorage logArchive;
    private final EventBus eventBus;

    public OpsController(OpsService opsService, RecoveryService recoveryService,
                         SchedulerMetrics metrics, SlaService slaService,
                         LogBus logBus, LogArchiveStorage logArchive, EventBus eventBus) {
        this.opsService = opsService;
        this.recoveryService = recoveryService;
        this.metrics = metrics;
        this.slaService = slaService;
        this.logBus = logBus;
        this.logArchive = logArchive;
        this.eventBus = eventBus;
    }

    /** 驾驶舱全局态势：计数 + 失败实例清单 + Agent 诊断中事项。 */
    @GetMapping("/summary")
    public ApiResponse<DashboardSummary> summary() {
        return ApiResponse.ok(opsService.summary());
    }

    /** 所有任务定义。 */
    @GetMapping("/tasks")
    public ApiResponse<List<TaskDef>> tasks() {
        return ApiResponse.ok(opsService.tasks());
    }

    /** 正式运行实例（排除 TEST 试跑），按 id 降序。 */
    @GetMapping("/instances")
    public ApiResponse<List<TaskInstance>> instances() {
        return ApiResponse.ok(opsService.instances());
    }

    /** 失败的正式运行实例。 */
    @GetMapping("/failed")
    public ApiResponse<List<TaskInstance>> failed() {
        return ApiResponse.ok(opsService.failedInstances());
    }

    // ─── 实例生命周期操作 ─────────────────────────────────

    @PostMapping("/instances/{id}/pause")
    public ApiResponse<?> pause(@PathVariable UUID id) {
        return ApiResponse.ok(opsService.pauseWorkflow(id));
    }

    @PostMapping("/instances/{id}/resume")
    public ApiResponse<?> resume(@PathVariable UUID id) {
        return ApiResponse.ok(opsService.resumeWorkflow(id));
    }

    @PostMapping("/instances/{id}/kill")
    public ApiResponse<?> kill(@PathVariable UUID id) {
        return ApiResponse.ok(opsService.killWorkflow(id));
    }

    @PostMapping("/instances/{id}/rerun")
    public ApiResponse<?> rerun(@PathVariable UUID id) {
        boolean ok = recoveryService.rerunAll(id);
        return ok ? ApiResponse.ok("已触发整流重跑") : ApiResponse.err(400, "重跑未生效（实例不存在或非终态）");
    }

    @PostMapping("/instances/{id}/recover")
    public ApiResponse<?> recover(@PathVariable UUID id) {
        boolean ok = recoveryService.resume(id);
        return ok ? ApiResponse.ok("已触发断点恢复") : ApiResponse.err(400, "恢复未生效（实例非失败态或不存在）");
    }

    @PostMapping("/task-instances/{id}/pause")
    public ApiResponse<?> pauseTask(@PathVariable UUID id) {
        return ApiResponse.ok(opsService.pauseTask(id));
    }

    @PostMapping("/task-instances/{id}/resume")
    public ApiResponse<?> resumeTask(@PathVariable UUID id) {
        return ApiResponse.ok(opsService.resumeTask(id));
    }

    @PostMapping("/task-instances/{id}/kill")
    public ApiResponse<?> killTask(@PathVariable UUID id) {
        return ApiResponse.ok(opsService.killTask(id));
    }

    @GetMapping("/instances/{id}/log")
    public ApiResponse<?> log(@PathVariable UUID id,
                                 @RequestParam(defaultValue = "0") int offset,
                                 @RequestParam(defaultValue = "65536") int limit) {
        return ApiResponse.ok(opsService.getLog(id, offset, limit));
    }

    // ─── 系统指标（Phase 5） ───────────────────────────────────

    /** 调度四层指标聚合快照（供前端指标看板）。 */
    @GetMapping("/metrics")
    public ApiResponse<MetricsSnapshot> metrics() {
        // 先刷新 DB 衍生指标
        metrics.refreshQueueDepth();
        metrics.refreshOldestAge();
        metrics.refreshSlotUtilization();
        metrics.refreshFragmentation();
        return ApiResponse.ok(metrics.snapshot());
    }

    // ─── 实时 SSE 端点 ─────────────────────────────────────────

    /**
     * 任务实例日志实时流：从 LogBus 读取实时日志，支持 Last-Event-ID 断线续传。
     * 若实例已结束（state=SUCCESS/FAILED），从归档读取历史日志。
     */
    @GetMapping(value = "/instances/{id}/logs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> logStream(@PathVariable UUID id,
                                                     @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        // 检查实例是否已结束，若结束则从归档读取
        TaskInstance inst = opsService.instances().stream()
                .filter(i -> i.getId().equals(id))
                .findFirst()
                .orElse(null);

        if (inst != null && ("SUCCESS".equals(inst.getState()) || "FAILED".equals(inst.getState()))) {
            // 从归档读取历史日志
            return streamArchivedLogs(id, inst);
        }

        // 实时流：定时轮询 LogBus
        final String[] afterId = {lastEventId};
        return Flux.interval(Duration.ofMillis(200))
                .flatMap(tick -> {
                    List<LogBus.Entry> entries = logBus.read(id, afterId[0], 100);
                    if (entries.isEmpty()) {
                        return Flux.empty();
                    }
                    Flux<ServerSentEvent<String>> events = Flux.fromIterable(entries)
                            .map(entry -> {
                                afterId[0] = entry.id();
                                return ServerSentEvent.<String>builder()
                                        .id(entry.id())
                                        .event("log")
                                        .data(entry.line())
                                        .build();
                            });
                    return events;
                });
    }

    private Flux<ServerSentEvent<String>> streamArchivedLogs(UUID id, TaskInstance inst) {
        // 尝试从归档读取（键格式：logs/{biz_date}/{instance_id}/{attempt}.log）
        String bizDate = inst.getBizDate() != null ? inst.getBizDate().toString() : "unknown";
        String key = String.format("logs/%s/%s/%d.log", bizDate, id, inst.getAttempt());
        var content = logArchive.get(key);
        if (content.isEmpty()) {
            return Flux.just(ServerSentEvent.<String>builder()
                    .event("end")
                    .data("")
                    .build());
        }

        String[] lines = content.get().split("\n");
        return Flux.fromArray(lines)
                .index()
                .map(tuple -> ServerSentEvent.<String>builder()
                        .id(String.valueOf(tuple.getT1()))
                        .event("log")
                        .data(tuple.getT2())
                        .build())
                .concatWith(Flux.just(ServerSentEvent.<String>builder()
                        .event("end")
                        .data("")
                        .build()));
    }

    /**
     * 工作流实例状态事件流：订阅 EventBus，实时推送 DAG 节点状态变迁。
     */
    @GetMapping(value = "/workflow-instances/{id}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> workflowEventsStream(@PathVariable UUID id) {
        String channel = "dw:evt:" + id;
        Sinks.Many<ServerSentEvent<String>> sink = Sinks.many().multicast().onBackpressureBuffer();

        var subscription = eventBus.subscribe(channel, message -> {
            sink.tryEmitNext(ServerSentEvent.<String>builder()
                    .event("status")
                    .data(message)
                    .build());
        });

        return sink.asFlux()
                .doOnCancel(() -> {
                    try {
                        subscription.close();
                    } catch (Exception e) {
                        // 忽略
                    }
                });
    }
}
