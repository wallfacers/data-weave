package com.dataweave.master.application.lineage.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.dataweave.master.application.lineage.ColumnLineageStoreAdapter;
import com.dataweave.master.application.lineage.ColumnLineageResult;
import com.dataweave.master.application.lineage.LineageEdgeAssembler;
import com.dataweave.master.domain.lineage.ColumnEdge;
import com.dataweave.master.domain.lineage.Confidence;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.Source;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 脚本血缘编排器（041 核心）。聚合可插拔抽取器产物 → 三级冲突消解 → 人工裁决重放 → 坐标解析出边。
 *
 * <p>不变量：
 * <ul>
 *   <li><b>零阻断</b>（FR-005）：单抽取器异常/超时只损失该通道产物（TIMEOUT/PARSE_FAIL hint 留痕），
 *       本方法绝不外抛；调用方仍按血缘惯例 try-catch 包裹。</li>
 *   <li><b>优先序</b>（FR-009/FR-012）：同 (方向, 表) 键 SCRIPT_SQL &gt; SCRIPT_INFERRED &gt; SCRIPT_MODEL，
 *       低优先通道不覆盖高优先结果。</li>
 *   <li><b>裁决重放</b>（FR-007）：REMOVED 键过滤不入图；CONFIRMED 键置信度升级。</li>
 *   <li><b>时间预算</b>：整体默认 2s（{@code lineage.script.timeout-ms}），超时收下已完成通道的产物。</li>
 * </ul>
 */
@Service
public class ScriptLineageService {

    private static final Logger log = LoggerFactory.getLogger(ScriptLineageService.class);

    /** 通道优先序（下标越小越优先）。 */
    private static final List<Source> CHANNEL_PRIORITY =
            List.of(Source.SCRIPT_SQL, Source.SCRIPT_INFERRED, Source.SCRIPT_MODEL);

