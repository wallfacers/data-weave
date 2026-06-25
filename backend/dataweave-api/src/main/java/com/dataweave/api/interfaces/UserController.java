package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.domain.User;
import com.dataweave.master.domain.UserRepository;
import com.dataweave.master.domain.UserRole;
import com.dataweave.master.domain.UserRoleRepository;
import com.dataweave.master.i18n.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 用户管理 CRUD。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JdbcTemplate jdbcTemplate;

    public UserController(UserRepository userRepository,
                          UserRoleRepository userRoleRepository,
                          PasswordEncoder passwordEncoder,
                          JdbcTemplate jdbcTemplate) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping
    public ApiResponse<Object> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String roleId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) tenantId = 1L;
        // 有筛选/分页参数 → 动态查询返回分页结果
        if (search != null || status != null || roleId != null || page != null) {
            return ApiResponse.ok(query(tenantId, search, status, roleId, page, size));
        }
        // 无参 → 旧版全量返回
        return ApiResponse.ok(userRepository.findByTenantId(tenantId));
    }

    private Map<String, Object> query(Long tenantId, String search, String status,
                                       String roleId, Integer page, Integer size) {
        StringBuilder where = new StringBuilder("WHERE u.tenant_id = ? AND u.deleted = 0");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);

        if (search != null && !search.isBlank()) {
            where.append(" AND (u.username LIKE ? OR u.display_name LIKE ? OR u.email LIKE ?)");
            String like = "%" + search.trim() + "%";
            params.add(like);
            params.add(like);
            params.add(like);
        }
        if (status != null && !status.isBlank()) {
            where.append(" AND u.status = ?");
            params.add(status);
        }
        if (roleId != null && !roleId.isBlank()) {
            List<Long> roleIds = Arrays.stream(roleId.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Long::parseLong)
                    .toList();
            if (!roleIds.isEmpty()) {
                where.append(" AND u.id IN (SELECT ur.user_id FROM user_role ur WHERE ur.deleted = 0 AND ur.role_id IN (");
                for (int i = 0; i < roleIds.size(); i++) {
                    if (i > 0) where.append(",");
                    where.append("?");
                    params.add(roleIds.get(i));
                }
                where.append("))");
            }
        }

        int p = page != null ? Math.max(1, page) : 1;
        int s = size != null ? Math.max(1, Math.min(size, 100)) : 20;

        // Count
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users u " + where, Long.class, params.toArray());
        long totalElements = total != null ? total : 0;

        // Page
        int offset = (p - 1) * s;
        String sql = "SELECT u.* FROM users u " + where + " ORDER BY u.created_at DESC LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(s);
        pageParams.add(offset);

        List<User> content = jdbcTemplate.query(sql, (rs, rowNum) -> {
            User u = new User();
            u.setId(rs.getLong("id"));
            u.setTenantId(rs.getLong("tenant_id"));
            u.setUsername(rs.getString("username"));
            u.setDisplayName(rs.getString("display_name"));
            u.setEmail(rs.getString("email"));
            u.setPhone(rs.getString("phone"));
            u.setStatus(rs.getString("status"));
            u.setCreatedBy(rs.getObject("created_by") != null ? rs.getLong("created_by") : null);
            u.setUpdatedBy(rs.getObject("updated_by") != null ? rs.getLong("updated_by") : null);
            u.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null);
            u.setUpdatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null);
            return u;
        }, pageParams.toArray());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", content);
        result.put("total", totalElements);
        result.put("page", p);
        result.put("size", s);
        return result;
    }

    @GetMapping("/{id}")
    public ApiResponse<User> get(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(ApiResponse::ok)
                .orElseThrow(() -> new BizException("user.not_found", id).withHttpStatus(404));
    }

    @PostMapping
    public ApiResponse<User> create(@RequestBody Map<String, String> body) {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) tenantId = 1L;

        User user = new User();
        user.setTenantId(tenantId);
        user.setUsername(body.get("username"));
        user.setPasswordHash(passwordEncoder.encode(body.getOrDefault("password", "123456")));
        user.setDisplayName(body.getOrDefault("displayName", body.get("username")));
        user.setEmail(body.get("email"));
        user.setPhone(body.get("phone"));
        user.setStatus("ACTIVE");
        user.setCreatedBy(TenantContext.userId());
        user.setUpdatedBy(TenantContext.userId());
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        user.setDeleted(0);
        user.setVersion(0);
        return ApiResponse.ok(userRepository.save(user));
    }

    @PutMapping("/{id}")
    public ApiResponse<User> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return userRepository.findById(id)
                .map(existing -> {
                    if (body.containsKey("displayName")) existing.setDisplayName(body.get("displayName"));
                    if (body.containsKey("email")) existing.setEmail(body.get("email"));
                    if (body.containsKey("phone")) existing.setPhone(body.get("phone"));
                    if (body.containsKey("status")) existing.setStatus(body.get("status"));
                    if (body.containsKey("password")) {
                        existing.setPasswordHash(passwordEncoder.encode(body.get("password")));
                    }
                    existing.setUpdatedBy(TenantContext.userId());
                    existing.setUpdatedAt(LocalDateTime.now());
                    return ApiResponse.ok(userRepository.save(existing));
                })
                .orElseThrow(() -> new BizException("user.not_found", id).withHttpStatus(404));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(existing -> {
                    existing.setDeleted(1);
                    existing.setStatus("DISABLED");
                    existing.setUpdatedAt(LocalDateTime.now());
                    userRepository.save(existing);
                    return ApiResponse.<Void>ok();
                })
                .orElseThrow(() -> new BizException("user.not_found", id).withHttpStatus(404));
    }

    // ===== 用户-角色绑定 =====

    @PostMapping("/{id}/roles")
    public ApiResponse<Void> assignRoles(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> roleIds = (List<Number>) body.get("roleIds");
        if (roleIds == null) throw new BizException("user.role_ids.required");

        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) tenantId = 1L;

        // 先清除旧绑定
        List<UserRole> existing = userRoleRepository.findByUserId(id);
        for (UserRole ur : existing) {
            userRoleRepository.delete(ur);
        }

        // 新建绑定
        for (Number roleId : roleIds) {
            UserRole ur = new UserRole();
            ur.setTenantId(tenantId);
            ur.setUserId(id);
            ur.setRoleId(roleId.longValue());
            ur.setCreatedBy(TenantContext.userId());
            ur.setUpdatedBy(TenantContext.userId());
            ur.setCreatedAt(LocalDateTime.now());
            ur.setUpdatedAt(LocalDateTime.now());
            ur.setDeleted(0);
            ur.setVersion(0);
            userRoleRepository.save(ur);
        }
        return ApiResponse.ok();
    }
}
