package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.master.application.TaskService;
import com.dataweave.master.application.TaskService.PageResult;
import com.dataweave.master.application.TaskService.TaskDetail;
import com.dataweave.master.domain.TaskDef;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * 任务 CRUD REST 端点。
 *
 * <p>支持创建草稿、分页搜索、查看详情、编辑（仅 DRAFT）、软删除、发布上线、下线。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    /** 创建任务草稿。 */
    @PostMapping
    public ApiResponse<TaskDef> create(@RequestBody TaskDef body) {
        return ApiResponse.ok(taskService.create(body));
    }

    /** 分页搜索任务。 */
    @GetMapping
    public ApiResponse<PageResult> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        LocalDateTime start = parseDateTime(startTime);
        LocalDateTime end = parseDateTime(endTime);
        return ApiResponse.ok(taskService.search(keyword, type, status, start, end, page, size));
    }

    /** 获取任务详情（含版本历史）。 */
    @GetMapping("/{id}")
    public ApiResponse<TaskDetail> getById(@PathVariable Long id) {
        TaskDetail detail = taskService.getById(id).orElse(null);
        if (detail == null) {
            return ApiResponse.err(404, "任务不存在: " + id);
        }
        return ApiResponse.ok(detail);
    }

    /** 更新任务（仅 DRAFT 可改）。 */
    @PutMapping("/{id}")
    public ApiResponse<TaskDef> update(@PathVariable Long id, @RequestBody TaskDef body) {
        return ApiResponse.ok(taskService.update(id, body));
    }

    /** 软删除任务（仅 DRAFT 可删）。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> softDelete(@PathVariable Long id) {
        taskService.softDelete(id);
        return ApiResponse.ok();
    }

    /** 发布上线。 */
    @PostMapping("/{id}/publish")
    public ApiResponse<TaskDef> publish(@PathVariable Long id,
                                     @RequestBody(required = false) Map<String, String> body) {
        String remark = body != null ? body.get("remark") : null;
        return ApiResponse.ok(taskService.publish(id, remark));
    }

    /** 下线。 */
    @PostMapping("/{id}/offline")
    public ApiResponse<TaskDef> offline(@PathVariable Long id) {
        return ApiResponse.ok(taskService.offline(id));
    }

    private LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s, DT_FMT);
        } catch (Exception e) {
            return null;
        }
    }
}
