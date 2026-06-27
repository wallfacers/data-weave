package com.dataweave.master.filecontract.mapping;

import com.dataweave.master.domain.EntityTag;
import com.dataweave.master.domain.Tag;
import com.dataweave.master.filecontract.dto.TagsDoc;
import com.dataweave.master.filecontract.error.FileContractException;
import com.dataweave.master.filecontract.yaml.DeterministicYaml;

import java.util.*;

/**
 * Maps between {@link TagsDoc} (tags.yaml) and domain {@link Tag} + {@link EntityTag} lists.
 *
 * <p>File side: tags.yaml is the tag palette (definitions only).
 * Associations (EntityTag) are inlined as {@code tags: [name, ...]} on each entity doc.
 */
public class TagMapper {

    private final DeterministicYaml yaml;

    public TagMapper(DeterministicYaml yaml) {
        this.yaml = yaml;
    }

    // ---- Serialize (domain → file) ----

    /**
     * Build a TagsDoc from the tag palette.
     * Tags are sorted by name (D3 stable ordering).
     */
    public TagsDoc toTagsDoc(List<Tag> tags) {
        var entries = tags.stream()
                .sorted(Comparator.comparing(Tag::getName))
                .map(t -> new TagsDoc.TagEntry(t.getName(), t.getColor()))
                .toList();
        return new TagsDoc(TagsDoc.CURRENT_FORMAT_VERSION, entries);
    }

    /**
     * Serialize TagsDoc to YAML content for tags.yaml.
     */
    public String serialize(TagsDoc doc) {
        var map = DeterministicYaml.orderedMap();
        DeterministicYaml.put(map, "formatVersion", doc.formatVersion());
        var tagMaps = doc.tags().stream().map(e -> {
            var tm = DeterministicYaml.orderedMap();
            DeterministicYaml.put(tm, "name", e.name());
            DeterministicYaml.putIfPresent(tm, "color", e.color());
            return (Object) tm;
        }).toList();
        DeterministicYaml.put(map, "tags", tagMaps.isEmpty() ? List.of() : tagMaps);
        return yaml.dump(map);
    }

    // ---- Deserialize (file → domain) ----

    /**
     * Parse tags.yaml content into a list of Tag domain objects.
     */
    @SuppressWarnings("unchecked")
    public List<Tag> fromYaml(String content, String filePath) {
        var raw = yaml.load(content);
        var tags = new ArrayList<Tag>();
        var rawTags = (List<Map<String, Object>>) raw.get("tags");
        if (rawTags == null) return tags;
        for (var rt : rawTags) {
            var tag = new Tag();
            tag.setName(requiredString(rt, "name", filePath));
            tag.setColor((String) rt.get("color"));  // optional, null ok
            tags.add(tag);
        }
        return tags;
    }

    /**
     * Build EntityTag associations from an inline {@code tags: [name, ...]} list
     * and a tag-name→Tag lookup table.
     */
    public List<EntityTag> resolveEntityTags(List<String> tagNames, String entityType,
                                             Map<String, Tag> tagByName, String file) {
        if (tagNames == null || tagNames.isEmpty()) return List.of();
        return tagNames.stream().map(tn -> {
            var tag = tagByName.get(tn);
            if (tag == null) {
                throw new FileContractException(file, "tags",
                        "tag '" + tn + "' referenced but not defined in tags.yaml");
            }
            var et = new EntityTag();
            et.setTagId(tag.getId());
            et.setEntityType(entityType);
            return et;
        }).toList();
    }

    // ---- Helpers ----

    static String requiredString(Map<String, Object> map, String key, String file) {
        var val = map.get(key);
        if (val == null || (val instanceof String s && s.isBlank())) {
            throw new FileContractException(file, key, "required field '" + key + "' is missing");
        }
        return val.toString();
    }
}
