package com.dataweave.master.application.lineage;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import com.dataweave.master.application.lineage.script.ScriptLineageCorrectionGate;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.LineageCorrectionRepository;
import com.dataweave.master.domain.lineage.LineageEdgeCorrection;
import com.dataweave.master.domain.lineage.LineageStore;
import com.dataweave.master.i18n.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 041 US3 人工修正服务：语义键 UPSERT / REVOKE 删行 / 抑制键集供给（{@link ScriptLineageCorrectionGate}）。
 *
 * <p>PG 行是裁决真相源（重复 push 由编排器重放）；neo4j 即时同步 best-effort（失败降级，
 * 下次 push 补齐，FR-005/FR-007）。审计由 GatedActionService 落 agent_action，本服务不重复记。
 */
@Service
public class LineageCorrectionService implements ScriptLineageCorrectionGate {

    private static final Logger log = LoggerFactory.getLogger(LineageCorrectionService.class);

    public static final String ACTION_CONFIRM = "CONFIRM";
    public static final String ACTION_REMOVE = "REMOVE";
    public static final String ACTION_REVOKE = "REVOKE";

    private final LineageCorrectionRepository repository;
    private final LineageStore lineageStore;

    public LineageCorrectionService(LineageCorrectionRepository repository, LineageStore lineageStore) {
        this.repository = repository;
        this.lineageStore = lineageStore;
    }

    /** 生效裁决视图。 */
    public record CorrectionView(Long id, String direction, String tableKey, String columnKey,
                                 String status, String operator, LocalDateTime createdAt) {
        static CorrectionView of(LineageEdgeCorrection c) {
            return new CorrectionView(c.getId(), c.getDirection(), c.getTableKey(),
                    c.getColumnKey() == null || c.getColumnKey().isBlank() ? null : c.getColumnKey(),
                    c.getStatus(), c.getOperator(), c.getCreatedAt());
        }
    }

    /**
     * 应用一次修正动作（幂等：同键同 action 重复提交返回既有裁决）。
     *
     * @param action CONFIRM / REMOVE / REVOKE
     * @return 生效裁决（REVOKE 返回 null）
     */
    public CorrectionView apply(long tenantId, long projectId, long taskDefId,
                                String action, String direction, String tableKey,
                                String columnKey, String operator) {
        String dir = normalizeDirection(direction);
        String tk = require(tableKey, "lineage.edge_not_found");
        String ck = columnKey == null ? "" : columnKey.strip().toLowerCase(Locale.ROOT);
        Optional<LineageEdgeCorrection> existing = repository
                .findByTenantIdAndProjectIdAndTaskDefIdAndDirectionAndTableKeyAndColumnKey(
                        tenantId, projectId, taskDefId, dir, tk, ck);

        switch (action == null ? "" : action.toUpperCase(Locale.ROOT)) {
            case ACTION_CONFIRM, ACTION_REMOVE -> {
                String status = action.toUpperCase(Locale.ROOT).equals(ACTION_CONFIRM)
                        ? STATUS_CONFIRMED : STATUS_REMOVED;
                LineageEdgeCorrection row = existing.orElseGet(() -> new LineageEdgeCorrection(
                        tenantId, projectId, taskDefId, dir, tk, ck, status, operator));
                if (existing.isPresent() && status.equals(row.getStatus())) {
                    return CorrectionView.of(row);   // 幂等
                }
                row.setStatus(status);
                row.setOperator(operator);
                row.setUpdatedAt(LocalDateTime.now());
                LineageEdgeCorrection saved = repository.save(row);
                syncGraph(tenantId, projectId, taskDefId, dir, tk, ck, STATUS_REMOVED.equals(status));
                return CorrectionView.of(saved);
            }
            case ACTION_REVOKE -> {
                existing.ifPresent(row -> {
                    boolean wasRemoved = STATUS_REMOVED.equals(row.getStatus());
                    repository.delete(row);
                    if (!wasRemoved) {
                        // 撤销确认：图上确认标记复原为自然推断态由下次 push 重放完成；此处不动图
                        return;
                    }
                    // 撤销剔除：边恢复由下次 push 重放自然完成（图内该边当前不存在，无法凭空重建）
                });
                return null;
            }
            default -> throw new BizException("lineage.correction_conflict", String.valueOf(action));
        }
    }

    /** 某任务当前生效裁决列表。 */
    public List<CorrectionView> listForTask(long tenantId, long projectId, long taskDefId) {
        return repository.findByTenantIdAndProjectIdAndTaskDefIdOrderByIdAsc(tenantId, projectId, taskDefId)
                .stream().map(CorrectionView::of).toList();
    }

    /** 读侧注解用：多任务裁决批量取（key = taskDefId|DIRECTION|tableKey[|columnKey] → status）。 */
    public Map<String, String> decisionsForTasks(long tenantId, long projectId, Collection<Long> taskDefIds) {
        if (taskDefIds == null || taskDefIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (LineageEdgeCorrection c : repository.findByTenantIdAndProjectIdAndTaskDefIdIn(
                tenantId, projectId, taskDefIds)) {
            out.put(c.getTaskDefId() + "|" + key(c), c.getStatus());
        }
        return out;
    }

    // ── ScriptLineageCorrectionGate（编排器重放，FR-007）──

    @Override
    public Map<String, String> decisionsFor(long tenantId, long projectId, long taskDefId) {
        Map<String, String> out = new LinkedHashMap<>();
        for (LineageEdgeCorrection c : repository.findByTenantIdAndProjectIdAndTaskDefIdOrderByIdAsc(
                tenantId, projectId, taskDefId)) {
            out.put(key(c), c.getStatus());
        }
        return out;
    }

    private static String key(LineageEdgeCorrection c) {
        String base = c.getDirection() + "|" + c.getTableKey();
        return c.getColumnKey() == null || c.getColumnKey().isBlank() ? base : base + "|" + c.getColumnKey();
    }

    private void syncGraph(long tenantId, long projectId, long taskDefId,
                           String direction, String tableKey, String columnKey, boolean remove) {
        try {
            Direction d = "READ".equals(direction) ? Direction.READS : Direction.WRITES;
            lineageStore.applyCorrection(tenantId, projectId, taskDefId, d, tableKey,
                    columnKey.isBlank() ? null : columnKey, remove);
        } catch (Exception e) {
            // 即时同步失败降级：裁决已持久，下次 push 重放补齐（FR-005）
            log.warn("[LineageCorrection] graph sync degraded: {}", e.toString());
        }
    }

    private static String normalizeDirection(String direction) {
        String d = direction == null ? "" : direction.toUpperCase(Locale.ROOT);
        if (!"READ".equals(d) && !"WRITE".equals(d)) {
            throw new BizException("lineage.edge_not_found", String.valueOf(direction));
        }
        return d;
    }

    private static String require(String v, String code) {
        if (v == null || v.isBlank()) {
            throw new BizException(code, "");
        }
        return v.strip();
    }
}
