package com.dataweave.master.filecontract;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory file tree: relative path → UTF-8 content.
 * Pure data carrier — no IO, no Spring dependency.
 */
public record ProjectFileBundle(Map<String, String> files) {

    public ProjectFileBundle {
        // defensive copy + preserve insertion order
        files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
    }

    /** Empty bundle. */
    public ProjectFileBundle() {
        this(Collections.emptyMap());
    }

    /** Number of files in the bundle. */
    public int size() {
        return files.size();
    }

    /** Get content for a path, or null if absent. */
    public String get(String path) {
        return files.get(path);
    }
}
