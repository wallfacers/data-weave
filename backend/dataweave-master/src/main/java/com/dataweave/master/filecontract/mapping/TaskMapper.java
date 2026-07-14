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
 *   <li>{@code type} → script file extension mapping (D7); SPARK 按 sparkMode 细分（pyspark→.py, spark-sql→.sql, jar→无脚本体）</li>
 *   <li>{@code frozen} (Integer 0/1) ↔ boolean (D4)</li>
 * </ul>
 */
public class TaskMapper {

    private final DeterministicYaml yaml;
    private final ObjectMapper jsonMapper;

    /** Script extension mapping (D7): task type → file extension. SPARK default pyspark .py；spark-sql/jar 由 sparkMode 覆盖。 */
    private static final Map<String, String> TYPE_EXTENSION = Map.of(
            "SQL", ".sql",
            "SHELL", ".sh",
            "PYTHON", ".py",
            "DATA_SYNC", ".json",
            "ECHO", ".txt",
            "SPARK", ".py"   // 默认 pyspark .py；spark-sql→.sql / jar→无脚本体 由 getScriptExtension(type, sparkMode) 覆盖
    );
    private static final String DEFAULT_EXTENSION = ".txt";

    /** Server {@code content} limit; deserialize rejects (not truncates) longer scripts.
     *  059：VARCHAR(4000)→VARCHAR(1048576)（1MB；承载 DataX/SeaTunnel/Flink 真实作业体，
     *  DB 与应用层边界一致，防无界 DoS）。 */
    public static final int MAX_CONTENT_LENGTH = 1048576;

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
        // SPARK 子模式：从 params_json 中提取 _sparkMode/_jarRef/_mainClass（push 时落盘用）
        String sparkMode = sparkMeta(paramsMap, "_sparkMode");
        String jarRef = sparkMeta(paramsMap, "_jarRef");
        String mainClass = sparkMeta(paramsMap, "_mainClass");
        Map<String, Object> resourcesMap = null;
        if (task.getResourcesJson() != null && !task.getResourcesJson().isBlank()) {
            resourcesMap = parseJsonToMap(task.getResourcesJson());
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
                null,   // datasourceId → code
                null,   // targetDatasourceId → code
                paramsMap,
                null,   // tags
                sparkMode,
                jarRef,
                mainClass,
                Boolean.TRUE.equals(task.getLongRunning()) ? Boolean.TRUE : null,  // 062：长驻标记（false 时不落 yaml）
                null,   // declaredSchema — TaskDef 不存声明，仅反序列化时设
                null,   // declaredColumnLineage
                resourcesMap
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
        if (doc.frozen() != null && doc.frozen()) {
            DeterministicYaml.put(map, "frozen", true);
        }
        DeterministicYaml.putIfPresent(map, "datasource", doc.datasource());
        DeterministicYaml.putIfPresent(map, "targetDatasource", doc.targetDatasource());
        // SPARK 子模式字段（非空才写，保持 .task.yaml 清洁）
        DeterministicYaml.putIfPresent(map, "sparkMode", doc.sparkMode());
        DeterministicYaml.putIfPresent(map, "jarRef", doc.jarRef());
        DeterministicYaml.putIfPresent(map, "mainClass", doc.mainClass());
        // 062：长驻标记（非 true 不写，保持 .task.yaml 清洁，与 frozen 同风格）
        if (doc.longRunning() != null && doc.longRunning()) {
            DeterministicYaml.put(map, "longRunning", true);
        }
        if (doc.params() != null && !doc.params().isEmpty()) {
            DeterministicYaml.put(map, "params", toOrderedParamsMap(doc.params()));
        }
        // 067 声明式资源（非空才写，保持 .task.yaml 清洁，与 params 同风格）
        if (doc.resources() != null && !doc.resources().isEmpty()) {
            DeterministicYaml.put(map, "resources", toOrderedParamsMap(doc.resources()));
        }
        if (doc.tags() != null && !doc.tags().isEmpty()) {
            DeterministicYaml.put(map, "tags", doc.tags().stream().sorted().toList());
        }
        // 024 声明列 schema（round-trip）
        if (doc.declaredSchema() != null && !doc.declaredSchema().isEmpty()) {
            var schemaMap = new LinkedHashMap<String, java.util.List<? extends Map<String, String>>>();
            doc.declaredSchema().forEach((table, cols) -> {
                var colMaps = cols.stream().map(c -> {
                    var cm = new LinkedHashMap<String, String>();
                    cm.put("name", c.name());
                    cm.put("type", c.type());
                    return cm;
                }).toList();
                schemaMap.put(table, colMaps);
            });
            DeterministicYaml.put(map, "schema", schemaMap);
        }
        // 024 声明 columnLineage（round-trip）
        if (doc.declaredColumnLineage() != null && !doc.declaredColumnLineage().isEmpty()) {
            DeterministicYaml.put(map, "columnLineage", doc.declaredColumnLineage());
        }
        return yaml.dump(map);
    }

