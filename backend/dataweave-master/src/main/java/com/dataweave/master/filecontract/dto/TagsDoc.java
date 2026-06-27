package com.dataweave.master.filecontract.dto;

import java.util.List;

/**
 * File shape for {@code tags.yaml} — tag palette (data-model §2).
 * Associations (EntityTag) are inlined on each entity doc, not here.
 */
public record TagsDoc(
        int formatVersion,
        List<TagEntry> tags
) {
    public static final int CURRENT_FORMAT_VERSION = 1;

    public record TagEntry(String name, String color) {}
}
