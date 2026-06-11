package com.dataweave.api.interfaces;

import com.dataweave.master.application.TaskService;
import com.dataweave.master.application.TaskService.PageResult;
import com.dataweave.master.application.TaskService.TaskDetail;
import com.dataweave.master.domain.TaskDef;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<TaskDef> create(@RequestBody TaskDef body) {
        TaskDef created = taskService.create(body);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    /** 分页搜索任务。 */
    @GetMapping
    public PageResult search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String startTime,
            @RequestParam(required = false) String endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        LocalDateTime start = parseDateTime(startTime);
        LocalDateTime end = parseDateTime(endTime);
        return taskService.search(keyword, type, status, start, end, page, size);
    }

    /** 获取任务详情（含版本历史）。 */
    @GetMapping("/{id}")
    public ResponseEntity<TaskDetail> getById(@PathVariable Long id) {
        return taskService.getById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 更新任务（仅 DRAFT 可改）。 */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody TaskDef body) {
        try {
            return ResponseEntity.ok(taskService.update(id, body));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /** 软删除任务（仅 DRAFT 可删）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> softDelete(@PathVariable Long id) {
        try {
            taskService.softDelete(id);
            return ResponseEntity.noContent().build();
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /** 发布上线。 */
    @PostMapping("/{id}/publish")
    public ResponseEntity<?> publish(@PathVariable Long id,
                                     @RequestBody(required = false) Map<String, String> body) {
        try {
            String remark = body != null ? body.get("remark") : null;
            return ResponseEntity.ok(taskService.publish(id, remark));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
    }

    /** 下线。 */
    @PostMapping("/{id}/offline")
    public ResponseEntity<?> offline(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(taskService.offline(id));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
        }
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
