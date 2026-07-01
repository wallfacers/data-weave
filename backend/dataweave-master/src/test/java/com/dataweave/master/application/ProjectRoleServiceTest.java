package com.dataweave.master.application;

import com.dataweave.master.domain.Permission;
import com.dataweave.master.domain.PermissionRepository;
import com.dataweave.master.domain.ProjectMember;
import com.dataweave.master.domain.ProjectMemberRepository;
import com.dataweave.master.domain.Role;
import com.dataweave.master.domain.RolePermissionRepository;
import com.dataweave.master.domain.RoleRepository;
import com.dataweave.master.domain.RolePermission;
import com.dataweave.master.i18n.BizException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 036-D ProjectRoleService 角色 / 权限解析与授权单测（mock repos，不启 Spring）。
 *
 * <p>验证权限矩阵一致性（FR-040）与 {@link ProjectRoleService#requirePermission} 三态
 * （project.required / project.forbidden / project.role.forbidden，FR-042）。
 * 角色 code 对齐 seed：ADMIN / DEVELOPER / VIEWER。
 */
class ProjectRoleServiceTest {

    private final ProjectMemberRepository memberRepo = mock(ProjectMemberRepository.class);
    private final RoleRepository roleRepo = mock(RoleRepository.class);
    private final RolePermissionRepository rolePermRepo = mock(RolePermissionRepository.class);
    private final PermissionRepository permRepo = mock(PermissionRepository.class);
    private final ProjectRoleService svc =
            new ProjectRoleService(memberRepo, roleRepo, rolePermRepo, permRepo);

    // 角色：1=ADMIN, 2=DEVELOPER, 3=VIEWER
    private static Role role(long id, String code) {
        Role r = new Role();
        r.setId(id);
        r.setCode(code);
        r.setName(code); // mock 展示名 == code（见 resolveMembership_admin_snapshot 断言）
        r.setDeleted(0);
        return r;
    }

    private static ProjectMember member(long memberId, long roleId) {
        ProjectMember m = new ProjectMember();
        m.setId(memberId);
        m.setRoleId(roleId);
        m.setDeleted(0);
        return m;
    }

    private static RolePermission rp(long permissionId) {
        RolePermission rp = new RolePermission();
        rp.setPermissionId(permissionId);
        return rp;
    }

    private static Permission perm(long id, String code) {
        Permission p = new Permission();
        p.setId(id);
        p.setCode(code);
        p.setDeleted(0);
        return p;
    }

    /** stub: user 是 project 成员，角色 roleId，对应权限 permIds。 */
    private void stubMember(long user, long roleId, List<Long> permIds) {
        when(memberRepo.findByTenantIdAndProjectIdAndUserIdAndDeleted(1L, 1L, user, 0))
                .thenReturn(List.of(member(user, roleId)));
        when(roleRepo.findById(roleId)).thenReturn(Optional.of(role(roleId, codeOf(roleId))));
        when(rolePermRepo.findByRoleId(roleId)).thenReturn(
                permIds.stream().map(ProjectRoleServiceTest::rp).toList());
        for (long pid : permIds) {
            when(permRepo.findById(pid)).thenReturn(Optional.of(perm(pid, codeOfPerm(pid))));
        }
    }

    private static String codeOf(long roleId) {
        if (roleId == 1L) return "ADMIN";
        if (roleId == 2L) return "DEVELOPER";
        return "VIEWER";
    }

    private static String codeOfPerm(long pid) {
        if (pid == 1L) return "task:manage";
        if (pid == 2L) return "workflow:manage";
        if (pid == 3L) return "metric:manage";
        if (pid == 4L) return "datasource:manage";
        return "project:manage";
    }

    // ===== 权限矩阵（FR-040）=====

    @Test
    void admin_has_all_five_permissions() {
        stubMember(1L, 1L, List.of(1L, 2L, 3L, 4L, 5L));
        assertThat(svc.roleCodeOf(1L, 1L, 1L)).hasValue("ADMIN");
        assertThat(svc.permissionsOf(1L, 1L, 1L)).containsExactlyInAnyOrder(
                "task:manage", "workflow:manage", "metric:manage", "datasource:manage", "project:manage");
    }

    @Test
    void developer_has_four_permissions_without_project_manage() {
        stubMember(2L, 2L, List.of(1L, 2L, 3L, 4L));
        assertThat(svc.roleCodeOf(1L, 2L, 1L)).hasValue("DEVELOPER");
        Set<String> perms = svc.permissionsOf(1L, 2L, 1L);
        assertThat(perms).containsExactlyInAnyOrder(
                "task:manage", "workflow:manage", "metric:manage", "datasource:manage");
        assertThat(perms).doesNotContain("project:manage");
    }

    @Test
    void viewer_has_no_permissions() {
        // VIEWER 无 role_permission 行 → 空权限集（只读）
        stubMember(3L, 3L, List.of());
        assertThat(svc.roleCodeOf(1L, 3L, 1L)).hasValue("VIEWER");
        assertThat(svc.permissionsOf(1L, 3L, 1L)).isEmpty();
    }

    @Test
    void non_member_has_no_role_and_no_permissions() {
        when(memberRepo.findByTenantIdAndProjectIdAndUserIdAndDeleted(1L, 1L, 99L, 0))
                .thenReturn(List.of());
        assertThat(svc.roleCodeOf(1L, 99L, 1L)).isEmpty();
        assertThat(svc.permissionsOf(1L, 99L, 1L)).isEmpty();
        assertThat(svc.resolveMembership(1L, 99L, 1L).member()).isFalse();
        assertThat(svc.isMember(1L, 99L, 1L)).isFalse();
    }

    @Test
    void resolveMembership_admin_snapshot() {
        stubMember(1L, 1L, List.of(1L, 5L));
        var m = svc.resolveMembership(1L, 1L, 1L);
        assertThat(m.member()).isTrue();
        assertThat(m.roleCode()).isEqualTo("ADMIN");
        assertThat(m.roleName()).isEqualTo("ADMIN"); // mock name == code（测试不关心展示名）
        assertThat(m.permissions()).containsExactlyInAnyOrder("task:manage", "project:manage");
    }

    // ===== requirePermission 三态（FR-042）=====

    @Test
    void requirePermission_member_with_permission_passes() {
        stubMember(1L, 1L, List.of(5L));
        svc.requirePermission(1L, 1L, 1L, "project:manage"); // 不抛即通过
    }

    @Test
    void requirePermission_member_without_permission_throws_role_forbidden_403() {
        stubMember(2L, 2L, List.of(1L, 2L, 3L, 4L)); // DEVELOPER 无 project:manage
        assertThatThrownBy(() -> svc.requirePermission(1L, 2L, 1L, "project:manage"))
                .isInstanceOfSatisfying(BizException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("project.role.forbidden");
                    assertThat(e.getHttpStatus()).isEqualTo(403);
                });
    }

    @Test
    void requirePermission_viewer_any_write_throws_role_forbidden() {
        stubMember(3L, 3L, List.of()); // VIEWER 无任何权限
        assertThatThrownBy(() -> svc.requirePermission(1L, 3L, 1L, "task:manage"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("project.role.forbidden"));
    }

    @Test
    void requirePermission_non_member_throws_forbidden() {
        when(memberRepo.findByTenantIdAndProjectIdAndUserIdAndDeleted(1L, 1L, 99L, 0))
                .thenReturn(List.of());
        assertThatThrownBy(() -> svc.requirePermission(1L, 99L, 1L, "project:manage"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("project.forbidden"));
    }

    @Test
    void requirePermission_missing_project_throws_required() {
        assertThatThrownBy(() -> svc.requirePermission(1L, 1L, null, "project:manage"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("project.required"));
    }
}
