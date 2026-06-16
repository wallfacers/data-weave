package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.master.application.TagService;
import com.dataweave.master.domain.EntityTag;
import com.dataweave.master.domain.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 标签 REST 端点：标签 CRUD + 任务/工作流打标/解绑（4 条对称路径）。
 */
@RestController
public class TagController {

    private final TagService tagService;

    public TagController(TagService tagService) {
        this.tagService = tagService;
    }

    // ─── 标签 CRUD ───────────────────────────────────────

    /** 列出项目内标签。 */
    @GetMapping("/api/tags")
    public ApiResponse<List<Tag>> list(@RequestParam(defaultValue = "1") Long projectId) {
        return ApiResponse.ok(tagService.list(projectId));
    }

    /** 创建标签（项目内 name 唯一）。 */
    @PostMapping("/api/tags")
    public ApiResponse<Tag> create(@RequestBody Map<String, Object> body) {
        Long projectId = body.get("projectId") != null ? ((Number) body.get("projectId")).longValue() : 1L;
        String name = body.get("name") != null ? body.get("name").toString() : null;
        String color = body.get("color") != null ? body.get("color").toString() : null;
        return ApiResponse.ok(tagService.create(projectId, name, color));
    }

    /** 删除标签（硬删 + 级联解绑）。 */
    @DeleteMapping("/api/tags/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        tagService.delete(id);
        return ApiResponse.ok();
    }

    // ─── 任务打标 / 解绑 ─────────────────────────────────

    /** 给任务打标签（body: {"tagId": ...}，幂等）。 */
    @PostMapping("/api/tasks/{id}/tags")
    public ApiResponse<Void> tagTask(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        tagService.tag(EntityTag.TYPE_TASK, id, asLong(body.get("tagId")));
        return ApiResponse.ok();
    }

    /** 解绑任务的某标签。 */
    @DeleteMapping("/api/tasks/{id}/tags/{tagId}")
    public ApiResponse<Void> untagTask(@PathVariable Long id, @PathVariable Long tagId) {
        tagService.untag(EntityTag.TYPE_TASK, id, tagId);
        return ApiResponse.ok();
    }

    /** 查任务的全部标签。 */
    @GetMapping("/api/tasks/{id}/tags")
    public ApiResponse<List<Tag>> taskTags(@PathVariable Long id) {
        return ApiResponse.ok(tagService.tagsOf(EntityTag.TYPE_TASK, id));
    }

    // ─── 工作流打标 / 解绑 ───────────────────────────────

    /** 给工作流打标签（body: {"tagId": ...}，幂等）。 */
    @PostMapping("/api/workflows/{id}/tags")
    public ApiResponse<Void> tagWorkflow(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        tagService.tag(EntityTag.TYPE_WORKFLOW, id, asLong(body.get("tagId")));
        return ApiResponse.ok();
    }

    /** 解绑工作流的某标签。 */
    @DeleteMapping("/api/workflows/{id}/tags/{tagId}")
    public ApiResponse<Void> untagWorkflow(@PathVariable Long id, @PathVariable Long tagId) {
        tagService.untag(EntityTag.TYPE_WORKFLOW, id, tagId);
        return ApiResponse.ok();
    }

    /** 查工作流的全部标签。 */
    @GetMapping("/api/workflows/{id}/tags")
    public ApiResponse<List<Tag>> workflowTags(@PathVariable Long id) {
        return ApiResponse.ok(tagService.tagsOf(EntityTag.TYPE_WORKFLOW, id));
    }

    private static Long asLong(Object v) {
        return v != null ? ((Number) v).longValue() : null;
    }
}
