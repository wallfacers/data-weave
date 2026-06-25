package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.domain.*;
import com.dataweave.master.i18n.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 项目管理 CRUD + 成员管理。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;
    private final JdbcTemplate jdbcTemplate;

    public ProjectController(ProjectRepository projectRepository,
                             ProjectMemberRepository projectMemberRepository,
                             JdbcTemplate jdbcTemplate) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public ApiResponse<Object> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long ownerId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) tenantId = 1L;
        // 有筛选/分页参数 → 动态查询返回分页结果
        if (search != null || status != null || ownerId != null || page != null) {
            return ApiResponse.ok(query(tenantId, search, status, ownerId, page, size));
        }
        // 无参 → 旧版全量返回
        return ApiResponse.ok(projectRepository.findByTenantId(tenantId));
    }

    private Map<String, Object> query(Long tenantId, String search, String status,
                                       Long ownerId, Integer page, Integer size) {
        StringBuilder where = new StringBuilder("WHERE tenant_id = ? AND deleted = 0");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (search != null && !search.isBlank()) {
            where.append(" AND (code LIKE ? OR name LIKE ?)");
            String like = "%" + search.trim() + "%";
            params.add(like);
            params.add(like);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND status = ?");
            params.add(status);
        }
        if (ownerId != null) {
            where.append(" AND owner_id = ?");
            params.add(ownerId);
        }

        int p = page != null ? Math.max(1, page) : 1;
        int s = size != null ? Math.max(1, Math.min(size, 100)) : 20;

        // Count
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM projects " + where, Long.class, params.toArray());
        long totalElements = total != null ? total : 0;

        // Page
        int offset = (p - 1) * s;
        String sql = "SELECT * FROM projects " + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(s);
        pageParams.add(offset);

        List<Project> content = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Project proj = new Project();
            proj.setId(rs.getLong("id"));
            proj.setTenantId(rs.getLong("tenant_id"));
            proj.setCode(rs.getString("code"));
            proj.setName(rs.getString("name"));
            proj.setOwnerId(rs.getObject("owner_id") != null ? rs.getLong("owner_id") : null);
            proj.setStatus(rs.getString("status"));
            proj.setCreatedBy(rs.getObject("created_by") != null ? rs.getLong("created_by") : null);
            proj.setUpdatedBy(rs.getObject("updated_by") != null ? rs.getLong("updated_by") : null);
            proj.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null);
            proj.setUpdatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null);
            return proj;
        }, pageParams.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", content);
        result.put("total", totalElements);
        result.put("page", p);
        result.put("size", s);
        return result;
    }

    @GetMapping("/{id}")
    public ApiResponse<Project> get(@PathVariable Long id) {
        return projectRepository.findById(id)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new BizException("project.not_found", id).withHttpStatus(404));
    }

    @PostMapping
    public ApiResponse<Project> create(@RequestBody Map<String, String> body) {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) tenantId = 1L;

        Project p = new Project();
        p.setTenantId(tenantId);
        p.setCode(body.get("code"));
        p.setName(body.get("name"));
        p.setOwnerId(TenantContext.userId());
        p.setStatus("ACTIVE");
        p.setCreatedBy(TenantContext.userId());
        p.setUpdatedBy(TenantContext.userId());
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        p.setDeleted(0);
        p.setVersion(0);
        return ApiResponse.ok(projectRepository.save(p));
    }

    @PutMapping("/{id}")
    public ApiResponse<Project> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return projectRepository.findById(id)
                .map(existing -> {
                    if (body.containsKey("name")) existing.setName(body.get("name"));
                    if (body.containsKey("status")) existing.setStatus(body.get("status"));
                    existing.setUpdatedBy(TenantContext.userId());
                    existing.setUpdatedAt(LocalDateTime.now());
                    return ApiResponse.ok(projectRepository.save(existing));
                })
                .orElseThrow(() -> new BizException("project.not_found", id).withHttpStatus(404));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        return projectRepository.findById(id)
                .map(existing -> {
                    existing.setDeleted(1);
                    existing.setStatus("ARCHIVED");
                    existing.setUpdatedAt(LocalDateTime.now());
                    projectRepository.save(existing);
                    return ApiResponse.<Void>ok();
                })
                .orElseThrow(() -> new BizException("project.not_found", id).withHttpStatus(404));
    }

    // ===== 项目成员管理 =====

    @GetMapping("/{id}/members")
    public ApiResponse<List<ProjectMember>> listMembers(@PathVariable Long id) {
        return ApiResponse.ok(projectMemberRepository.findByProjectId(id));
    }

    @PostMapping("/{id}/members")
    public ApiResponse<Void> addMember(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) tenantId = 1L;

        Number userId = (Number) body.get("userId");
        Number roleId = (Number) body.get("roleId");
        if (userId == null || roleId == null) throw new BizException("project.member.required");

        ProjectMember pm = new ProjectMember();
        pm.setTenantId(tenantId);
        pm.setProjectId(id);
        pm.setUserId(userId.longValue());
        pm.setRoleId(roleId.longValue());
        pm.setCreatedBy(TenantContext.userId());
        pm.setUpdatedBy(TenantContext.userId());
        pm.setCreatedAt(LocalDateTime.now());
        pm.setUpdatedAt(LocalDateTime.now());
        pm.setDeleted(0);
        pm.setVersion(0);
        projectMemberRepository.save(pm);
        return ApiResponse.ok();
    }

    @DeleteMapping("/{id}/members/{memberId}")
    public ApiResponse<Void> removeMember(@PathVariable Long id, @PathVariable Long memberId) {
        return projectMemberRepository.findById(memberId)
                .map(pm -> {
                    pm.setDeleted(1);
                    pm.setUpdatedAt(LocalDateTime.now());
                    projectMemberRepository.save(pm);
                    return ApiResponse.<Void>ok();
                })
                .orElseThrow(() -> new BizException("project.member.not_found", memberId).withHttpStatus(404));
    }
}
