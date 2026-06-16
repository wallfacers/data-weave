package com.dataweave.api;

import com.dataweave.master.application.CatalogAssignService;
import com.dataweave.master.application.CatalogException;
import com.dataweave.master.application.CatalogTreeService;
import com.dataweave.master.application.CatalogTreeService.CatalogTree;
import com.dataweave.master.application.CatalogTreeService.TreeNode;
import com.dataweave.master.application.TagService;
import com.dataweave.master.domain.CatalogNode;
import com.dataweave.master.domain.EntityTag;
import com.dataweave.master.domain.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 类目领域服务测试（H2 profile）：建/读/移动/删树、归类、标签。
 * 移动/子树/防环 SQL 用两库通用写法（CONCAT + 两参 SUBSTRING + LIKE），本类即覆盖 H2 跑通（4.9.1）。
 * 各测试用独立高位 projectId 隔离 seed 数据。
 */
@SpringBootTest
@ActiveProfiles("h2")
class CatalogServiceTest {

    @Autowired CatalogTreeService treeService;
    @Autowired CatalogAssignService assignService;
    @Autowired TagService tagService;
    @Autowired JdbcTemplate jdbc;

    // ─── 4.1 建树 + 读树计数 + 面包屑(path) ───────────────

    @Test
    void buildTree_readCounts_andPath() {
        long project = 7001L;
        CatalogNode user = treeService.createFolder(project, null, "用户域", null);
        CatalogNode ods = treeService.createFolder(project, user.getId(), "ODS层", null);

        // path：根 /id/，子 /父id/子id/（面包屑可由 path 拆解）
        assertThat(user.getPath()).isEqualTo("/" + user.getId() + "/");
        assertThat(ods.getPath()).isEqualTo("/" + user.getId() + "/" + ods.getId() + "/");

        long t1 = insertTask(project, "ods_user_login", ods.getId());
        insertTask(project, "ods_user_logout", ods.getId());
        insertTask(project, "未分类任务", null);

        CatalogTree tree = treeService.getTree(project);
        assertThat(tree.roots()).hasSize(1);
        TreeNode root = tree.roots().get(0);
        assertThat(root.name()).isEqualTo("用户域");
        assertThat(root.children()).hasSize(1);
        TreeNode odsNode = root.children().get(0);
        assertThat(odsNode.taskCount()).isEqualTo(2);    // 直属计数
        assertThat(tree.uncategorizedTaskCount()).isEqualTo(1);
        assertThat(t1).isPositive();
    }

    // ─── 4.1 同级稳定排序 (sort_order, name) ──────────────

    @Test
    void tree_stableSort_bySortOrderThenName() {
        long project = 7002L;
        treeService.createFolder(project, null, "B无序", null);   // sort_order null → 视为 0
        treeService.createFolder(project, null, "A无序", null);   // 同 0 → 按 name
        treeService.createFolder(project, null, "置顶", -10);     // 更小 → 最前

        List<TreeNode> roots = treeService.getTree(project).roots();
        assertThat(roots).extracting(TreeNode::name).containsExactly("置顶", "A无序", "B无序");
    }

    // ─── 4.2 移动重写子树 path + 一致性 ──────────────────

    @Test
    void move_rewritesSubtreePath() {
        long project = 7003L;
        CatalogNode a = treeService.createFolder(project, null, "A", null);
        CatalogNode b = treeService.createFolder(project, a.getId(), "B", null);
        CatalogNode c = treeService.createFolder(project, b.getId(), "C", null);
        CatalogNode d = treeService.createFolder(project, null, "D", null);

        // 把 B（及其子 C）从 A 移到 D 下
        treeService.move(b.getId(), d.getId());

        CatalogNode b2 = treeService.requireNode(b.getId());
        CatalogNode c2 = treeService.requireNode(c.getId());
        assertThat(b2.getParentId()).isEqualTo(d.getId());
        assertThat(b2.getPath()).isEqualTo("/" + d.getId() + "/" + b.getId() + "/");
        // 子树 C 的 path 前缀被一并重写，parent_id/path 一致
        assertThat(c2.getPath()).isEqualTo("/" + d.getId() + "/" + b.getId() + "/" + c.getId() + "/");
        assertThat(c2.getPath()).startsWith(b2.getPath());
    }

    @Test
    void move_toRoot() {
        long project = 7004L;
        CatalogNode a = treeService.createFolder(project, null, "A", null);
        CatalogNode b = treeService.createFolder(project, a.getId(), "B", null);
        treeService.move(b.getId(), null);
        CatalogNode b2 = treeService.requireNode(b.getId());
        assertThat(b2.getParentId()).isNull();
        assertThat(b2.getPath()).isEqualTo("/" + b.getId() + "/");
    }

