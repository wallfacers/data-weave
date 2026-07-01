package com.dataweave.master.application;

import com.dataweave.master.domain.ProjectMemberRepository;
import com.dataweave.master.i18n.BizException;
import org.springframework.stereotype.Service;

/**
 * 036 项目作用域校验入口（地基契约 FR-002）。
 *
 * <p>落在 master 模块，使 api / alert / worker 及 master 自身的读写路径均可复用
 * （依赖方向 api→master、alert→master，故校验逻辑必须驻 master，不能放 api）。
 *
 * <p>参数显式传入 {@code (tenantId, userId, projectId)}：
 * <ul>
 *   <li>api 控制器从 {@code TenantContext} 取；</li>
 *   <li>alert 控制器从 {@code exchange} 属性取；</li>
 *   <li>master 服务由其控制器透传。</li>
 * </ul>
 * 避免 master 反向依赖 api 的 ThreadLocal，同时校验逻辑纯粹、易测。
 *
 * <p>越权语义：projectId 缺失 → {@code project.required}；(tenant,user) 非该项目成员 →
 * {@code project.forbidden}（HTTP 403）。跨租户天然隔离（成员行含 tenant_id）。
 */
@Service
public class ProjectScope {

    private final ProjectMemberRepository memberRepository;

    public ProjectScope(ProjectMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    /**
     * 校验 (tenantId, userId) 是 projectId 对应项目的成员，返回校验通过的 projectId。
     *
     * @throws BizException {@code project.required} 若 projectId / 身份缺失；
     *                      {@code project.forbidden} 若非成员。
     */
    public Long require(Long tenantId, Long userId, Long projectId) {
        if (projectId == null || projectId <= 0 || tenantId == null || userId == null) {
            throw new BizException("project.required");
        }
        if (!isMember(tenantId, userId, projectId)) {
            throw new BizException("project.forbidden").withHttpStatus(403);
        }
        return projectId;
    }

    /**
     * 软校验：是否成员（不抛异常）。供菜单/视图可见性等非拦截场景使用。
     */
    public boolean isMember(Long tenantId, Long userId, Long projectId) {
        if (projectId == null || projectId <= 0 || tenantId == null || userId == null) {
            return false;
        }
        return memberRepository.countByTenantIdAndProjectIdAndUserIdAndDeleted(
                tenantId, projectId, userId, 0) > 0;
    }
}
