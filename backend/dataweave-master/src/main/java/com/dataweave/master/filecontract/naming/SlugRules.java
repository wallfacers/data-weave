package com.dataweave.master.filecontract.naming;

import com.dataweave.master.filecontract.error.FileContractException;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * Portable slug/name validation per FR-007a.
 * Valid characters: lowercase ASCII letters, digits, hyphen, underscore.
 * Case-only collisions within a directory are rejected.
 */
public final class SlugRules {

    private SlugRules() {}

    /** Portable character set for file/directory names: {@code [a-z0-9_-]+}. */
    private static final Pattern PORTABLE = Pattern.compile("^[a-z0-9_-]+$");

    /** Reserved file names that must not be used as entity slugs. */
    public static final Set<String> RESERVED = Set.of("project", "tags", "_folder");

    /** Basenames that are reserved regardless of extension. */
    private static final Set<String> RESERVED_BASENAMES = Set.of("project.yaml", "tags.yaml", "_folder.yaml");

    /**
     * Validate a single slug (file base name or directory name).
     *
     * @param value  the slug to validate
     * @param locus  human-readable context for error messages (e.g. "directory name")
     * @param file   the file/bundle path for error reporting
     * @throws FileContractException if the slug is invalid
     */
    public static void validateSlug(String value, String locus, String file) {
        if (value == null || value.isBlank()) {
            throw new FileContractException(file, locus, "must not be empty");
        }
        if (!PORTABLE.matcher(value).matches()) {
            throw new FileContractException(file, locus,
                    "invalid slug '" + value + "': only lowercase ASCII letters, digits, '-' and '_' allowed");
        }
    }

    /**
     * Validate that a slug is not a reserved name (e.g. "project" or "tags" used as a task slug).
     */
    public static void validateNotReserved(String slug, String file) {
        if (RESERVED.contains(slug)) {
            throw new FileContractException(file, "slug",
                    "'" + slug + "' is a reserved name and cannot be used as an entity slug");
        }
    }

    /**
     * Check that a full filename (e.g. "project.yaml", "tags.yaml") is not a reserved
     * system file being used as an entity file.
     */
    public static void validateNotReservedFilename(String filename, String file) {
        if (RESERVED_BASENAMES.contains(filename)) {
            throw new FileContractException(file, "filename",
                    "'" + filename + "' is a reserved system file");
        }
    }

    /**
     * Check for case-only collisions within a set of sibling names.
     * For example, "etl" and "ETL" in the same directory → error.
     *
     * @param names  sibling file/directory names (already lowercase-slugs)
     * @param dir    directory path for error reporting
     */
    public static void checkCaseCollisions(Iterable<String> names, String dir) {
        // All names already pass PORTABLE (lowercase), so this is mainly
        // a safeguard if we encounter mixed-case input before normalization.
        // The real collision detection happens when we lowercase external input.
        // For now, we verify uniqueness within the set — duplicates mean a collision.
        var seen = new java.util.HashSet<String>();
        for (var name : names) {
            var lower = name.toLowerCase();
            if (!seen.add(lower)) {
                throw new FileContractException(dir, "naming",
                        "case collision: '" + name + "' conflicts with another name in the same directory");
            }
        }
    }
}
