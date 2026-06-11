package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.domain.User;
import com.dataweave.master.domain.UserRepository;
import com.dataweave.master.domain.UserRole;
import com.dataweave.master.domain.UserRoleRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户管理 CRUD。
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserController(UserRepository userRepository,
                          UserRoleRepository userRoleRepository,
                          PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping
    public ApiResponse<List<User>> list() {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) tenantId = 1L;
        return ApiResponse.ok(userRepository.findByTenantId(tenantId));
    }

    @GetMapping("/{id}")
    public ApiResponse<User> get(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(ApiResponse::ok)
                .orElse(ApiResponse.err(404, "用户不存在: " + id));
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
                .orElse(ApiResponse.err(404, "用户不存在: " + id));
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
                .orElse(ApiResponse.err(404, "用户不存在: " + id));
    }

    // ===== 用户-角色绑定 =====

    @PostMapping("/{id}/roles")
    public ApiResponse<Void> assignRoles(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> roleIds = (List<Number>) body.get("roleIds");
        if (roleIds == null) return ApiResponse.err(400, "roleIds 不能为空");

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
