package com.dataweave.master.application;

import com.dataweave.master.domain.ProjectMemberRepository;
import com.dataweave.master.i18n.BizException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 036 地基：ProjectScope 项目作用域成员校验单测。
 *
 * <p>验证越权拦截语义（安全关键）：成员放行、非成员 project.forbidden、缺 projectId project.required。
 */
class ProjectScopeTest {

    private final ProjectMemberRepository repo = mock(ProjectMemberRepository.class);
    private final ProjectScope scope = new ProjectScope(repo);

    @Test
    void member_passes_and_returns_projectId() {
        when(repo.countByTenantIdAndProjectIdAndUserIdAndDeleted(1L, 10L, 5L, 0)).thenReturn(1L);

        assertThat(scope.require(1L, 5L, 10L)).isEqualTo(10L);
        assertThat(scope.isMember(1L, 5L, 10L)).isTrue();
    }

    @Test
    void non_member_is_forbidden() {
        when(repo.countByTenantIdAndProjectIdAndUserIdAndDeleted(1L, 10L, 99L, 0)).thenReturn(0L);

        assertThatThrownBy(() -> scope.require(1L, 99L, 10L))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getCode()).isEqualTo("project.forbidden"));
        assertThat(scope.isMember(1L, 99L, 10L)).isFalse();
    }

    @Test
    void forbidden_maps_to_http_403() {
        when(repo.countByTenantIdAndProjectIdAndUserIdAndDeleted(1L, 10L, 99L, 0)).thenReturn(0L);

        assertThatThrownBy(() -> scope.require(1L, 99L, 10L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getHttpStatus()).isEqualTo(403));
    }

    @Test
    void missing_projectId_is_required() {
        assertThatThrownBy(() -> scope.require(1L, 5L, null))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("project.required"));
    }

    @Test
    void missing_identity_is_required() {
        assertThatThrownBy(() -> scope.require(null, null, 10L))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getCode()).isEqualTo("project.required"));
        // 缺身份不查库
        assertThat(scope.isMember(null, 5L, 10L)).isFalse();
    }
}
