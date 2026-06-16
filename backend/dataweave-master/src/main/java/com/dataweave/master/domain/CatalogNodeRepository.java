package com.dataweave.master.domain;

import org.springframework.data.repository.CrudRepository;
import java.util.List;

/**
 * 文件夹节点仓储（瘦）。子树/移动/计数等 path 相关 SQL 在 {@code CatalogTreeService}
 * 用 JdbcTemplate 实现（与 {@code TaskService} 风格一致，两库通用写法）。
 */
public interface CatalogNodeRepository extends CrudRepository<CatalogNode, Long> {
    List<CatalogNode> findByProjectIdAndDeleted(Long projectId, Integer deleted);

    List<CatalogNode> findByParentIdAndDeleted(Long parentId, Integer deleted);
}
