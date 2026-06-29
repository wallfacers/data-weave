package com.dataweave.master.application;

import com.dataweave.master.application.lineage.CalciteColumnLineage;
import com.dataweave.master.application.lineage.ColumnLineageCatalog;
import com.dataweave.master.application.lineage.ColumnLineageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 列级 SQL 血缘解析主入口。
 *
 * <p>从一条 SQL 推出「目标列 ← 源列」派生关系（{@code ColumnEdge}）。<b>绝不抛阻断异常</b>
 * （契约 C1）：任何失败按降级阶梯退回 —— 列级解析不可拖垮建任务/push 主链路。
 *
 * <p>表级抽取（{@link SqlTableExtractor}）复用于：① 得到候选表集合（源+目标）以构建 Calcite schema；
 * ② 列级完全失败时的退表级兜底（由调用方据 {@code parsed=false} 决定）。
 */
@Component
public class SqlColumnLineageExtractor {

    private static final Logger log = LoggerFactory.getLogger(SqlColumnLineageExtractor.class);

    private final SqlTableExtractor tableExtractor;
    private final CalciteColumnLineage engine = new CalciteColumnLineage();

    public SqlColumnLineageExtractor(SqlTableExtractor tableExtractor) {
        this.tableExtractor = tableExtractor;
    }

    /**
     * 从一条 SQL 推出列级派生关系。
     *
     * @param sql     任务脚本（单条或多条分号分隔）
     * @param catalog 解析期列元数据来源（由 018 提供）；可为 {@code null}（则全降级）
     * @return {@link ColumnLineageResult}（parsed/edges/degraded），永不为 {@code null}
     */
    public ColumnLineageResult extract(String sql, ColumnLineageCatalog catalog) {
        if (sql == null || sql.isBlank()) {
            return ColumnLineageResult.unparsed();
        }
        try {
            SqlTableExtractor.Result tables = tableExtractor.extract(sql);
            Set<String> candidates = new LinkedHashSet<>();
            candidates.addAll(tables.reads());
            candidates.addAll(tables.writes());
            return engine.analyze(sql, catalog, candidates);
        } catch (Throwable t) {
            // 任何异常（含 Calcite 内部错误/StackOverflow 之外的 Error 不拦）→ 退表级
            log.debug("列级解析整体失败，退表级：{}", t.toString());
            return ColumnLineageResult.unparsed();
        }
    }
}
