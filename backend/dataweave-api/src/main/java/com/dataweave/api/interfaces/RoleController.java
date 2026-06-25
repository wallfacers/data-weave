package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.domain.Role;
import com.dataweave.master.domain.RolePermission;
import com.dataweave.master.domain.RolePermissionRepository;
import com.dataweave.master.domain.RoleRepository;
import com.dataweave.master.i18n.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 角色管理 CRUD + 权限分配。
 */
@RestController
@RequestMapping("/api/roles")
public class RoleController {

    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final JdbcTemplate jdbcTemplate;

    public RoleController(RoleRepository roleRepository,
                          RolePermissionRepository rolePermissionRepository,
                          JdbcTemplate jdbcTemplate) {
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public ApiResponse<Object> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) tenantId = 1L;
        // 有筛选/分页参数 → 动态查询返回分页结果
        if (search != null || page != null) {
            return ApiResponse.ok(query(tenantId, search, page, size));
        }
        // 无参 → 旧版全量返回
        return ApiResponse.ok(roleRepository.findByTenantId(tenantId));
    }

    private Map<String, Object> query(Long tenantId, String search,
                                       Integer page, Integer size) {
        StringBuilder where = new StringBuilder("WHERE tenant_id = ? AND deleted = 0");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (search != null && !search.isBlank()) {
            where.append(" AND (code LIKE ? OR name LIKE ?)");
            String like = "%" + search.trim() + "%";
            params.add(like);
            params.add(like);
        }

        int p = page != null ? Math.max(1, page) : 1;
        int s = size != null ? Math.max(1, Math.min(size, 100)) : 20;

        // Count
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM roles " + where, Long.class, params.toArray());
        long totalElements = total != null ? total : 0;

        // Page
        int offset = (p - 1) * s;
        String sql = "SELECT * FROM roles " + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(s);
        pageParams.add(offset);

        List<Role> content = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Role r = new Role();
            r.setId(rs.getLong("id"));
            r.setTenantId(rs.getLong("tenant_id"));
            r.setCode(rs.getString("code"));
            r.setName(rs.getString("name"));
            r.setDescription(rs.getString("description"));
            r.setCreatedBy(rs.getObject("created_by") != null ? rs.getLong("created_by") : null);
            r.setUpdatedBy(rs.getObject("updated_by") != null ? rs.getLong("updated_by") : null);
            r.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null);
            r.setUpdatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null);
            return r;
        }, pageParams.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", content);
        result.put("total", totalElements);
        result.put("page", p);
        result.put("size", s);
        return result;
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
