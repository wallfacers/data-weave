package com.dataweave.master.filecontract.mapping;

import com.dataweave.master.domain.TaskDef;
import com.dataweave.master.filecontract.dto.TaskDoc;
import com.dataweave.master.filecontract.error.FileContractException;
import com.dataweave.master.filecontract.yaml.DeterministicYaml;
import tools.jackson.databind.ObjectMapper;

import java.util.*;

/**
 * Maps between {@link TaskDoc} ({@code <slug>.task.yaml} + script file) and domain {@link TaskDef}.
 *
 * <p>Key transformations:
 * <ul>
 *   <li>{@code paramsJson} (JSON string) ↔ {@code params} (expanded YAML map, D6)</li>
 *   <li>{@code datasourceId/targetDatasourceId} (Long) ↔ {@code datasource/targetDatasource} (code string, D5/FR-009)</li>
 *   <li>{@code content} (script body) ↔ separate native-language file (FR-001)</li>
 *   <li>{@code type} → script file extension mapping (D7)</li>
 *   <li>{@code frozen} (Integer 0/1) ↔ boolean (D4)</li>
 * </ul>
 */
public class TaskMapper {

    private final DeterministicYaml yaml;
    private final ObjectMapper jsonMapper;

    /** Script extension mapping (D7): task type → file extension. */
    private static final Map<String, String> TYPE_EXTENSION = Map.of(
            "SQL", ".sql",
            "SHELL", ".sh",
            "PYTHON", ".py",
            "DATA_SYNC", ".json",
            "ECHO", ".txt"
    );
    private static final String DEFAULT_EXTENSION = ".txt";

    /** Server {@code content} VARCHAR(4000) limit; deserialize rejects (not truncates) longer scripts. */
    public static final int MAX_CONTENT_LENGTH = 4000;

    public TaskMapper(DeterministicYaml yaml, ObjectMapper jsonMapper) {
        this.yaml = yaml;
        this.jsonMapper = jsonMapper;
    }

    // ---- Serialize (domain → file) ----

    /**
     * Build TaskDoc from TaskDef. Script content is handled separately via {@link #getScriptExtension}
     * and is NOT embedded in the doc.
     */
    public TaskDoc toTaskDoc(TaskDef task) {
        Map<String, Object> paramsMap = null;
        if (task.getParamsJson() != null && !task.getParamsJson().isBlank()) {
            paramsMap = parseJsonToMap(task.getParamsJson());
        }
        return new TaskDoc(
                TaskDoc.CURRENT_FORMAT_VERSION,
                task.getName(),
                task.getType(),
                task.getDescription(),
                task.getPriority(),
                task.getTimeoutSec(),
                task.getRetryMax(),
                task.getFrozen() != null && task.getFrozen() == 1,
                null,   // datasourceId → code: resolved by C layer, stored as code
                null,   // targetDatasourceId → code
                paramsMap,
                null    // tags: set externally when caller knows tag names
        );
    }

    /**
     * Serialize TaskDoc to YAML for {@code <slug>.task.yaml}.
     */
    public String serialize(TaskDoc doc) {
        var map = DeterministicYaml.orderedMap();
        DeterministicYaml.put(map, "formatVersion", doc.formatVersion());
        DeterministicYaml.put(map, "name", doc.name());
        DeterministicYaml.put(map, "type", doc.type());
        DeterministicYaml.putIfPresent(map, "description", doc.description());
        DeterministicYaml.putIfPresent(map, "priority", doc.priority());
        DeterministicYaml.putIfPresent(map, "timeoutSec", doc.timeoutSec());
        DeterministicYaml.putIfPresent(map, "retryMax", doc.retryMax());
        // frozen: only write if true (D4: false = default = omit)
        if (doc.frozen() != null && doc.frozen()) {
            DeterministicYaml.put(map, "frozen", true);
        }
        DeterministicYaml.putIfPresent(map, "datasource", doc.datasource());
        DeterministicYaml.putIfPresent(map, "targetDatasource", doc.targetDatasource());
        if (doc.params() != null && !doc.params().isEmpty()) {
            DeterministicYaml.put(map, "params", toOrderedParamsMap(doc.params()));
        }
        if (doc.tags() != null && !doc.tags().isEmpty()) {
            DeterministicYaml.put(map, "tags", doc.tags().stream().sorted().toList());
        }
        return yaml.dump(map);
    }

    /** Get script file extension for a task type (D7). */
    public static String getScriptExtension(String type) {
        return TYPE_EXTENSION.getOrDefault(type != null ? type.toUpperCase() : null, DEFAULT_EXTENSION);
    }

    // ---- Deserialize (file → domain) ----

