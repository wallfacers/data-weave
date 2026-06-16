package com.dataweave.master.domain;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

public interface EntityTagRepository extends CrudRepository<EntityTag, Long> {

    Optional<EntityTag> findByTagIdAndEntityTypeAndEntityId(Long tagId, String entityType, Long entityId);

    List<EntityTag> findByEntityTypeAndEntityId(String entityType, Long entityId);

    List<EntityTag> findByTagId(Long tagId);

    /** 删除某资产的指定标签关联。 */
    void deleteByTagIdAndEntityTypeAndEntityId(Long tagId, String entityType, Long entityId);

    /** 删除标签时级联清其所有关联。 */
    @Modifying
    @Query("DELETE FROM entity_tag WHERE tag_id = :tagId")
    void deleteByTagId(@Param("tagId") Long tagId);
}
