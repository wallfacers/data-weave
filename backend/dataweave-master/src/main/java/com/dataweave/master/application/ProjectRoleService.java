package com.dataweave.master.application;

import com.dataweave.master.domain.Permission;
import com.dataweave.master.domain.PermissionRepository;
import com.dataweave.master.domain.ProjectMember;
import com.dataweave.master.domain.ProjectMemberRepository;
import com.dataweave.master.domain.Role;
import com.dataweave.master.domain.RolePermission;
import com.dataweave.master.domain.RolePermissionRepository;
import com.dataweave.master.domain.RoleRepository;
import com.dataweave.master.i18n.BizException;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 036-D 项目角色与权限解析（FR-040 / FR-042）。
 *
 * <p>依据 {@code project_member} + {@code roles} + {@code role_permission} + {@code permissions}
 * 解析「当前用户在当前项目的角色 / 权限集」，并供受保护写端点授权（{@link #requirePermission}）。
 * 角色枚举以 {@code data.sql} 既定为准：{@code ADMIN / DEVELOPER / VIEWER}（spec Assumptions），
 * 与 spec 文案的 OWNER/EDITOR/VIEWER 对应：ADMIN≈OWNER、DEVELOPER≈EDITOR、VIEWER≈VIEWER。
 *
 * <p>本服务不替代 {@link ProjectScope} 的成员归属校验（{@code project.forbidden}），而是在其上
 * 叠加「角色 → 权限」维度：越权写操作抛 {@code project.role.forbidden}（HTTP 403，结构化
 * BizException，零 bypass、不弱化闸门）。通过本服务后，调用方仍照常走 {@code GatedActionService}
 * 闸门——项目角色授权是闸门前置门，闸门本体完整执行。
 *
 * <p>依赖方向：驻 master，被 api 控制器与 alert 控制器复用；参数显式传入
 * {@code (tenantId, userId, projectId)}，避免 master 反向依赖 api 的 TenantContext ThreadLocal，
 * 与 {@link ProjectScope} 保持一致。
 */
@Service
public class ProjectRoleService {

    /** seed 既定的角色 code（data.sql 域 A）。 */
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_DEVELOPER = "DEVELOPER";
    public static final String ROLE_VIEWER = "VIEWER";

    private final ProjectMemberRepository memberRepository;
    private final RoleRepository roleRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionRepository permissionRepository;

    public ProjectRoleService(ProjectMemberRepository memberRepository,
                              RoleRepository roleRepository,
                              RolePermissionRepository rolePermissionRepository,
                              PermissionRepository permissionRepository) {
        this.memberRepository = memberRepository;
        this.roleRepository = roleRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.permissionRepository = permissionRepository;
    }

    /**
     * 当前用户在 projectId 的角色 code（ADMIN/DEVELOPER/VIEWER）；非成员返回 {@link Optional#empty()}。
     */
    public Optional<String> roleCodeOf(Long tenantId, Long userId, Long projectId) {
        return memberOf(tenantId, userId, projectId)
                .map(ProjectMember::getRoleId)
                .flatMap(this::roleCode);
    }

    /**
     * 当前用户在 projectId 持有的权限 code 集（角色 → role_permission → permissions）；
     * 非成员返回空集（VIEWER 角色无 role_permission 行，亦返回空集 → 仅可读）。
     */
    public Set<String> permissionsOf(Long tenantId, Long userId, Long projectId) {
        Optional<Long> roleId = memberOf(tenantId, userId, projectId).map(ProjectMember::getRoleId);
        if (roleId.isEmpty()) return Set.of();
        List<RolePermission> bindings = rolePermissionRepository.findByRoleId(roleId.get());
        Set<String> codes = new HashSet<>();
        for (RolePermission rp : bindings) {
            permissionRepository.findById(rp.getPermissionId())
                    .filter(p -> !isDeleted(p))
                    .map(Permission::getCode)
                    .ifPresent(codes::add);
        }
        return codes;
    }

    /**
     * 解析「角色 + 权限集」快照，供前端权限查询接口（GET /api/projects/{id}/me）。
     * 非成员返回 {@code member=false}、{@code role=null}、{@code permissions=空}。
     */
    public ProjectMembership resolveMembership(Long tenantId, Long userId, Long projectId) {
        Optional<ProjectMember> member = memberOf(tenantId, userId, projectId);
        if (member.isEmpty()) {
            return new ProjectMembership(false, null, null, Set.of());
        }
        Long roleId = member.get().getRoleId();
        Role role = roleRepository.findById(roleId).filter(r -> !isDeleted(r)).orElse(null);
        Set<String> permissions = permissionsOf(tenantId, userId, projectId);
        return new ProjectMembership(true,
                role != null ? role.getCode() : null,
                role != null ? role.getName() : null,
                permissions);
    }

    /**
     * 受保护写端点授权：校验当前用户在 projectId 持有 {@code permission}，否则抛结构化异常。
     *
     * <p>越权语义（与 {@link ProjectScope} 对齐，零 bypass）：
     * <ul>
     *   <li>身份/项目缺失 → {@code project.required}；</li>
     *   <li>非该项目成员 → {@code project.forbidden}（HTTP 403）；</li>
     *   <li>成员但角色权限不足 → {@code project.role.forbidden}（HTTP 403）。</li>
     * </ul>
     */
    public void requirePermission(Long tenantId, Long userId, Long projectId, String permission) {
        if (tenantId == null || userId == null || projectId == null || projectId <= 0) {
            throw new BizException("project.required");
        }
        if (memberOf(tenantId, userId, projectId).isEmpty()) {
            throw new BizException("project.forbidden").withHttpStatus(403);
        }
        if (!permissionsOf(tenantId, userId, projectId).contains(permission)) {
            throw new BizException("project.role.forbidden").withHttpStatus(403);
        }
    }

    /** 是否项目成员（软校验，不抛异常）。供菜单可见性等非拦截场景。 */
    public boolean isMember(Long tenantId, Long userId, Long projectId) {
        return memberOf(tenantId, userId, projectId).isPresent();
    }

    private Optional<ProjectMember> memberOf(Long tenantId, Long userId, Long projectId) {
        if (tenantId == null || userId == null || projectId == null) return Optional.empty();
        return memberRepository
                .findByTenantIdAndProjectIdAndUserIdAndDeleted(tenantId, projectId, userId, 0)
                .stream()
                .findFirst();
    }

    private Optional<String> roleCode(Long roleId) {
        return roleRepository.findById(roleId).filter(r -> !isDeleted(r)).map(Role::getCode);
    }

    private static boolean isDeleted(Role r) {
        return r != null && r.getDeleted() != null && r.getDeleted() != 0;
    }

    private static boolean isDeleted(Permission p) {
        return p != null && p.getDeleted() != null && p.getDeleted() != 0;
    }

    /** 项目成员资格快照（角色 + 权限集），供前端 me 接口。 */
    public record ProjectMembership(boolean member, String roleCode, String roleName,
                                    Set<String> permissions) {}
}
