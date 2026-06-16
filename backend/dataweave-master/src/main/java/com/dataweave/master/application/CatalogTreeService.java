package com.dataweave.master.application;

import com.dataweave.master.domain.CatalogNode;
import com.dataweave.master.domain.CatalogNodeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 类目文件夹树服务：建/读/移动/删，统一维护物化路径 {@code path}。
 *
 * <p>写方法入参为纯领域参数（projectId/parentId/name/id），不依赖 HTTP 上下文，
 * 可被 REST Controller 与（二期）MCP handler 共同调用。{@code path} 由本服务
 * 在创建/移动时维护，外部绝不直接写入。
 *
 * <p>方言：移动子树重写用两库通用的 {@code CONCAT(...) + SUBSTRING(str, start)}
 * （不用 {@code ||}，避 H2 MySQL 兼容模式逻辑或坑）；{@code path} 仅含数字与
 * {@code /}，{@code LIKE} 前缀匹配无需 ESCAPE。
 */
@Service
public class CatalogTreeService {

    private static final Long DEFAULT_TENANT = 1L;

    private final CatalogNodeRepository repository;
    private final JdbcTemplate jdbcTemplate;

    public CatalogTreeService(CatalogNodeRepository repository, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    // ─── Records ─────────────────────────────────────────

    /** 树节点（含直属任务/工作流计数与子节点）。 */
    public record TreeNode(Long id, Long parentId, String name, Integer sortOrder,
                           int taskCount, int workflowCount, List<TreeNode> children) {}

    /** 整棵树 + 未分类（catalog_node_id IS NULL）资产计数。 */
    public record CatalogTree(List<TreeNode> roots,
                              int uncategorizedTaskCount, int uncategorizedWorkflowCount) {}

    // ─── Create ──────────────────────────────────────────

    /** 创建文件夹（计算并落 path）。parentId 为空 = 根文件夹。 */
    @Transactional
    public CatalogNode createFolder(Long projectId, Long parentId, String name, Integer sortOrder) {
        if (name == null || name.isBlank()) {
            throw new CatalogException(CatalogException.INVALID, "文件夹名不能为空");
        }
        String parentPath = "/";
        if (parentId != null) {
            CatalogNode parent = requireNode(parentId);
            if (!parent.getProjectId().equals(projectId)) {
                throw new CatalogException(CatalogException.INVALID, "父文件夹与目标项目不一致");
            }
            parentPath = parent.getPath();
        }
        LocalDateTime now = LocalDateTime.now();
        CatalogNode node = new CatalogNode();
        node.setTenantId(DEFAULT_TENANT);
        node.setProjectId(projectId);
        node.setParentId(parentId);
        node.setName(name.trim());
        node.setPath("/");           // 占位，落库拿到 id 后回填
        node.setSortOrder(sortOrder);
        node.setCreatedAt(now);
        node.setUpdatedAt(now);
        node.setDeleted(0);
        node.setVersion(0L);
        CatalogNode saved = repository.save(node);
        saved.setPath(parentPath + saved.getId() + "/");
        return repository.save(saved);
    }

    // ─── Read tree ───────────────────────────────────────

    /** 读整棵树。计数用 GROUP BY 各一条聚合查询（避免 N+1），同级按 (sort_order, name) 稳定排序。 */
    public CatalogTree getTree(Long projectId) {
        List<CatalogNode> nodes = repository.findByProjectIdAndDeleted(projectId, 0);

        Map<Long, Integer> taskCounts = groupCount("task_def", projectId);
        Map<Long, Integer> workflowCounts = groupCount("workflow_def", projectId);

        // 稳定排序：COALESCE(sort_order,0) 升序，再 name 升序
        nodes.sort(Comparator
                .comparingInt((CatalogNode n) -> n.getSortOrder() != null ? n.getSortOrder() : 0)
                .thenComparing(CatalogNode::getName, Comparator.nullsLast(Comparator.naturalOrder())));

        // 按 parentId 分组（保序）
        Map<Long, List<CatalogNode>> byParent = new HashMap<>();
        for (CatalogNode n : nodes) {
            byParent.computeIfAbsent(n.getParentId(), k -> new ArrayList<>()).add(n);
        }

        List<TreeNode> roots = buildChildren(null, byParent, taskCounts, workflowCounts);

        int uncatTask = taskCounts.getOrDefault(null, 0);
        int uncatWorkflow = workflowCounts.getOrDefault(null, 0);
        return new CatalogTree(roots, uncatTask, uncatWorkflow);
    }

    private List<TreeNode> buildChildren(Long parentId, Map<Long, List<CatalogNode>> byParent,
                                         Map<Long, Integer> taskCounts, Map<Long, Integer> workflowCounts) {
        List<CatalogNode> children = byParent.get(parentId);
        if (children == null) return List.of();
        List<TreeNode> result = new ArrayList<>(children.size());
        for (CatalogNode n : children) {
            result.add(new TreeNode(
                    n.getId(), n.getParentId(), n.getName(), n.getSortOrder(),
                    taskCounts.getOrDefault(n.getId(), 0),
                    workflowCounts.getOrDefault(n.getId(), 0),
                    buildChildren(n.getId(), byParent, taskCounts, workflowCounts)));
        }
        return result;
    }

    /** 一条 GROUP BY 聚合某表按 catalog_node_id 的计数（含 NULL key = 未分类）。 */
    private Map<Long, Integer> groupCount(String table, Long projectId) {
        Map<Long, Integer> counts = new HashMap<>();
        jdbcTemplate.query(
                "SELECT catalog_node_id, COUNT(*) AS c FROM " + table
                        + " WHERE project_id = ? AND deleted = 0 GROUP BY catalog_node_id",
                rs -> {
                    Long nodeId = rs.getObject("catalog_node_id") != null ? rs.getLong("catalog_node_id") : null;
                    counts.put(nodeId, rs.getInt("c"));
                },
                projectId);
        return counts;
    }

    // ─── Rename ──────────────────────────────────────────

    /** 改名（不动 path/parent）。 */
    @Transactional
    public CatalogNode rename(Long id, String name) {
        if (name == null || name.isBlank()) {
            throw new CatalogException(CatalogException.INVALID, "文件夹名不能为空");
        }
        CatalogNode node = requireNode(id);
        node.setName(name.trim());
        node.setUpdatedAt(LocalDateTime.now());
        return repository.save(node);
    }

    // ─── Move（更新 parent + 子树 path 前缀批量重写 + 防环）────

    /** 移动文件夹到新父（null = 移到根）。单事务内重写整棵子树 path，并防环。 */
    @Transactional
    public CatalogNode move(Long id, Long newParentId) {
        CatalogNode node = requireNode(id);
        String oldPath = node.getPath();
        String newParentPath = "/";

        if (newParentId != null) {
            if (newParentId.equals(id)) {
                throw new CatalogException(CatalogException.CYCLE, "不能把文件夹移动到自身之下");
            }
            CatalogNode newParent = requireNode(newParentId);
            if (!newParent.getProjectId().equals(node.getProjectId())) {
                throw new CatalogException(CatalogException.INVALID, "不能跨项目移动");
            }
            // 防环：新父若是自身或后代，其 path 必以 oldPath 为前缀
            if (newParent.getPath().startsWith(oldPath)) {
                throw new CatalogException(CatalogException.CYCLE, "不能把文件夹移动到其自身的后代之下");
            }
            newParentPath = newParent.getPath();
        }

        String newPath = newParentPath + node.getId() + "/";

        // 子树 path 前缀批量重写（含本节点自身：SUBSTRING(oldPath, oldLen+1)="" → 得 newPath）
        // 两库通用：CONCAT 拼接 + 两参 SUBSTRING；path 仅数字与 '/'，LIKE 无需 ESCAPE
        jdbcTemplate.update(
                "UPDATE catalog_node SET path = CONCAT(?, SUBSTRING(path, ?)) "
                        + "WHERE path LIKE ? AND deleted = 0 AND project_id = ?",
                newPath, oldPath.length() + 1, oldPath + "%", node.getProjectId());

        // 更新本节点 parent_id（path 已被上面的批量 UPDATE 改好）
        jdbcTemplate.update(
                "UPDATE catalog_node SET parent_id = ?, updated_at = ? WHERE id = ?",
                newParentId, LocalDateTime.now(), id);

        return requireNode(id);
    }

    // ─── Delete（非空禁删）──────────────────────────────

    /** 删除文件夹：有子文件夹或已归类资产则抛 NOT_EMPTY。空文件夹软删。 */
    @Transactional
    public void delete(Long id) {
        CatalogNode node = requireNode(id);
        if (!repository.findByParentIdAndDeleted(id, 0).isEmpty()) {
            throw new CatalogException(CatalogException.NOT_EMPTY, "文件夹下仍有子文件夹，请先清空");
        }
        int assets = assetCount("task_def", id) + assetCount("workflow_def", id);
        if (assets > 0) {
            throw new CatalogException(CatalogException.NOT_EMPTY, "文件夹下仍有任务或工作流，请先移走");
        }
        node.setDeleted(1);
        node.setUpdatedAt(LocalDateTime.now());
        repository.save(node);
    }

    private int assetCount(String table, Long nodeId) {
        Integer c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE catalog_node_id = ? AND deleted = 0",
                Integer.class, nodeId);
        return c != null ? c : 0;
    }

    // ─── Helpers ─────────────────────────────────────────

    /** 加载节点，不存在或已删则抛 NOT_FOUND。 */
    public CatalogNode requireNode(Long id) {
        return repository.findById(id)
                .filter(n -> n.getDeleted() == null || n.getDeleted() == 0)
                .orElseThrow(() -> new CatalogException(CatalogException.NOT_FOUND, "文件夹不存在: " + id));
    }
}
