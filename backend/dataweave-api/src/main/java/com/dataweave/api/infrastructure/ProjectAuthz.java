package com.dataweave.api.infrastructure;

import com.dataweave.master.application.ProjectRoleService;
import org.springframework.stereotype.Component;

/**
 * 036-D 控制器层项目角色授权门面（FR-042）。
 *
 * <p>封装「TenantContext 身份 + 目标 projectId + {@link ProjectRoleService#requirePermission}」，
 * 供 Task/Workflow/MetricMarketplace/Approval/ProjectSync 写端点复用。语义（与 ProjectScope 对齐）：
 * 身份/项目缺失 → {@code project.required}；非成员 → {@code project.forbidden}(403)；
 * 角色权限不足 → {@code project.role.forbidden}(403)。
 *
 * <p>本门面是闸门前置门：通过后调用方照常走 GatedActionService/PolicyEngine，零 bypass。
 * by-id 端点应传<b>实体归属 projectId</b>（防跨项目按 id 改删，镜像 AlertController.requireOwned）；
 * create 类端点用 {@link #requireCurrent}（当前请求项目，来自 X-Project-Id / ?projectId=）。
 *
 * <p><b>契约冻结（036-D 地基）</b>：本文件由收尾方落地，D1/D2 实现 agent 只消费不修改；
 * 契约不满足需求时回报收尾方。
 */
@Component
public class ProjectAuthz {

    private final ProjectRoleService projectRoleService;

    public ProjectAuthz(ProjectRoleService projectRoleService) {
        this.projectRoleService = projectRoleService;
    }

    /** 按显式 projectId 授权（by-id 端点用实体归属项目）。 */
    public void require(String permission, Long projectId) {
        projectRoleService.requirePermission(
                TenantContext.tenantId(), TenantContext.userId(), projectId, permission);
    }

    /** 按当前请求项目授权（create 类端点）。 */
    public void requireCurrent(String permission) {
        require(permission, TenantContext.projectId());
    }
}
