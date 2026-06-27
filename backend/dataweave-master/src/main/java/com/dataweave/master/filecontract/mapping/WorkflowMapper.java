package com.dataweave.master.filecontract.mapping;

import com.dataweave.master.domain.WorkflowDef;
import com.dataweave.master.domain.WorkflowEdge;
import com.dataweave.master.domain.WorkflowNode;
import com.dataweave.master.filecontract.dto.WorkflowDoc;
import com.dataweave.master.filecontract.error.FileContractException;
import com.dataweave.master.filecontract.yaml.DeterministicYaml;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * Maps between {@link WorkflowDoc} ({@code <slug>.flow.yaml}) and domain
 * {@link WorkflowDef} + {@link WorkflowNode} + {@link WorkflowEdge}.
 *
 * <p>Key transformations:
 * <ul>
 *   <li>Node {@code task} is a relative task path/slug (not a numeric id)</li>
 *   <li>Edge {@code from}/{@code to} reference node keys (not numeric ids)</li>
 *   <li>{@code STRONG} edges are omitted from file (D3); deserialize defaults to STRONG</li>
 *   <li>Schedule datetimes: ISO-8601 string ↔ LocalDateTime</li>
 *   <li>{@code preemptible}: Integer 0/1 ↔ boolean</li>
 * </ul>
 */
public class WorkflowMapper {

    private final DeterministicYaml yaml;
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public WorkflowMapper(DeterministicYaml yaml) {
        this.yaml = yaml;
    }

    // ---- Serialize (domain → file) ----

    /**
     * Build WorkflowDoc from WorkflowDef + its nodes and edges.
     */
    public WorkflowDoc toWorkflowDoc(WorkflowDef wf, List<WorkflowNode> nodes, List<WorkflowEdge> edges,
                                     Map<Long, String> nodeKeyMap, Map<Long, String> taskPathMap) {
        var schedule = new WorkflowDoc.Schedule(
                wf.getScheduleType(),
                wf.getCron(),
                formatDateTime(wf.getScheduleStart()),
                formatDateTime(wf.getScheduleEnd()),
                wf.getScheduleIntervalMs()
        );
        var nodeDocs = nodes.stream()
                .sorted(Comparator.comparing(WorkflowNode::getNodeKey))
                .map(n -> new WorkflowDoc.NodeDoc(
                        n.getNodeKey(),
                        n.getNodeType(),
                        taskPathMap.get(n.getTaskId()),
                        n.getName(),
                        n.getPosX() != null && n.getPosY() != null
                                ? List.of(n.getPosX(), n.getPosY()) : null))
                .toList();
        // Sort by node KEYS (from, to, strength) — portable & stable, independent of
        // numeric node ids (D3 / data-model §5; required for byte-stable round-trip).
        var edgeDocs = edges.stream()
                .map(e -> new WorkflowDoc.EdgeDoc(
                        nodeKeyMap.get(e.getFromNodeId()),
                        nodeKeyMap.get(e.getToNodeId()),
                        "WEAK".equals(e.getStrength()) ? "WEAK" : null))
                .sorted(Comparator.comparing(WorkflowDoc.EdgeDoc::from)
                        .thenComparing(WorkflowDoc.EdgeDoc::to)
                        .thenComparing(ed -> ed.strength() == null ? "" : ed.strength()))
                .toList();
        return new WorkflowDoc(
                WorkflowDoc.CURRENT_FORMAT_VERSION,
                wf.getName(),
                wf.getDescription(),
                schedule,
                wf.getPriority(),
                wf.getPreemptible() != null && wf.getPreemptible() == 1,
                wf.getTimeoutSec(),
                null, // tags set externally
                nodeDocs,
                edgeDocs
        );
    }

