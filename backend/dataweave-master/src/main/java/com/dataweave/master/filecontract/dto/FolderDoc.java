package com.dataweave.master.filecontract.dto;

/**
 * File shape for {@code _folder.yaml} — catalog node marker (data-model §3).
 * Each catalog directory contains exactly one _folder.yaml.
 */
public record FolderDoc(
        String name,
        Integer sortOrder
) {}
