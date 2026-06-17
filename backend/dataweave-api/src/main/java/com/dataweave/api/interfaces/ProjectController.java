package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.domain.*;
import com.dataweave.master.i18n.BizException;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 项目管理 CRUD + 成员管理。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository projectMemberRepository;

    public ProjectController(ProjectRepository projectRepository,
                             ProjectMemberRepository projectMemberRepository) {
        this.projectRepository = projectRepository;
        this.projectMemberRepository = projectMemberRepository;
    }

    @GetMapping
    public ApiResponse<List<Project>> list() {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) tenantId = 1L;
        return ApiResponse.ok(projectRepository.findByTenantId(tenantId));
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