    /**
     * Serialize WorkflowDoc to YAML.
     */
    public String serialize(WorkflowDoc doc) {
        var map = DeterministicYaml.orderedMap();
        DeterministicYaml.put(map, "formatVersion", doc.formatVersion());
        DeterministicYaml.put(map, "name", doc.name());
        DeterministicYaml.putIfPresent(map, "description", doc.description());

        // schedule
        var schedMap = DeterministicYaml.orderedMap();
        DeterministicYaml.put(schedMap, "type", doc.schedule().type());
        DeterministicYaml.putIfPresent(schedMap, "cron", doc.schedule().cron());
        DeterministicYaml.putIfPresent(schedMap, "start", doc.schedule().start());
        DeterministicYaml.putIfPresent(schedMap, "end", doc.schedule().end());
        DeterministicYaml.putIfPresent(schedMap, "intervalMs", doc.schedule().intervalMs());
        DeterministicYaml.put(map, "schedule", schedMap);

        DeterministicYaml.putIfPresent(map, "priority", doc.priority());
        if (doc.preemptible() != null && doc.preemptible()) {
            DeterministicYaml.put(map, "preemptible", true);
        }
        DeterministicYaml.putIfPresent(map, "timeoutSec", doc.timeoutSec());
        if (doc.tags() != null && !doc.tags().isEmpty()) {
            DeterministicYaml.put(map, "tags", doc.tags().stream().sorted().toList());
        }
        DeterministicYaml.put(map, "nodes", serializeNodes(doc.nodes()));
        DeterministicYaml.put(map, "edges", serializeEdges(doc.edges()));
        return yaml.dump(map);
    }

