package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.domain.User;
import com.dataweave.master.domain.UserRepository;
import com.dataweave.master.domain.UserRole;
import com.dataweave.master.domain.UserRoleRepository;
import org.springframework.http.ResponseEntity;
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
    public List<User> list() {
        Long tenantId = TenantContext.tenantId();
        if (tenantId == null) tenantId = 1L;
        return userRepository.findByTenantId(tenantId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<User> get(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public User create(@RequestBody Map<String, String> body) {
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
        return userRepository.save(user);
    }

    @PutMapping("/{id}")
    public ResponseEntity<User> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
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
                    return ResponseEntity.ok(userRepository.save(existing));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        return userRepository.findById(id)
                .map(existing -> {
                    existing.setDeleted(1);
                    existing.setStatus("DISABLED");
                    existing.setUpdatedAt(LocalDateTime.now());
                    userRepository.save(existing);
                    return ResponseEntity.ok().<Void>build();
                })
                .orElse(ResponseEntity.notFound().build());
    }

    // ===== 用户-角色绑定 =====

    @PostMapping("/{id}/roles")
    public ResponseEntity<Void> assignRoles(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<Number> roleIds = (List<Number>) body.get("roleIds");
        if (roleIds == null) return ResponseEntity.badRequest().build();

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
        return ResponseEntity.ok().build();
    }
}