    /** 抽取专用线程池：守护线程，容量小（push 内同步调用，无并发风暴面）。 */
    private static final ExecutorService POOL = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "script-lineage-extract");
        t.setDaemon(true);
        return t;
    });

    private final List<ScriptLineageExtractor> extractors;
    private final LineageEdgeAssembler assembler;
    private final ObjectProvider<ScriptLineageCorrectionGate> correctionGate;
    private final ObjectProvider<com.dataweave.master.domain.lineage.LineageHintRepository> hintRepository;
    private final long timeoutMs;

    public ScriptLineageService(List<ScriptLineageExtractor> extractors,
                                LineageEdgeAssembler assembler,
                                ObjectProvider<ScriptLineageCorrectionGate> correctionGate,
                                ObjectProvider<com.dataweave.master.domain.lineage.LineageHintRepository> hintRepository,
                                @Value("${lineage.script.timeout-ms:2000}") long timeoutMs) {
        this.extractors = extractors;
        this.assembler = assembler;
        this.correctionGate = correctionGate;
        this.hintRepository = hintRepository;
        this.timeoutMs = timeoutMs;
    }

    /** 编排产物：可直接并入 {@code LineageStore.recordTaskIo} 的域层边 + 待落库提示。 */
    public record Result(List<IoEdge> ioEdges, List<ColumnEdge> columnEdges,
                         List<ScriptExtraction.Hint> hints) {
        public static Result empty() {
            return new Result(List.of(), List.of(), List.of());
        }
        public boolean isEmpty() {
            return ioEdges.isEmpty() && columnEdges.isEmpty() && hints.isEmpty();
        }
    }

    /** 是否为脚本血缘处理的任务类型（调用方分叉用；SQL 走既有链路不经此处）。 */
    public boolean handles(String taskType) {
        if (taskType == null) {
            return false;
        }
        String t = taskType.toUpperCase(Locale.ROOT);
        return "PYTHON".equals(t) || "SHELL".equals(t) || "SPARK".equals(t);
    }

    public Result extract(ScriptSource source) {
        if (source == null || source.content() == null || source.content().isBlank()) {
            return Result.empty();
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        List<ScriptExtraction> collected = new ArrayList<>();
        List<ScriptExtraction.Hint> hints = new ArrayList<>();
        for (ScriptLineageExtractor extractor : extractors) {
            try {
                if (!extractor.supports(source.taskType())) {
                    continue;
                }
            } catch (Exception e) {
                continue; // supports() 异常按不支持处理
            }
            long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remainingMs <= 0) {
                hints.add(new ScriptExtraction.Hint(ScriptExtraction.HintKind.TIMEOUT, 0,
                        "budget exhausted before " + extractor.getClass().getSimpleName()));
                continue;
            }
            Future<ScriptExtraction> future = POOL.submit(() -> extractor.extract(source));
            try {
                ScriptExtraction ex = future.get(remainingMs, TimeUnit.MILLISECONDS);
                if (ex != null) {
                    collected.add(ex);
                    if (ex.hints() != null) {
                        hints.addAll(ex.hints());
                    }
                }
            } catch (TimeoutException te) {
                future.cancel(true);
                hints.add(new ScriptExtraction.Hint(ScriptExtraction.HintKind.TIMEOUT, 0,
                        extractor.getClass().getSimpleName() + " timeout"));
            } catch (Exception e) {
                log.warn("[ScriptLineage] extractor {} failed (FR-005 degrade): {}",
                        extractor.getClass().getSimpleName(), e.toString());
                hints.add(new ScriptExtraction.Hint(ScriptExtraction.HintKind.PARSE_FAIL, 0,
                        extractor.getClass().getSimpleName() + ": " + abbreviate(e.toString())));
            }
        }
        Result result = collected.isEmpty()
                ? new Result(List.of(), List.of(), List.copyOf(hints))
                : assembleResult(source, collected, hints);
        persistHints(source, result.hints());
        return result;
    }

    /** hint 落库：replace-per-task（FR-006/FR-008）；失败降级不阻断（FR-005）。 */
    private void persistHints(ScriptSource source, List<ScriptExtraction.Hint> hints) {
        var repo = hintRepository.getIfAvailable();
        if (repo == null || source.taskDefId() == null) {
            return;
        }
        try {
            repo.deleteForTask(source.tenantId(), source.projectId(), source.taskDefId());
            for (ScriptExtraction.Hint h : hints) {
                repo.save(new com.dataweave.master.domain.lineage.LineageUnresolvedHint(
                        source.tenantId(), source.projectId(), source.taskDefId(), null,
                        h.kind().name(), "L" + h.line() + ": " + h.snippet()));
            }
        } catch (Exception e) {
            log.warn("[ScriptLineage] hint persist degraded (FR-005): {}", e.toString());
        }
    }

    /** 冲突消解 + 裁决重放 + 坐标解析。 */
    private Result assembleResult(ScriptSource source, List<ScriptExtraction> collected,
                                  List<ScriptExtraction.Hint> hints) {
        DatasourceCoord readCoord = assembler.resolveCoord(
                source.tenantId(), source.projectId(), source.datasourceId());
        DatasourceCoord writeCoord = assembler.resolveCoord(
                source.tenantId(), source.projectId(),
                source.targetDatasourceId() != null ? source.targetDatasourceId() : source.datasourceId());

        // 1. 名字级冲突消解：键 = direction|tableLower，胜者 = 最高优先通道（FR-009/FR-012）
        Map<String, Winner> winners = new LinkedHashMap<>();
        for (ScriptExtraction ex : collected) {
            for (String t : ex.reads()) {
                offer(winners, Direction.READS, t, ex);
            }
            for (String t : ex.writes()) {
                offer(winners, Direction.WRITES, t, ex);
            }
        }

        // 2. 裁决重放（US3；gate 未注入 = 无裁决）
        Map<String, String> decisions = decisions(source);

        List<IoEdge> ioEdges = new ArrayList<>();
        for (Winner w : winners.values()) {
            DatasourceCoord coord = w.direction == Direction.READS ? readCoord : writeCoord;
            String tableKey = coord.dsKey() + "|" + w.table.toLowerCase(Locale.ROOT);
            String directionKey = w.direction == Direction.READS ? "READ" : "WRITE";
            String decision = decisions.get(directionKey + "|" + tableKey);
            if (ScriptLineageCorrectionGate.STATUS_REMOVED.equals(decision)) {
                continue; // 人工剔除：抑制重放（FR-007/SC-005）
            }
            Confidence confidence = w.channel == Source.SCRIPT_SQL
                    ? Confidence.CONFIRMED : Confidence.UNVERIFIED;
            if (ScriptLineageCorrectionGate.STATUS_CONFIRMED.equals(decision)) {
                confidence = Confidence.CONFIRMED;
            }
            ioEdges.add(new IoEdge(assembler.tableRef(coord, w.table), w.direction,
                    w.channel, confidence, w.modelVersion));
        }

        // 3. 列级：仅收留表级胜者归属通道的列边（防低优先通道列边越过表级消解）
        List<ColumnEdge> columnEdges = new ArrayList<>();
        for (ScriptExtraction ex : collected) {
            if (ex.columnEdges() == null || ex.columnEdges().isEmpty()) {
                continue;
            }
            ColumnLineageResult asResult = ColumnLineageResult.parsed(ex.columnEdges(), false);
            for (ColumnEdge ce : ColumnLineageStoreAdapter.toDomain(asResult, readCoord, writeCoord)) {
                String dstLower = ce.dstTable().qualifiedName().toLowerCase(Locale.ROOT);
                Winner w = winners.get(Direction.WRITES.name() + "|" + dstLower);
                if (w != null && w.channel == ex.channel()) {
                    columnEdges.add(ce);
                }
            }
        }
        return new Result(List.copyOf(ioEdges), List.copyOf(columnEdges), List.copyOf(hints));
    }

    private Map<String, String> decisions(ScriptSource source) {
        ScriptLineageCorrectionGate gate = correctionGate.getIfAvailable();
        if (gate == null || source.taskDefId() == null) {
            return Map.of();
        }
        try {
            Map<String, String> d = gate.decisionsFor(source.tenantId(), source.projectId(), source.taskDefId());
            return d != null ? d : Map.of();
        } catch (Exception e) {
            log.warn("[ScriptLineage] correction gate failed, ignore decisions: {}", e.toString());
            return Map.of();
        }
    }

    private static void offer(Map<String, Winner> winners, Direction direction, String table,
                              ScriptExtraction ex) {
        if (table == null || table.isBlank()) {
            return;
        }
        String t = table.trim();
        String key = direction.name() + "|" + t.toLowerCase(Locale.ROOT);
        Winner cur = winners.get(key);
        if (cur == null || priority(ex.channel()) < priority(cur.channel)) {
            winners.put(key, new Winner(direction, t, ex.channel(), ex.modelVersion()));
        }
    }

    private static int priority(Source channel) {
        int i = CHANNEL_PRIORITY.indexOf(channel);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private static String abbreviate(String s) {
        return s == null ? "" : (s.length() > 200 ? s.substring(0, 200) : s);
    }

    private record Winner(Direction direction, String table, Source channel, String modelVersion) {}
}