    private List<Map<String, Object>> serializeNodes(List<WorkflowDoc.NodeDoc> nodes) {
        return nodes.stream().map(n -> {
            var nm = DeterministicYaml.orderedMap();
            DeterministicYaml.put(nm, "key", n.key());
            DeterministicYaml.put(nm, "type", n.type());
            if (n.task() != null) {
                DeterministicYaml.put(nm, "task", n.task());
            }
            DeterministicYaml.putIfPresent(nm, "name", n.name());
            if (n.pos() != null && n.pos().size() == 2) {
                DeterministicYaml.put(nm, "pos", n.pos());
            }
            return nm;
        }).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> serializeEdges(List<WorkflowDoc.EdgeDoc> edges) {
        return (List) edges.stream().<Map<String, Object>>map(e -> {
            var em = DeterministicYaml.orderedMap();
            DeterministicYaml.put(em, "from", e.from());
            DeterministicYaml.put(em, "to", e.to());
            if (e.strength() != null) {
                DeterministicYaml.put(em, "strength", e.strength());
            }
            return em;
        }).toList();
    }

    // ---- Deserialize (file → domain) ----

    /**
     * Parse {@code <slug>.flow.yaml} content into a WorkflowDoc.
     */
    @SuppressWarnings("unchecked")
    public WorkflowDoc fromYaml(String content, String filePath) {
        var raw = yaml.load(content);
        int formatVersion = TaskMapper.intFrom(raw, "formatVersion", filePath);
        String name = TagMapper.requiredString(raw, "name", filePath);
        String description = TaskMapper.optionalString(raw, "description", filePath);

        // schedule
        var rawSched = TaskMapper.optionalMap(raw, "schedule", filePath);
        if (rawSched == null) {
            throw new FileContractException(filePath, "schedule", "required field 'schedule' is missing");
        }
        String schedType = TagMapper.requiredString(rawSched, "type", filePath);
        String cron = TaskMapper.optionalString(rawSched, "cron", filePath);
        String start = TaskMapper.optionalString(rawSched, "start", filePath);
        String end = TaskMapper.optionalString(rawSched, "end", filePath);
        Long intervalMs = rawSched.get("intervalMs") instanceof Number n ? n.longValue() : null;
        var schedule = new WorkflowDoc.Schedule(schedType, cron, start, end, intervalMs);

        Integer priority = TaskMapper.optionalInt(raw, "priority", filePath);
        Boolean preemptible = TaskMapper.optionalBool(raw, "preemptible", filePath);
        Integer timeoutSec = TaskMapper.optionalInt(raw, "timeoutSec", filePath);
        List<String> tags = TaskMapper.optionalStringList(raw, "tags", filePath);

        // nodes
        var rawNodes = (List<Map<String, Object>>) raw.get("nodes");
        if (rawNodes == null) {
            throw new FileContractException(filePath, "nodes", "required field 'nodes' is missing");
        }
        var nodes = new ArrayList<WorkflowDoc.NodeDoc>();
        var nodeKeys = new HashSet<String>();
        for (var rn : rawNodes) {
            var nd = parseNodeDoc(rn, filePath);
            if (!nodeKeys.add(nd.key())) {
                throw new FileContractException(filePath, "nodes",
                        "duplicate node key '" + nd.key() + "'");
            }
            nodes.add(nd);
        }

        // edges
        var rawEdges = (List<Map<String, Object>>) raw.get("edges");
        var edges = new ArrayList<WorkflowDoc.EdgeDoc>();
        if (rawEdges != null) {
            for (var re : rawEdges) {
                edges.add(parseEdgeDoc(re, nodeKeys, filePath));
            }
        }

        return new WorkflowDoc(formatVersion, name, description, schedule,
                priority, preemptible, timeoutSec, tags, nodes, edges);
    }

    @SuppressWarnings("unchecked")
    private WorkflowDoc.NodeDoc parseNodeDoc(Map<String, Object> rn, String file) {
        String key = TagMapper.requiredString(rn, "key", file);
        String type = TagMapper.requiredString(rn, "type", file);
        String task = (String) rn.get("task");
        String nodeName = (String) rn.get("name");
        List<Integer> pos = null;
        var rawPos = rn.get("pos");
        if (rawPos instanceof List<?> pl && pl.size() == 2
                && pl.get(0) instanceof Number && pl.get(1) instanceof Number) {
            pos = List.of(((Number) pl.get(0)).intValue(), ((Number) pl.get(1)).intValue());
        }
        // Validate: TASK must have task, VIRTUAL must not
        if ("TASK".equals(type) && (task == null || task.isBlank())) {
            throw new FileContractException(file, "nodes." + key,
                    "TASK node must have a 'task' field");
        }
        if ("VIRTUAL".equals(type) && task != null) {
            throw new FileContractException(file, "nodes." + key,
                    "VIRTUAL node must not have a 'task' field");
        }
        return new WorkflowDoc.NodeDoc(key, type, task, nodeName, pos);
    }

    @SuppressWarnings("unchecked")
    private WorkflowDoc.EdgeDoc parseEdgeDoc(Map<String, Object> re, Set<String> nodeKeys, String file) {
        String from = TagMapper.requiredString(re, "from", file);
        String to = TagMapper.requiredString(re, "to", file);
        String strength = (String) re.get("strength");
        // Validate dangling references
        if (!nodeKeys.contains(from)) {
            throw new FileContractException(file, "edges.from",
                    "edge references non-existent node '" + from + "'");
        }
        if (!nodeKeys.contains(to)) {
            throw new FileContractException(file, "edges.to",
                    "edge references non-existent node '" + to + "'");
        }
        return new WorkflowDoc.EdgeDoc(from, to, strength);
    }

    /**
     * Convert WorkflowDoc to domain objects.
     */
    public DomainWorkflow toDomain(WorkflowDoc doc) {
        var wf = new WorkflowDef();
        wf.setName(doc.name());
        wf.setDescription(doc.description());
        wf.setScheduleType(doc.schedule().type());
        wf.setCron(doc.schedule().cron());
        wf.setScheduleStart(parseDateTime(doc.schedule().start()));
        wf.setScheduleEnd(parseDateTime(doc.schedule().end()));
        wf.setScheduleIntervalMs(doc.schedule().intervalMs());
        wf.setPriority(doc.priority());
        wf.setPreemptible(doc.preemptible() != null && doc.preemptible() ? 1 : 0);
        wf.setTimeoutSec(doc.timeoutSec());

        var nodes = new ArrayList<WorkflowNode>();
        for (var nd : doc.nodes()) {
            var node = new WorkflowNode();
            node.setNodeKey(nd.key());
            node.setNodeType(nd.type());
            node.setName(nd.name());
            if (nd.pos() != null && nd.pos().size() == 2) {
                node.setPosX(nd.pos().get(0));
                node.setPosY(nd.pos().get(1));
            }
            nodes.add(node);
        }

        var edges = new ArrayList<WorkflowEdge>();
        for (var ed : doc.edges()) {
            var edge = new WorkflowEdge();
            // fromNodeId / toNodeId are set later when node IDs are resolved
            edge.setStrength(ed.strength() != null ? ed.strength() : "STRONG");
            edges.add(edge);
        }

        return new DomainWorkflow(wf, nodes, edges);
    }

    public record DomainWorkflow(WorkflowDef workflow, List<WorkflowNode> nodes, List<WorkflowEdge> edges) {}

    // ---- Helpers ----

    private static String formatDateTime(LocalDateTime dt) {
        return dt != null ? dt.format(ISO) : null;
    }

    private static LocalDateTime parseDateTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDateTime.parse(s, ISO);
        } catch (DateTimeParseException e) {
            return null; // preserve as null for forward compat
        }
    }
}
