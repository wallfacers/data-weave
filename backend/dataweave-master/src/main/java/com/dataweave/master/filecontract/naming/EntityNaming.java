package com.dataweave.master.filecontract.naming;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Single source of truth for deterministic entity slug derivation,
 * degenerate detection, fallback hashing, and sibling-set uniqueness.
 *
 * <h3>Pipeline</h3>
 * <pre>
 *   name ──▶ slugOf ──▶ base ──▶ isDegenerate? ──YES──▶ fallbackHash ──▶ effective
 *                   │                        │
 *                   │                        NO──▶ base ──▶ effective
 *                   │
 *   (unnamed guard)
 *
 *   effective ──▶ uniquify(siblings sorted by id) ──▶ final slug
 * </pre>
 *
 * <p>This replaces three near-duplicate slug derivations that previously lived in
 * {@code ProjectSyncService.slugify()} and {@code ProjectMapper.deriveTaskSlug/deriveWorkflowSlug()}.
 * All call-sites now delegate here to eliminate identity drift (INV-4 / FR-004).
 */
public final class EntityNaming {

    private EntityNaming() {}

    /** SHA-256 is always available in every JDK. */
    private static final String SHA_256 = "SHA-256";
    /** Fallback prefix: "e" for "entity", matching the existing empty-name-fallback convention. */
    private static final String FALLBACK_PREFIX = "e";
    /** Number of hash bytes to hex-encode (4 bytes = 8 hex chars). */
    private static final int HASH_BYTES = 4;
    /** Returned when name is null/blank. */
    private static final String UNNAMED = "unnamed";

    // ═══════════════════════════════════════════════════════════════
    // Base slug derivation
    // ═══════════════════════════════════════════════════════════════

    /**
     * Derive the base slug from an entity name.
     * <ul>
     *   <li>Lowercase</li>
     *   <li>Any run of characters outside {@code [a-z0-9_-]} → single underscore</li>
     *   <li>Compress repeated underscores</li>
     *   <li>Trim leading/trailing underscores</li>
     * </ul>
     *
     * @param name entity display name (may contain CJK, punctuation, etc.)
     * @return base slug; never null, never blank (minimum "unnamed")
     */
    public static String slugOf(String name) {
        if (name == null || name.isBlank()) return UNNAMED;
        String lower = name.toLowerCase();
        String slug = lower.replaceAll("[^a-z0-9_-]+", "_")
                .replaceAll("_+", "_")
                .replaceAll("^_|_$", "");
        if (slug.isEmpty()) return UNNAMED;
        return slug;
    }

    // ═══════════════════════════════════════════════════════════════
    // Degenerate detection (FR-002: THE key fix)
    // ═══════════════════════════════════════════════════════════════

    /**
     * A base slug is <em>degenerate</em> when it contains no ASCII letter
     * or digit — it carries zero distinguishing information.
     *
     * <p>This replaces the old {@code isEmpty()} check that missed cases like
     * {@code -} / {@code --} (CJK names whose only ASCII residues are hyphens).
     *
     * @param base the result of {@link #slugOf(String)}
     * @return true if {@code base} contains no {@code [a-z0-9]} character
     */
    public static boolean isDegenerate(String base) {
        if (base == null || base.isEmpty()) return true;
        return !base.matches(".*[a-z0-9].*");
    }

    // ═══════════════════════════════════════════════════════════════
    // Fallback hash (FR-003, CL-001: pure deterministic hash)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Deterministic fallback for names whose base slug is degenerate.
     * Produces {@code "e" + hex(SHA-256(name, UTF-8))} with the first
     * {@link #HASH_BYTES} bytes hex-encoded.
     *
     * <p>This is the same format the old {@code slugify()} used for empty-name
     * fallback, extended to all degenerate names.
     *
     * @param name original entity name (used for hash input, not the slug)
     * @return fallback hash, e.g. {@code "e1702eedc"}
     */
    public static String fallbackHash(String name) {
        try {
            MessageDigest md = MessageDigest.getInstance(SHA_256);
            byte[] hash = md.digest(name.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(FALLBACK_PREFIX);
            for (int i = 0; i < HASH_BYTES; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // Every JDK ships SHA-256; this is unreachable.
            return UNNAMED;
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Effective slug
    // ═══════════════════════════════════════════════════════════════

    /**
     * The effective slug for a single entity name, before sibling-set
     * uniqueness is applied. Degenerate names fall back to a hash;
     * readable names keep their base slug (FR-011: minimal perturbation).
     *
     * @param name entity display name
     * @return non-null, portable slug
     */
    public static String effectiveSlug(String name) {
        if (name == null || name.isBlank()) return UNNAMED;
        String base = slugOf(name);
        if (isDegenerate(base)) {
            return fallbackHash(name);
        }
        return base;
    }

    // ═══════════════════════════════════════════════════════════════
    // Uniqueness guard (FR-003, INV-2, INV-3)
    // ═══════════════════════════════════════════════════════════════

    /**
     * Apply deterministic uniqueness within a sibling set (entities that
     * share the same catalog directory).
     *
     * <p>Strategy:
     * <ol>
     *   <li>Sort entries by entity id ascending (deterministic ordering)</li>
     *   <li>First entity with a given effective slug keeps it as-is</li>
     *   <li>Subsequent collisions append {@code "-<id>"} to the effective slug</li>
     *   <li>If that also collides (edge case), re-append suffix until unique</li>
     * </ol>
     *
     * <p>Because ordering is by stable entity id, the same input always produces
     * the same output (INV-3). Every returned slug is guaranteed unique within
     * the sibling set (INV-2).
     *
     * @param siblings list of (entityId, effectiveSlug) pairs for one sibling group;
     *                 caller is responsible for grouping by directory
     * @return map of entityId → unique final slug, preserving insertion order
     *         of the sorted-by-id sequence
     */
    public static Map<Long, String> uniquify(List<Map.Entry<Long, String>> siblings) {
        if (siblings == null || siblings.isEmpty()) return Map.of();

        // Sort by entity id ascending for deterministic suffix assignment
        List<Map.Entry<Long, String>> sorted = new ArrayList<>(siblings);
        sorted.sort(Map.Entry.comparingByKey());

        Map<Long, String> result = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>();

        for (var entry : sorted) {
            Long id = entry.getKey();
            String effective = entry.getValue();
            String candidate = effective;

            if (!taken.add(candidate)) {
                // Collision: append entity id as deterministic suffix
                candidate = effective + "-" + id;
                // Edge case: effective="gmv-15" collides with uniquified "gmv-15"
                // from effective="gmv", id=15. Loop until unique (bounded by set size).
                while (!taken.add(candidate)) {
                    candidate = candidate + "-" + id;
                }
            }
            result.put(id, candidate);
        }

        return result;
    }
}
