package com.dataweave.master.filecontract.dto;

/**
 * File shape for {@code project.yaml} (data-model §1).
 */
public record ProjectDoc(
        int formatVersion,
        String code,
        String name
) {
    public static final int CURRENT_FORMAT_VERSION = 1;
}