    /** Get script file extension for a task type (D7). SPARK + sparkMode 细分。 */
    public static String getScriptExtension(String type) {
        return getScriptExtension(type, null);
    }

    /** Get script file extension for a task type with an optional sparkMode (F2 round-trip fidelity). */
    public static String getScriptExtension(String type, String sparkMode) {
        String key = type != null ? type.toUpperCase() : null;
        if ("SPARK".equals(key)) {
            return switch (sparkMode != null ? sparkMode.toLowerCase() : "pyspark") {
                case "spark-sql" -> ".sql";
                case "jar" -> "";       // jar 形态无独立脚本体
                default -> ".py";       // pyspark (default)
            };
        }
        return TYPE_EXTENSION.getOrDefault(key, DEFAULT_EXTENSION);
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
        Map<String, Object> resourcesMap = optionalMap(raw, "resources", filePath);
        List<String> tags = optionalStringList(raw, "tags", filePath);
        // SPARK 子模式字段（F1 file contract）
        String sparkMode = optionalString(raw, "sparkMode", filePath);
        String jarRef = optionalString(raw, "jarRef", filePath);
        String mainClass = optionalString(raw, "mainClass", filePath);
        Boolean longRunning = optionalBool(raw, "longRunning", filePath);  // 062：长驻标记
        // 024 声明列 schema + columnLineage（两块均可选，独立）
        Map<String, java.util.List<com.dataweave.master.filecontract.dto.ColumnSchemaDecl>> declaredSchema =
                parseDeclaredSchema(raw, filePath);
        java.util.List<Map<String, String>> declaredColumnLineage =
                parseDeclaredColumnLineage(raw, filePath);
        return new TaskDoc(formatVersion, name, type, description, priority,
                timeoutSec, retryMax, frozen, datasource, targetDatasource, paramsMap, tags,
                sparkMode, jarRef, mainClass, longRunning, declaredSchema, declaredColumnLineage,
                resourcesMap);
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
        task.setLongRunning(doc.longRunning() != null && doc.longRunning());  // 062：长驻标记
        task.setContent(scriptContent);
        // params: map → canonical JSON string; SPARK sub-mode fields injected into params_json (_sparkMode etc.)
        Map<String, Object> params = doc.params() != null ? new LinkedHashMap<>(doc.params()) : new LinkedHashMap<>();
        if (doc.sparkMode() != null && !doc.sparkMode().isBlank()) {
            params.put("_sparkMode", doc.sparkMode());
        }
        if (doc.jarRef() != null && !doc.jarRef().isBlank()) {
            params.put("_jarRef", doc.jarRef());
        }
        if (doc.mainClass() != null && !doc.mainClass().isBlank()) {
            params.put("_mainClass", doc.mainClass());
        }
        if (!params.isEmpty()) {
            task.setParamsJson(mapToCanonicalJson(params));
        }
        if (doc.resources() != null && !doc.resources().isEmpty()) {
            task.setResourcesJson(mapToCanonicalJson(doc.resources()));
        }
        return task;
    }

