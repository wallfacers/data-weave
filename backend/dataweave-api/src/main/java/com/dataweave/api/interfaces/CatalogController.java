package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.master.application.CatalogException;
import com.dataweave.master.application.CatalogTreeService;
import com.dataweave.master.application.CatalogTreeService.CatalogTree;
import com.dataweave.master.domain.CatalogNode;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 类目文件夹树 REST 端点。
 *
 * <p>读整棵树 + 文件夹 CRUD/移动 + 资产归类。错误码经 {@link CatalogException} 稳定映射：
 * 非空禁删 409、成环 400、不存在 404、非法入参 400。
 *
 * <p>PATCH 语义钉死：用 {@code Map} 请求体区分「显式 null」与「字段缺失」——
 * 归类 {@code {"catalogNodeId": null}} 清空、{@code {}} 不改；{@code path} 字段一律拒收。
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogTreeService treeService;

    public CatalogController(CatalogTreeService treeService) {
        this.treeService = treeService;
    }

    /** 读整棵类目树（含每节点直属任务/工作流计数 + 未分类计数）。 */
    @GetMapping("/tree")
    public ApiResponse<CatalogTree> tree(@RequestParam(defaultValue = "1") Long projectId) {
        return ApiResponse.ok(treeService.getTree(projectId));
    }

    /** 创建文件夹（name + 可选 parentId/sortOrder）。 */
    @PostMapping("/nodes")
    public ApiResponse<CatalogNode> create(@RequestBody Map<String, Object> body) {
        rejectPath(body);
        Long projectId = asLong(body.getOrDefault("projectId", 1));
        Long parentId = asLong(body.get("parentId"));
        String name = body.get("name") != null ? body.get("name").toString() : null;
        Integer sortOrder = body.get("sortOrder") != null ? ((Number) body.get("sortOrder")).intValue() : null;
        return ApiResponse.ok(treeService.createFolder(projectId, parentId, name, sortOrder));
    }

    /** 改名（含 name）与/或移动（含 parentId，可为 null=移到根）。 */
    @PatchMapping("/nodes/{id}")
    public ApiResponse<CatalogNode> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        rejectPath(body);
        CatalogNode result = null;
        if (body.containsKey("name")) {
            String name = body.get("name") != null ? body.get("name").toString() : null;
            result = treeService.rename(id, name);
        }
        if (body.containsKey("parentId")) {
            result = treeService.move(id, asLong(body.get("parentId")));
        }
        if (result == null) {
            result = treeService.requireNode(id);
        }
        return ApiResponse.ok(result);
    }

    /** 删除文件夹（非空禁删）。 */
    @DeleteMapping("/nodes/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        treeService.delete(id);
        return ApiResponse.ok();
    }

    private static void rejectPath(Map<String, Object> body) {
        if (body != null && body.containsKey("path")) {
            throw new CatalogException("catalog.path.derived");
        }
    }

    private static Long asLong(Object v) {
        return v != null ? ((Number) v).longValue() : null;
    }
}
