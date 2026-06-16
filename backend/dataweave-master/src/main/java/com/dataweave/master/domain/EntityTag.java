package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

/**
 * 标签-资产 多态关联（多对多）。{@code entityType ∈ {TASK, WORKFLOW}}。
 * {@code (tagId, entityType, entityId)} 联合唯一防重复打标。
 */
@Table("entity_tag")
public class EntityTag {
    /** entity_type 取值：任务。 */
    public static final String TYPE_TASK = "TASK";
    /** entity_type 取值：工作流。 */
    public static final String TYPE_WORKFLOW = "WORKFLOW";

    @Id
    private Long id;
    private Long tagId;
    private String entityType;
    private Long entityId;
    private LocalDateTime createdAt;

    public EntityTag() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTagId() { return tagId; }
    public void setTagId(Long tagId) { this.tagId = tagId; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public Long getEntityId() { return entityId; }
    public void setEntityId(Long entityId) { this.entityId = entityId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
