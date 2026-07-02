package com.dataweave.api.interfaces;

import com.dataweave.api.infrastructure.ApiResponse;
import com.dataweave.api.infrastructure.ProjectAuthz;
import com.dataweave.api.infrastructure.TenantContext;
import com.dataweave.master.application.ProjectSyncDtos;
import com.dataweave.master.application.ProjectSyncService;
import org.springframework.web.bind.annotation.*;

/**
 * 项目同步 API：pull（导出为文件集）/ push（文件集落库+快照）/ diff（只读差异预览）。
 * 子特性 C 的 REST 接口层。
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectSyncController {

    private final ProjectSyncService syncService;
    private final ProjectAuthz projectAuthz;

    public ProjectSyncController(ProjectSyncService syncService, ProjectAuthz projectAuthz) {
        this.syncService = syncService;
        this.projectAuthz = projectAuthz;
    }

    /** US1: 拉取项目定义为文件集。 */
    @PostMapping("/{projectId}/pull")
    public ApiResponse<ProjectSyncDtos.PullResult> pull(@PathVariable Long projectId) {
        return ApiResponse.ok(syncService.pull(projectId, TenantContext.tenantId()));
    }

    /** US2: 推送文件集落库并生成版本快照。036-D2：定义写入 = EDITOR+（task:manage，FR-042），闸门前置门。 */
    @PostMapping("/{projectId}/push")
    public ApiResponse<ProjectSyncDtos.PushResult> push(@PathVariable Long projectId,
                                                        @RequestBody ProjectSyncDtos.PushCommand cmd) {
        projectAuthz.require("task:manage", projectId);
        return ApiResponse.ok(syncService.push(projectId, TenantContext.tenantId(),
                TenantContext.userId(), cmd));
    }

    /** US3: 只读差异预览（push 前调用）。 */
    @PostMapping("/{projectId}/diff")
    public ApiResponse<ProjectSyncDtos.DiffPreview> diff(@PathVariable Long projectId,
                                                         @RequestBody ProjectSyncDtos.PushCommand cmd) {
        return ApiResponse.ok(syncService.diff(projectId, TenantContext.tenantId(), cmd));
    }
}
