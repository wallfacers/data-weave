package com.dataweave.master.application.lineage.grounding;

import com.dataweave.master.application.lineage.DatasourceBoundCatalog;
import com.dataweave.master.domain.lineage.ColumnEdge;
import com.dataweave.master.domain.lineage.Confidence;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.Source;
import com.dataweave.master.domain.lineage.TableRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 目录接地服务（055，契约 grounding-stage.md C1）。
 *
 * <p>对一批候选边执行：系统排除 + 三态存在性裁决 + 来源分类 + 处置留痕。
 * <ul>
 *   <li>所有通道产候选，均接受 {@code PRESENT} 核验（升 {@link Confidence#CONFIRMED}）；</li>
 *   <li>但 {@code ABSENT} / 系统命中的<b>剔除动作</b>只作用于推断类来源
 *       （{@link Source#SCRIPT_INFERRED}/{@link Source#SCRIPT_MODEL}/{@link Source#SCRIPT_AGENT}）；</li>
 *   <li>确定性/声明来源（Calcite 解析成功的 {@code SQL_PARSED}/{@code SCRIPT_SQL}、{@code AGENT}、
 *       {@code FORM}、{@code null}）命中 {@code ABSENT}/系统只留痕，从不剔除（FR-011，防跨数据源真表误杀）。</li>
 * </ul>
 *
 * <p><b>安全</b>：任一候选处理抛异常 → 该候选降级 RETAINED（不剔）；整体不抛（SC-004）。
 */
@Component
public class CatalogGroundingService {

    private static final Logger log = LoggerFactory.getLogger(CatalogGroundingService.class);

    private final SystemNamespaceClassifier classifier;

    public CatalogGroundingService(SystemNamespaceClassifier classifier) {
        this.classifier = classifier;
    }

    /** 接地结果：过滤/升级后的边集 + 待落审计的处置留痕。 */
    public record GroundingResult(
            List<IoEdge> ioEdges,
            List<ColumnEdge> columnEdges,
            List<GroundingDisposition> dispositions) {
    }

    public GroundingResult ground(long tenantId, long projectId, Long taskDefId,
                                  List<IoEdge> mergedIo, List<ColumnEdge> mergedCol,
                                  DatasourceBoundCatalog readCatalog, Long readDsId, String readEngine,
                                  DatasourceBoundCatalog writeCatalog, Long writeDsId, String writeEngine) {
        List<IoEdge> keptIo = new ArrayList<>();
        List<GroundingDisposition> dispositions = new ArrayList<>();
        Set<String> droppedTables = new HashSet<>();   // 被剔除表的 qualifiedName（lower），用于连带剔除列级边

        for (IoEdge edge : mergedIo) {
            try {
                boolean reads = edge.direction() == Direction.READS;
                DatasourceBoundCatalog catalog = reads ? readCatalog : writeCatalog;
                Long dsId = reads ? readDsId : writeDsId;
                String engine = reads ? readEngine : writeEngine;
                String qn = edge.table() != null ? edge.table().qualifiedName() : null;
                Source src = edge.source();
                boolean droppable = isDroppable(src);

                if (qn == null || qn.isBlank()) {
                    keptIo.add(edge);
                    continue;
                }

                // ① 系统命名空间排除（在 probe 之前，纯字符串判定）
                if (classifier.isSystem(engine, qn)) {
                    if (droppable) {
                        droppedTables.add(qn.toLowerCase(Locale.ROOT));
                        dispositions.add(disp(qn, edge.direction(), src, dsId,
                                GroundingDisposition.VERDICT_SYSTEM_EXCLUDED, GroundingDisposition.DISP_EXCLUDED,
                                "system namespace"));
                    } else {
                        keptIo.add(edge);
                        dispositions.add(disp(qn, edge.direction(), src, dsId,
                                GroundingDisposition.VERDICT_SYSTEM_EXCLUDED, GroundingDisposition.DISP_RETAINED,
                                "system namespace but deterministic — kept"));
                    }
                    continue;
                }

                // ② 三态存在性（grounding 前 evict 候选表，兑现 FR-013 重 push 失效）
                if (catalog != null) {
                    catalog.evict(qn);
                }
                TableExistence existence = catalog != null
                        ? catalog.probeExistence(tenantId, projectId, qn)
                        : TableExistence.UNKNOWN;

                switch (existence) {
                    case PRESENT -> {
                        keptIo.add(adopt(edge));
                        dispositions.add(disp(qn, edge.direction(), src, dsId,
                                GroundingDisposition.VERDICT_PRESENT, GroundingDisposition.DISP_ADOPTED,
                                "catalog present"));
                    }
                    case ABSENT -> {
                        if (droppable) {
                            droppedTables.add(qn.toLowerCase(Locale.ROOT));
                            dispositions.add(disp(qn, edge.direction(), src, dsId,
                                    GroundingDisposition.VERDICT_ABSENT, GroundingDisposition.DISP_DROPPED,
                                    "catalog absent (inferential)"));
                        } else {
                            keptIo.add(edge);
                            dispositions.add(disp(qn, edge.direction(), src, dsId,
                                    GroundingDisposition.VERDICT_ABSENT, GroundingDisposition.DISP_RETAINED,
                                    "catalog absent but deterministic — kept"));
                        }
                    }
                    case UNKNOWN -> keptIo.add(edge);   // 原样保留，零惩罚，不落审计（控噪）
                }
            } catch (Exception e) {
                // 单候选故障 → 降级保留，绝不剔除、绝不打断（SC-004）
                log.debug("[grounding] candidate degraded (retained): {}", e.toString());
                keptIo.add(edge);
            }
        }

        // ③ 连带剔除被 DROPPED/EXCLUDED 表的列级边
        List<ColumnEdge> keptCol = new ArrayList<>();
        for (ColumnEdge ce : mergedCol) {
            if (droppedTables.contains(qnLower(ce.srcTable())) || droppedTables.contains(qnLower(ce.dstTable()))) {
                continue;
            }
            keptCol.add(ce);
        }

        return new GroundingResult(keptIo, keptCol, dispositions);
    }

    /** PRESENT 采纳：confidence==UNVERIFIED 时升 CONFIRMED（catalog-verified）；其余保留原态（不掩盖 CONFLICT）。 */
    private static IoEdge adopt(IoEdge edge) {
        if (edge.confidence() == Confidence.UNVERIFIED) {
            return new IoEdge(edge.table(), edge.direction(), edge.source(), Confidence.CONFIRMED, edge.modelVersion());
        }
        return edge;
    }

    /** 可剔除来源 = 推断类通道；确定性/声明来源（含 null）不可剔（FR-011）。 */
    private static boolean isDroppable(Source s) {
        return s == Source.SCRIPT_INFERRED || s == Source.SCRIPT_MODEL || s == Source.SCRIPT_AGENT;
    }

    private static GroundingDisposition disp(String candidate, Direction dir, Source src, Long dsId,
                                             String verdict, String disposition, String reason) {
        return new GroundingDisposition(candidate, dir.name(), src, dsId, verdict, disposition, reason);
    }

    private static String qnLower(TableRef t) {
        return t != null && t.qualifiedName() != null ? t.qualifiedName().toLowerCase(Locale.ROOT) : "";
    }
}
