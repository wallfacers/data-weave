package com.dataweave.master.filecontract.yaml;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic SnakeYAML dump/load wrapper (research D1, D3).
 *
 * <p>Guarantees:
 * <ul>
 *   <li>Block style, indent 2, no {@code ---} document header</li>
 *   <li>LF line endings, single trailing newline</li>
 *   <li>No anchors/aliases (pure Map/List/scalar trees)</li>
 *   <li>No line splitting (strings stay on one line or use literal block)</li>
 *   <li>Key order follows LinkedHashMap insertion order</li>
 * </ul>
 *
 * <p>This is a pure utility — no Spring, no IO, no Jackson.
 */
public final class DeterministicYaml {

    private final Yaml dumper;
    private final Yaml loader;

    public DeterministicYaml() {
        this.dumper = createDumper();
        this.loader = new Yaml();
    }

    private static Yaml createDumper() {
        var opts = new DumperOptions();
        opts.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        opts.setIndent(2);
        opts.setIndicatorIndent(1);  // must be strictly less than indent
        opts.setWidth(Integer.MAX_VALUE);  // no line splitting (D3)
        opts.setSplitLines(false);
        opts.setPrettyFlow(false);
        opts.setExplicitStart(false);      // no --- header
        opts.setExplicitEnd(false);        // no ... footer
        opts.setCanonical(false);
        opts.setAllowReadOnlyProperties(false);
        opts.setLineBreak(DumperOptions.LineBreak.UNIX);
        opts.setProcessComments(false);
        return new Yaml(opts);
    }

    /**
     * Dump an ordered map to a deterministic YAML string.
     * The map must use LinkedHashMap for key ordering.
     */
    public String dump(Map<String, Object> data) {
        var writer = new StringWriter();
        dumper.dump(data, writer);
        var result = writer.toString();
        // SnakeYAML may append extra newline; normalize to exactly one trailing LF
        while (result.endsWith("\n\n")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * Load YAML string into a raw Map. Keys are strings, values are
     * standard YAML types (String, Integer, Long, Double, Boolean,
     * List, Map). The caller is responsible for field-level validation
     * and DTO construction.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> load(String yaml) {
        if (yaml == null || yaml.isBlank()) {
            return new LinkedHashMap<>();
        }
        var result = loader.load(yaml);
        if (result == null) {
            return new LinkedHashMap<>();
        }
        return (Map<String, Object>) result;
    }

    // ---- Ordered-map helpers (D2, D3) ----

    /** Create a new empty LinkedHashMap with string keys. */
    public static Map<String, Object> orderedMap() {
        return new LinkedHashMap<>();
    }

    /**
     * Put a value into the map iff non-null; null (= "not set") is skipped (D4/FR-012).
     * An empty string is a real value and IS written, so that "" round-trips distinct from null.
     */
    public static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /** Put a value into the map unconditionally (for required fields). */
    public static void put(Map<String, Object> map, String key, Object value) {
        map.put(key, value);
    }

    /** Create an ordered set from a list (stable ordering). */
    public static LinkedHashSet<String> orderedSet(List<String> items) {
        return new LinkedHashSet<>(items);
    }
}
