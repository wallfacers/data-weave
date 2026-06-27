package com.dataweave.master.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transient DTOs for the pull/push/diff sync API (子特性 C).
 * Pure data carriers — no persistence, no Spring dependency.
 */
public final class ProjectSyncDtos {

    private ProjectSyncDtos() {}

    /** File bundle: relative path → UTF-8 content. */
    public record SyncBundle(Map<String, String> files) {
        public SyncBundle {
            files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
        }
        public static SyncBundle of(Map<String, String> files) {
            return new SyncBundle(files);
        }
        public int size() { return files.size(); }
    }

    /** pull response data. */
    public record PullResult(
            Long projectId,
            SyncBundle bundle,
            String baseline,
            int fileCount) {}

    /** push request body. */
    public record PushCommand(
            Map<String, String> files,
            String baseline,
            boolean force,
            Integer expectedFileCount,
            String remark) {
        public PushCommand {
            if (files == null) files = Map.of();
        }
    }

    /** Entity-level counts for push result. */
    public record Counts(int task, int workflow, int catalog, int tag) {
        public static final Counts ZERO = new Counts(0, 0, 0, 0);
        public Counts plusTask()     { return new Counts(task + 1, workflow, catalog, tag); }
        public Counts plusWorkflow() { return new Counts(task, workflow + 1, catalog, tag); }
        public Counts plusCatalog()  { return new Counts(task, workflow, catalog + 1, tag); }
        public Counts plusTag()      { return new Counts(task, workflow, catalog, tag + 1); }
    }

    /** Reference to a version snapshot created by push. */
    public record SnapshotRef(String entityType, Long entityId, String name, Integer versionNo) {}

    /** push response data. */
    public record PushResult(
            Long projectId,
            Counts created,
            Counts updated,
            Counts deleted,
            List<SnapshotRef> snapshots,
            String newBaseline) {}

    /** Lightweight entity reference for diff display. */
    public record EntityRef(String entityType, String identity, String displayName) {}

    /** diff response data (read-only). */
    public record DiffPreview(
            List<EntityRef> added,
            List<EntityRef> modified,
            List<EntityRef> removed,
            boolean stale) {}
}
