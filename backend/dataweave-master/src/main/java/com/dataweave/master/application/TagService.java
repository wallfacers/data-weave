package com.dataweave.master.application;

import com.dataweave.master.domain.EntityTag;
import com.dataweave.master.domain.EntityTagRepository;
import com.dataweave.master.domain.Tag;
import com.dataweave.master.domain.TagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 标签服务：标签 CRUD（项目内 name 唯一）、打标（幂等）、解绑、删除级联清关联。
 *
 * <p>写方法入参为纯领域参数，可被 REST Controller 与（二期）MCP handler 共用。
 */
@Service
public class TagService {

    private static final Long DEFAULT_TENANT = 1L;
    private static final Set<String> VALID_TYPES = Set.of(EntityTag.TYPE_TASK, EntityTag.TYPE_WORKFLOW);

    private final TagRepository tagRepository;
    private final EntityTagRepository entityTagRepository;

    public TagService(TagRepository tagRepository, EntityTagRepository entityTagRepository) {
        this.tagRepository = tagRepository;
        this.entityTagRepository = entityTagRepository;
    }

    // ─── Tag CRUD ────────────────────────────────────────

    /** 列出项目内标签（按 name 升序）。 */
    public List<Tag> list(Long projectId) {
        return tagRepository.findByProjectIdOrderByNameAsc(projectId);
    }

    /** 创建标签（项目内 name 唯一）。 */
    @Transactional
    public Tag create(Long projectId, String name, String color) {
        if (name == null || name.isBlank()) {
            throw new CatalogException(CatalogException.INVALID, "标签名不能为空");
        }
        String trimmed = name.trim();
        tagRepository.findByProjectIdAndName(projectId, trimmed).ifPresent(t -> {
            throw new CatalogException(CatalogException.TAG_DUPLICATE, "标签名已存在: " + trimmed);
        });
        LocalDateTime now = LocalDateTime.now();
        Tag tag = new Tag();
        tag.setTenantId(DEFAULT_TENANT);
        tag.setProjectId(projectId);
        tag.setName(trimmed);
        tag.setColor(color);
        tag.setCreatedAt(now);
        tag.setUpdatedAt(now);
        return tagRepository.save(tag);
    }

    /** 删除标签（硬删）+ 级联清 entity_tag 关联。 */
    @Transactional
    public void delete(Long tagId) {
        tagRepository.findById(tagId)
                .orElseThrow(() -> new CatalogException(CatalogException.NOT_FOUND, "标签不存在: " + tagId));
        entityTagRepository.deleteByTagId(tagId);
        tagRepository.deleteById(tagId);
    }

    // ─── 打标 / 解绑 ─────────────────────────────────────

    /** 给资产打标签（幂等：已存在则跳过）。 */
    @Transactional
    public void tag(String entityType, Long entityId, Long tagId) {
        String type = normalizeType(entityType);
        tagRepository.findById(tagId)
                .orElseThrow(() -> new CatalogException(CatalogException.NOT_FOUND, "标签不存在: " + tagId));
        if (entityTagRepository.findByTagIdAndEntityTypeAndEntityId(tagId, type, entityId).isPresent()) {
            return; // 幂等去重
        }
        EntityTag link = new EntityTag();
        link.setTagId(tagId);
        link.setEntityType(type);
        link.setEntityId(entityId);
        link.setCreatedAt(LocalDateTime.now());
        entityTagRepository.save(link);
    }

    /** 解绑资产的某标签。 */
    @Transactional
    public void untag(String entityType, Long entityId, Long tagId) {
        entityTagRepository.deleteByTagIdAndEntityTypeAndEntityId(tagId, normalizeType(entityType), entityId);
    }

    /** 查某资产的全部标签。 */
    public List<Tag> tagsOf(String entityType, Long entityId) {
        String type = normalizeType(entityType);
        List<Long> tagIds = entityTagRepository.findByEntityTypeAndEntityId(type, entityId)
                .stream().map(EntityTag::getTagId).toList();
        if (tagIds.isEmpty()) return List.of();
        List<Tag> result = new java.util.ArrayList<>();
        tagRepository.findAllById(tagIds).forEach(result::add);
        return result;
    }

    private String normalizeType(String entityType) {
        if (entityType == null) {
            throw new CatalogException(CatalogException.INVALID, "entityType 不能为空");
        }
        String type = entityType.trim().toUpperCase();
        if (!VALID_TYPES.contains(type)) {
            throw new CatalogException(CatalogException.INVALID, "非法 entityType: " + entityType);
        }
        return type;
    }
}