    // ---- SPARK sub-mode helpers ----

    /** Extract a SPARK sub-mode metadata value from a params map (map may be null). */
    private static String sparkMeta(Map<String, Object> params, String key) {
        if (params == null) return null;
        Object v = params.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }

    // ---- Params JSON ↔ Map (D6) ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonToMap(String json) {
        try {
            return jsonMapper.readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            var m = new LinkedHashMap<String, Object>();
            m.put("value", json);
            return m;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toOrderedParamsMap(Map<String, Object> params) {
        // Filter out SPARK sub-mode keys from user-facing params (they live under sparkMode/jarRef/mainClass in YAML)
        var sorted = new LinkedHashMap<String, Object>();
        params.keySet().stream().sorted()
                .filter(k -> !k.startsWith("_spark") && !"_jarRef".equals(k) && !"_mainClass".equals(k))
                .forEach(k -> {
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

    // ---- 024 声明列元数据解析 ----

    /** 解析 schema 块（表名→有序列{name,type}）；缺失/格式非法→null+日志，不阻断 push。 */
    @SuppressWarnings("unchecked")
    private static Map<String, java.util.List<com.dataweave.master.filecontract.dto.ColumnSchemaDecl>>
            parseDeclaredSchema(Map<String, Object> raw, String filePath) {
        try {
            Object schemaObj = raw.get("schema");
            if (schemaObj == null) {
                return null;
            }
            if (!(schemaObj instanceof Map<?, ?> m)) {
                return null; // 格式非法，跳过
            }
            var result = new LinkedHashMap<String, java.util.List<com.dataweave.master.filecontract.dto.ColumnSchemaDecl>>();
            for (var entry : ((Map<String, Object>) m).entrySet()) {
                String tableName = entry.getKey();
                Object colsObj = entry.getValue();
                if (!(colsObj instanceof List<?> cols)) {
                    continue; // 跳过格式非法表
                }
                var colList = new ArrayList<com.dataweave.master.filecontract.dto.ColumnSchemaDecl>();
                for (Object colObj : cols) {
                    if (colObj instanceof Map<?, ?> colMap) {
                        Object nameObj = colMap.get("name");
                        Object typeObj = colMap.get("type");
                        String colName = nameObj != null ? String.valueOf(nameObj) : "";
                        String colType = typeObj != null ? String.valueOf(typeObj) : "";
                        colList.add(new com.dataweave.master.filecontract.dto.ColumnSchemaDecl(colName, colType));
                    }
                }
                if (!colList.isEmpty()) {
                    result.put(tableName, colList);
                }
            }
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null; // 声明解析失败不阻断
        }
    }

    /** 解析 columnLineage 块（{from:表.列, to:表.列} 列表）；缺失/格式非法→null+跳过。 */
    @SuppressWarnings("unchecked")
    private static java.util.List<Map<String, String>>
            parseDeclaredColumnLineage(Map<String, Object> raw, String filePath) {
        try {
            Object lineageObj = raw.get("columnLineage");
            if (lineageObj == null) {
                return null;
            }
            if (!(lineageObj instanceof List<?> l)) {
                return null; // 格式非法，跳过
            }
            var result = new ArrayList<Map<String, String>>();
            for (Object item : l) {
                if (item instanceof Map<?, ?> m) {
                    var entry = new LinkedHashMap<String, String>();
                    Object fromObj = m.get("from");
                    Object toObj = m.get("to");
                    entry.put("from", fromObj != null ? String.valueOf(fromObj) : "");
                    entry.put("to", toObj != null ? String.valueOf(toObj) : "");
                    result.add(entry);
                }
            }
            return result.isEmpty() ? null : result;
        } catch (Exception e) {
            return null;
        }
    }
}
