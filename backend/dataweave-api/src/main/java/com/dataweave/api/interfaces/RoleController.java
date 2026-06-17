package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.domain.Role;
import com.dataweave.master.domain.RolePermission;
import com.dataweave.master.domain.RolePermissionRepository;
import com.dataweave.master.domain.RoleRepository;
import com.dataweave.master.i18n.BizException;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 角色管理 CRUD + 权限分配。
 */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;

    public RoleController(RoleRepository roleRepository,
                          RolePermissionRepository rolePermissionRepository) {
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
    }

    @GetMapping
    public ApiResponse<List<Role>> list() {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) tenantId = 1L;
        return ApiResponse.ok(roleRepository.findByTenantId(tenantId));
    }

    @GetMapping("/{id}")
    public ApiResponse<Role> get(@PathVariable Long id) {
        return roleRepository.findById(id)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new BizException("role.not_found", id).withHttpStatus(404));
    }

    @PostMapping
    public ApiResponse<Role> create(@RequestBody Map<String, String> body) {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) tenantId = 1L;

        Role role = new Role();
        role.setTenantId(tenantId);
        role.setCode(body.get("code"));
        role.setName(body.get("name"));
        role.setDescription(body.get("description"));
        role.setCreatedBy(TenantContext.userId());
        role.setUpdatedBy(TenantContext.userId());
        role.setCreatedAt(LocalDateTime.now());
        role.setUpdatedAt(LocalDateTime.now());
        role.setDeleted(0);
        role.setVersion(0);
        return ApiResponse.ok(roleRepository.save(role));
    }

    @PutMapping("/{id}")
    public ApiResponse<Role> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return roleRepository.findById(id)
                .map(existing -> {
                    if (body.containsKey("name")) existing.setName(body.get("name"));
                    if (body.containsKey("description")) existing.setDescription(body.get("description"));
                    existing.setUpdatedBy(TenantContext.userId());
                    existing.setUpdatedAt(LocalDateTime.now());
                    return ApiResponse.ok(roleRepository.save(existing));
                })
                .orElseThrow(() -> new BizException("role.not_found", id).withHttpStatus(404));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        return roleRepository.findById(id)
                .map(existing -> {
                    existing.setDeleted(1);
                    existing.setUpdatedAt(LocalDateTime.now());
                    roleRepository.save(existing);
                    return ApiResponse.<Void>ok();
                })
                .orElseThrow(() -> new BizException("role.not_found", id).withHttpStatus(404));
    }

    // ===== 角色-权限绑定 =====

    @PostMapping("/{id}/permissions")
    public ApiResponse<Void> assignPermissions(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> permissionIds = (List<Number>) body.get("permissionIds");
        if (permissionIds == null) throw new BizException("role.permission_ids.required");

        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) tenantId = 1L;

        // 先清除旧绑定
        List<RolePermission> existing = rolePermissionRepository.findByRoleId(id);
        for (RolePermission rp : existing) {
            rolePermissionRepository.delete(rp);
        }

        // 新建绑定
        for (Number permId : permissionIds) {
            RolePermission rp = new RolePermission();
            rp.setTenantId(tenantId);
            rp.setRoleId(id);
            rp.setPermissionId(permId.longValue());
            rp.setCreatedBy(TenantContext.userId());
            rp.setUpdatedBy(TenantContext.userId());
            rp.setCreatedAt(LocalDateTime.now());
            rp.setUpdatedAt(LocalDateTime.now());
            rp.setDeleted(0);
            rp.setVersion(0);
            rolePermissionRepository.save(rp);
        }
        return ApiResponse.ok();
    }
}
