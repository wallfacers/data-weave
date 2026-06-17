package com.dataweave.api.application;

import com.dataweave.api.infrastructure.JwtUtil;
import com.dataweave.master.domain.*;
import com.dataweave.master.i18n.BizException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 认证服务：登录验证、JWT 签发、当前用户查询。
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(UserRepository userRepository,
                       UserRoleRepository userRoleRepository,
                       RoleRepository roleRepository,
                       RolePermissionRepository rolePermissionRepository,
                       PermissionRepository permissionRepository,
                       PasswordEncoder passwordEncoder,
                       JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.userRoleRepository = userRoleRepository;
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.permissionRepository = permissionRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    /**
     * 登录。验证用户名+密码，返回 JWT。
     *
     * @throws BizException 用户名或密码错误（{@code auth.invalid_credentials}）/ 账号已禁用（{@code auth.account_disabled}）
     */
    public LoginResult login(String username, String rawPassword) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new BizException("auth.invalid_credentials").withHttpStatus(401));

        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BizException("auth.account_disabled").withHttpStatus(403);
        }

        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BizException("auth.invalid_credentials").withHttpStatus(401);
        }

        List<String> roleCodes = resolveRoleCodes(user.getId(), user.getTenantId());
        String token = jwtUtil.generate(user.getId(), user.getTenantId(), user.getUsername(), roleCodes);

        return new LoginResult(token, user.getId(), user.getTenantId(), user.getUsername(),
                user.getDisplayName(), roleCodes);
    }

    /** 查询当前用户信息（含角色和权限）。 */
    public UserInfo me(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BizException("auth.user_not_found", userId).withHttpStatus(404));

        List<String> roleCodes = resolveRoleCodes(userId, user.getTenantId());
        List<String> permissionCodes = resolvePermissionCodes(userId, user.getTenantId());

        return new UserInfo(user.getId(), user.getTenantId(), user.getUsername(),
                user.getDisplayName(), user.getEmail(), user.getStatus(), roleCodes, permissionCodes);
    }

    private List<String> resolveRoleCodes(Long userId, Long tenantId) {
        List<UserRole> userRoles = userRoleRepository.findByUserId(userId);
        return userRoles.stream()
                .map(ur -> roleRepository.findById(ur.getRoleId()))
                .filter(opt -> opt.isPresent())
                .map(opt -> opt.get().getCode())
                .toList();
    }

    private List<String> resolvePermissionCodes(Long userId, Long tenantId) {
        List<UserRole> userRoles = userRoleRepository.findByUserId(userId);
        return userRoles.stream()
                .flatMap(ur -> rolePermissionRepository.findByRoleId(ur.getRoleId()).stream())
                .map(rp -> permissionRepository.findById(rp.getPermissionId()))
                .filter(opt -> opt.isPresent())
                .map(opt -> opt.get().getCode())
                .distinct()
                .toList();
    }

    /** 登录结果。 */
    public record LoginResult(String token, Long userId, Long tenantId, String username,
                              String displayName, List<String> roles) {
    }

    /** 用户信息。 */
    public record UserInfo(Long id, Long tenantId, String username, String displayName,
                           String email, String status, List<String> roles,
                           List<String> permissions) {
    }
}
