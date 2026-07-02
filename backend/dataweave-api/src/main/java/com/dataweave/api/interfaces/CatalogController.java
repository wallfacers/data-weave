package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.CatalogException;
import com.dataweave.master.application.CatalogTreeService;
import com.dataweave.master.application.CatalogTreeService.CatalogTree;
import com.dataweave.master.application.ProjectScope;
import com.dataweave.master.domain.CatalogNode;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 类目文件夹树 REST 端点。
 *
 * <p>读整棵树 + 文件夹 CRUD/移动 + 资产归类。错误码经 {@link CatalogException} 稳定映射：
 * 非空禁删 409、成环 400、不存在 404、非法入参 400。
 *
 * <p>036 项目隔离：所有端点按当前项目收敛，projectId 默认从 TenantContext 取，
 * 经 ProjectScope.require 成员校验；update/delete 按节点归属守卫防跨项目越权。
 *
 * <p>PATCH 语义钉死：用 {@code Map} 请求体区分「显式 null」与「字段缺失」——
 * 归类 {@code {"catalogNodeId": null}} 清空、{@code {}} 不改；{@code path} 字段一律拒收。
 */
@RestController
@RequestMapping("/api/catalog")
public class CatalogController {

    private final CatalogTreeService treeService;
    private final ProjectScope projectScope;

    public CatalogController(CatalogTreeService treeService, ProjectScope projectScope) {
        this.treeService = treeService;
        this.projectScope = projectScope;
    }

    private Long resolveProjectId(Long requestProjectId) {
        Long pid = requestProjectId != null ? requestProjectId : TenantContext.projectId();
        return projectScope.require(TenantContext.tenantId(), TenantContext.userId(), pid);
    }

    /** 读整棵类目树（含每节点直属任务/工作流计数 + 未分类计数）。036 按当前项目收敛。 */
    @GetMapping("/tree")
    public ApiResponse<CatalogTree> tree(@RequestParam(required = false) Long projectId) {
        return ApiResponse.ok(treeService.getTree(resolveProjectId(projectId)));
    }

    /** 创建文件夹（name + 可选 parentId/sortOrder）。036 projectId 默认从 TenantContext 取。 */
    @PostMapping("/nodes")
    public ApiResponse<CatalogNode> create(@RequestBody Map<String, Object> body) {
        rejectPath(body);
        Long projectId = body.containsKey("projectId")
                ? resolveProjectId(asLong(body.get("projectId")))
                : resolveProjectId(null);
        Long parentId = asLong(body.get("parentId"));
        String name = body.get("name") != null ? body.get("name").toString() : null;
        Integer sortOrder = body.get("sortOrder") != null ? ((Number) body.get("sortOrder")).intValue() : null;
        return ApiResponse.ok(treeService.createFolder(projectId, parentId, name, sortOrder));
    }

    /** 改名（含 name）与/或移动（含 parentId，可为 null=移到根）。036 按节点归属守卫。 */
    @PatchMapping("/nodes/{id}")
    public ApiResponse<CatalogNode> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        rejectPath(body);
        CatalogNode node = treeService.requireNode(id);
        projectScope.require(TenantContext.tenantId(), TenantContext.userId(), node.getProjectId());
        CatalogNode result = null;
        if (body.containsKey("name")) {
            String name = body.get("name") != null ? body.get("name").toString() : null;
            result = treeService.rename(id, name);
        }
        if (body.containsKey("parentId")) {
            result = treeService.move(id, asLong(body.get("parentId")));
        }
        if (result == null) {
            result = node;
        }
        return ApiResponse.ok(result);
    }

    /** 删除文件夹（非空禁删）。036 按节点归属守卫。 */
    @DeleteMapping("/nodes/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        CatalogNode node = treeService.requireNode(id);
        projectScope.require(TenantContext.tenantId(), TenantContext.userId(), node.getProjectId());
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
