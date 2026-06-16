package com.dataweave.master.application;

import com.dataweave.master.domain.CatalogNode;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.domain.WorkflowDefRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 资产归类服务：设置/清空 task、workflow 的 catalog_node_id（唯一归属，覆盖语义）。
 *
 * <p>{@code catalogNodeId == null} 表示清空归属（回未分类）。「显式 null 清空」
 * 与「字段缺失不改」由接口层区分——本服务只在确需改动时被调用，传入的 null 即清空。
 */
@Service
public class CatalogAssignService {

    private final TaskDefRepository taskDefRepository;
    private final WorkflowDefRepository workflowDefRepository;
    private final CatalogTreeService catalogTreeService;
    private final JdbcTemplate jdbcTemplate;

    public CatalogAssignService(TaskDefRepository taskDefRepository,
                                WorkflowDefRepository workflowDefRepository,
                                CatalogTreeService catalogTreeService,
                                JdbcTemplate jdbcTemplate) {
        this.taskDefRepository = taskDefRepository;
        this.workflowDefRepository = workflowDefRepository;
        this.catalogTreeService = catalogTreeService;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 设置/清空任务归属。catalogNodeId 为 null = 清空。 */
    @Transactional
    public void assignTask(Long taskId, Long catalogNodeId) {
        var task = taskDefRepository.findById(taskId)
                .orElseThrow(() -> new CatalogException(CatalogException.NOT_FOUND, "任务不存在: " + taskId));
        if (catalogNodeId != null) {
            CatalogNode node = catalogTreeService.requireNode(catalogNodeId);
            if (!node.getProjectId().equals(task.getProjectId())) {
                throw new CatalogException(CatalogException.INVALID, "目标文件夹与任务不在同一项目");
            }
        }
        jdbcTemplate.update("UPDATE task_def SET catalog_node_id = ?, updated_at = ? WHERE id = ?",
                catalogNodeId, LocalDateTime.now(), taskId);
    }

    /** 设置/清空工作流归属。catalogNodeId 为 null = 清空。 */
    @Transactional
    public void assignWorkflow(Long workflowId, Long catalogNodeId) {
        var workflow = workflowDefRepository.findById(workflowId)
                .orElseThrow(() -> new CatalogException(CatalogException.NOT_FOUND, "工作流不存在: " + workflowId));
        if (catalogNodeId != null) {
            CatalogNode node = catalogTreeService.requireNode(catalogNodeId);
            if (!node.getProjectId().equals(workflow.getProjectId())) {
                throw new CatalogException(CatalogException.INVALID, "目标文件夹与工作流不在同一项目");
            }
        }
        jdbcTemplate.update("UPDATE workflow_def SET catalog_node_id = ?, updated_at = ? WHERE id = ?",
                catalogNodeId, LocalDateTime.now(), workflowId);
    }
}