    // ─── 4.3 防环 ────────────────────────────────────────

    @Test
    void move_intoOwnDescendant_rejected() {
        long project = 7005L;
        CatalogNode a = treeService.createFolder(project, null, "A", null);
        CatalogNode b = treeService.createFolder(project, a.getId(), "B", null);

        assertThatThrownBy(() -> treeService.move(a.getId(), b.getId()))
                .isInstanceOf(CatalogException.class)
                .extracting("code").isEqualTo(CatalogException.CYCLE);

        assertThatThrownBy(() -> treeService.move(a.getId(), a.getId()))
                .isInstanceOf(CatalogException.class)
                .extracting("code").isEqualTo(CatalogException.CYCLE);
    }

    // ─── 4.4 非空禁删 ────────────────────────────────────

    @Test
    void delete_nonEmpty_rejected_emptyOk() {
        long project = 7006L;
        CatalogNode parent = treeService.createFolder(project, null, "父", null);
        CatalogNode child = treeService.createFolder(project, parent.getId(), "子", null);

        // 含子文件夹 → 拒
        assertThatThrownBy(() -> treeService.delete(parent.getId()))
                .isInstanceOf(CatalogException.class)
                .extracting("code").isEqualTo(CatalogException.NOT_EMPTY);

        // 子文件夹含资产 → 拒
        long task = insertTask(project, "归类任务", child.getId());
        assertThatThrownBy(() -> treeService.delete(child.getId()))
                .isInstanceOf(CatalogException.class)
                .extracting("code").isEqualTo(CatalogException.NOT_EMPTY);

        // 移走资产后空文件夹可删
        assignService.assignTask(task, null);
        treeService.delete(child.getId());
        assertThatThrownBy(() -> treeService.requireNode(child.getId()))
                .isInstanceOf(CatalogException.class);
    }

    // ─── 4.5 归类：设置/覆盖/清空 + 未分类 ───────────────

    @Test
    void assign_setOverrideClear() {
        long project = 7007L;
        CatalogNode f1 = treeService.createFolder(project, null, "F1", null);
        CatalogNode f2 = treeService.createFolder(project, null, "F2", null);
        long task = insertTask(project, "可迁移任务", null);

        assignService.assignTask(task, f1.getId());
        assertThat(catalogOf(task)).isEqualTo(f1.getId());

        assignService.assignTask(task, f2.getId());     // 覆盖（仍唯一归属）
        assertThat(catalogOf(task)).isEqualTo(f2.getId());

        assignService.assignTask(task, null);            // 清空 → 未分类
        assertThat(catalogOf(task)).isNull();
        assertThat(treeService.getTree(project).uncategorizedTaskCount()).isEqualTo(1);
    }

    // ─── 4.6 标签：唯一/幂等/删除级联 ────────────────────

    @Test
    void tags_uniqueIdempotentCascade() {
        long project = 7008L;
        Tag core = tagService.create(project, "核心", "#ff0000");
        // 项目内 name 唯一
        assertThatThrownBy(() -> tagService.create(project, "核心", null))
                .isInstanceOf(CatalogException.class)
                .extracting("code").isEqualTo(CatalogException.TAG_DUPLICATE);

        long task = insertTask(project, "带标签任务", null);
        tagService.tag(EntityTag.TYPE_TASK, task, core.getId());
        tagService.tag(EntityTag.TYPE_TASK, task, core.getId());   // 幂等
        assertThat(tagService.tagsOf(EntityTag.TYPE_TASK, task)).hasSize(1);

        // 删除标签 → 级联清关联，资产本体不受影响
        tagService.delete(core.getId());
        assertThat(tagService.tagsOf(EntityTag.TYPE_TASK, task)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM task_def WHERE id=?", Integer.class, task)).isEqualTo(1);
    }

    // ─── helpers ─────────────────────────────────────────

    private long insertTask(long projectId, String name, Long catalogNodeId) {
        jdbc.update("INSERT INTO task_def (tenant_id, project_id, name, type, status, deleted, version, catalog_node_id) "
                + "VALUES (1, ?, ?, 'SQL', 'DRAFT', 0, 0, ?)", projectId, name, catalogNodeId);
        return jdbc.queryForObject("SELECT id FROM task_def WHERE project_id=? AND name=?", Long.class, projectId, name);
    }

    private Long catalogOf(long taskId) {
        return jdbc.queryForObject("SELECT catalog_node_id FROM task_def WHERE id=?", Long.class, taskId);
    }
}