    /**
     * Parse {@code <slug>.task.yaml} content into a TaskDoc.
     */
    @SuppressWarnings("unchecked")
    public TaskDoc fromYaml(String content, String filePath) {
        var raw = yaml.load(content);
        int formatVersion = intFrom(raw, "formatVersion", filePath);
        String name = TagMapper.requiredString(raw, "name", filePath);
        String type = TagMapper.requiredString(raw, "type", filePath);
        String description = optionalString(raw, "description", filePath);
        Integer priority = optionalInt(raw, "priority", filePath);
        Integer timeoutSec = optionalInt(raw, "timeoutSec", filePath);
        Integer retryMax = optionalInt(raw, "retryMax", filePath);
        Boolean frozen = optionalBool(raw, "frozen", filePath);
        String datasource = optionalString(raw, "datasource", filePath);
        String targetDatasource = optionalString(raw, "targetDatasource", filePath);
        Map<String, Object> paramsMap = optionalMap(raw, "params", filePath);
        List<String> tags = optionalStringList(raw, "tags", filePath);
        return new TaskDoc(formatVersion, name, type, description, priority,
                timeoutSec, retryMax, frozen, datasource, targetDatasource, paramsMap, tags);
    }

    /**
     * Convert TaskDoc + script content to a TaskDef domain object.
     */
    public TaskDef toDomain(TaskDoc doc, String scriptContent) {
        var task = new TaskDef();
        task.setName(doc.name());
        task.setType(doc.type());
        task.setDescription(doc.description());
        task.setPriority(doc.priority());
        task.setTimeoutSec(doc.timeoutSec());
        task.setRetryMax(doc.retryMax());
        task.setFrozen(doc.frozen() != null && doc.frozen() ? 1 : 0);
        task.setContent(scriptContent);
        // params: map → canonical JSON string
        if (doc.params() != null && !doc.params().isEmpty()) {
            task.setParamsJson(mapToCanonicalJson(doc.params()));
        }
        // datasource: code is preserved as-is; id resolution is C's job
        // (datasourceId field stays null from file; C fills it during ingest)
        return task;
    }

    // ---- Params JSON ↔ Map (D6) ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonToMap(String json) {
        try {
            return jsonMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            // Non-JSON string — store as single key
            var m = new LinkedHashMap<String, Object>();
            m.put("value", json);
            return m;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toOrderedParamsMap(Map<String, Object> params) {
        var sorted = new LinkedHashMap<String, Object>();
        params.keySet().stream().sorted().forEach(k -> {
            var v = params.get(k);
            if (v instanceof Map<?, ?> m) {
                v = toOrderedParamsMap((Map<String, Object>) m);
            }
            sorted.put(k, v);
        });
        return sorted;
    }

    private String mapToCanonicalJson(Map<String, Object> map) {
        try {
            // recursively key-sorted → deterministic canonical JSON (D6, all levels)
            return jsonMapper.writeValueAsString(toOrderedParamsMap(map));
        } catch (Exception e) {
            throw new FileContractException("params", "params",
                    "failed to serialize params map to JSON: " + e.getMessage());
        }
    }

    // ---- Located accessors (FR-015: missing→null/throw, wrong type→located error) ----

    static int intFrom(Map<String, Object> map, String key, String file) {
        var val = map.get(key);
        if (val == null) {
            throw new FileContractException(file, key, "required field '" + key + "' is missing");
        }
        if (val instanceof Number n) return n.intValue();
        throw typeError(file, key, "integer", val);
    }

    /** Optional integer: missing → null; present-but-wrong-type → located error (no silent drop). */
    static Integer optionalInt(Map<String, Object> map, String key, String file) {
        var val = map.get(key);
        if (val == null) return null;
        if (val instanceof Number n) return n.intValue();
        throw typeError(file, key, "integer", val);
    }

    static String optionalString(Map<String, Object> map, String key, String file) {
        var val = map.get(key);
        if (val == null) return null;
        if (val instanceof String s) return s;
        throw typeError(file, key, "string", val);
    }

    static Boolean optionalBool(Map<String, Object> map, String key, String file) {
        var val = map.get(key);
        if (val == null) return null;
        if (val instanceof Boolean b) return b;
        throw typeError(file, key, "boolean", val);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> optionalMap(Map<String, Object> map, String key, String file) {
        var val = map.get(key);
        if (val == null) return null;
        if (val instanceof Map<?, ?> m) return (Map<String, Object>) m;
        throw typeError(file, key, "map", val);
    }

    @SuppressWarnings("unchecked")
    static List<String> optionalStringList(Map<String, Object> map, String key, String file) {
        var val = map.get(key);
        if (val == null) return null;
        if (val instanceof List<?> l) {
            for (var item : l) {
                if (!(item instanceof String)) {
                    throw typeError(file, key, "list of strings", item);
                }
            }
            return (List<String>) l;
        }
        throw typeError(file, key, "list of strings", val);
    }

    static FileContractException typeError(String file, String key, String expected, Object actual) {
        return new FileContractException(file, key,
                "field '" + key + "' expected " + expected + ", got "
                        + (actual == null ? "null" : actual.getClass().getSimpleName()));
    }
}
