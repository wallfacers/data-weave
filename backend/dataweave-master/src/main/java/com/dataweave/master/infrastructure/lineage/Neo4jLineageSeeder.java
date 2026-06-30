package com.dataweave.master.infrastructure.lineage;

import java.util.List;

import com.dataweave.master.domain.lineage.Confidence;
import com.dataweave.master.domain.lineage.DatasourceCoord;
import com.dataweave.master.domain.lineage.Direction;
import com.dataweave.master.domain.lineage.IoEdge;
import com.dataweave.master.domain.lineage.LineageStore;
import com.dataweave.master.domain.lineage.MetricEdge;
import com.dataweave.master.domain.lineage.Source;
import com.dataweave.master.domain.lineage.TableRef;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * neo4j 血缘 greenfield 种子（018 T031）。启动期幂等播种 data-model §5 数据集：
 * 1 库 / 5 表 / 3 任务(9001-9003) / 7 io 边 / 1 指标(ATOMIC#1→orders)，tenant=1 project=1。
 *
 * <p>对标 {@link Neo4jSchemaInitializer} 的韧性范式：{@code @Order(LOWEST_PRECEDENCE)} 排在约束初始化
 * 之后；neo4j 不可达时 try-catch 记 WARN 不阻断启动（血缘是增强，FR-007）。幂等完全复用
 * {@code recordTaskIo} 的 replace-per-task + {@code recordMetricLineage} 的 MERGE —— 重复 {@link #seed()}
 * 产出与单次一致，节点/边不翻倍。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class Neo4jLineageSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Neo4jLineageSeeder.class);
    private static final long T = 1L;
    private static final long P = 1L;
    /** 对齐 data.sql datasources#1 坐标（host 10.0.0.20 / port 3306 / database shop）。 */
    private static final DatasourceCoord COORD =
            new DatasourceCoord(T, P, "10.0.0.20", 3306, "shop", "orders_mysql");

    private final LineageStore lineageStore;

    public Neo4jLineageSeeder(LineageStore lineageStore) {
        this.lineageStore = lineageStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            seed();
            log.info("neo4j lineage seed applied (1 ds / 5 tables / 3 tasks / 7 io / 1 metric)");
        } catch (Exception e) {
            // neo4j 不可达：降级记日志，不阻断启动（血缘是增强，对标 Neo4jSchemaInitializer）
            log.warn("neo4j lineage seed skipped (unreachable or error): {}", e.getMessage());
        }
    }

    /** 播种 data-model §5 数据集（幂等）。public 供 IT 直接调。 */
    public void seed() {
        TableRef ods = new TableRef(COORD, "ods_order", "ODS");
        TableRef odsUser = new TableRef(COORD, "ods_user", "ODS");
        TableRef dwd = new TableRef(COORD, "dwd_order", "DWD");
        TableRef dws = new TableRef(COORD, "dws_user_order", "DWS");
        TableRef ads = new TableRef(COORD, "ads_gmv", "ADS");

        // 9001：READS ods_order(SQL_PARSED) → WRITES dwd_order(SQL_PARSED)
        lineageStore.recordTaskIo(T, P, 9001L, 1, "订单明细加工 ODS→DWD",
                List.of(io(ods, Direction.READS, Source.SQL_PARSED, Confidence.CONFIRMED),
                        io(dwd, Direction.WRITES, Source.SQL_PARSED, Confidence.CONFIRMED)),
                List.of(), null);
        // 9002：READS ods_user(SQL_PARSED) + READS dwd_order(AGENT) → WRITES dws_user_order(AGENT)
        lineageStore.recordTaskIo(T, P, 9002L, 1, "用户订单聚合 DWD→DWS",
                List.of(io(odsUser, Direction.READS, Source.SQL_PARSED, Confidence.CONFIRMED),
                        io(dwd, Direction.READS, Source.AGENT, Confidence.CONFIRMED),
                        io(dws, Direction.WRITES, Source.AGENT, Confidence.CONFIRMED)),
                List.of(), null);
        // 9003：READS dws_user_order(SQL_PARSED) → WRITES ads_gmv(AGENT/CONFLICT)
        lineageStore.recordTaskIo(T, P, 9003L, 1, "GMV 汇总 DWS→ADS",
                List.of(io(dws, Direction.READS, Source.SQL_PARSED, Confidence.CONFIRMED),
                        io(ads, Direction.WRITES, Source.AGENT, Confidence.CONFLICT)),
                List.of(), null);

        // 1 指标：ATOMIC#1 (GMV) → orders 表
        lineageStore.recordMetricLineage(
                new MetricEdge(T, P, "ATOMIC", 1L, "GMV", "TABLE", "orders"));
    }

    private static IoEdge io(TableRef t, Direction dir, Source source, Confidence confidence) {
        return new IoEdge(t, dir, source, confidence);
    }
}
