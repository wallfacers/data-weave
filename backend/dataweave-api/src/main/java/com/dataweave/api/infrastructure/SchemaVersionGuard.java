package com.dataweave.api.infrastructure;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时强校验 DB schema 版本与代码预期版本一致。
 *
 * <p>背景：项目用单一 {@code schema.sql}（无增量迁移脚本），{@code spring.sql.init.mode=never}
 * 下 schema 变更不会自动应用到运行中的数据库。一旦 DB 版本滞后于代码实体定义，查询会因缺列/缺表
 * 抛 {@code BadSqlGrammarException}，且静默导致 cron 调度卡死（next_trigger_time 不推进）。
 *
 * <p>此 guard 在 CommandLineRunner 阶段（数据源就绪后、业务 bean 启动前）执行：
 * <ol>
 *   <li>从 {@code schema_version} 表读最新行</li>
 *   <li>与硬编码的 {@link #EXPECTED_VERSION} 比对</li>
 *   <li>不匹配 → 抛出明确 {@link IllegalStateException}，应用拒绝启动，日志写明 DB 当前版本与代码预期版本</li>
 * </ol>
 *
 * <p><b>维护规则</b>：每次修改 {@code schema.sql} 并 bump {@code schema_version} 时，<b>必须同步更新
 * 本类的 {@link #EXPECTED_VERSION}</b>。两者不一致会导致应用在所有环境拒绝启动——这是有意设计，
 * 倒逼 schema 变更为显式操作（手动执行 DDL 或临时切换 {@code spring.sql.init.mode=always}）。
 *
 * @see <a href="docs/knowledge-map.md">Knowledge Map · 删表必留 DROP</a>
 */
@Component
public class SchemaVersionGuard implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaVersionGuard.class);

    /**
     * 代码预期的 schema 版本。必须与 {@code schema.sql} 中 {@code schema_version} 表的最新
     * INSERT 值保持一致，否则应用拒绝启动。
     */
    static final String EXPECTED_VERSION = "0.20.0";

    private final JdbcTemplate jdbc;

    public SchemaVersionGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // semver 感知排序：string_to_array 把 0.20.0 → {0,20,0}，整数数组比较 20 > 8
    static final String LATEST_VERSION_SQL =
            "SELECT version FROM schema_version "
            + "ORDER BY string_to_array(version, '.')::int[] DESC LIMIT 1";

    @Override
    public void run(String... args) {
        String dbVersion;
        try {
            dbVersion = jdbc.queryForObject(LATEST_VERSION_SQL, String.class);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "[SchemaVersionGuard] 无法读取 schema_version 表——数据库可能未初始化。"
                    + "请确认 PostgreSQL 已运行且 schema.sql 已执行。", e);
        }

        if (dbVersion == null) {
            throw new IllegalStateException(
                    "[SchemaVersionGuard] schema_version 表为空——数据库未完成 schema 初始化。"
                    + "请执行 schema.sql 或临时设置 spring.sql.init.mode=always。");
        }

        if (!EXPECTED_VERSION.equals(dbVersion)) {
            String msg = String.format(
                    "[SchemaVersionGuard] DB schema 版本不匹配！"
                    + "DB 当前版本=%s，代码预期版本=%s。"
                    + "请手动执行缺失的 DDL 变更（schema.sql 中的 CREATE/ALTER），"
                    + "或在可接受数据丢失的环境临时设置 spring.sql.init.mode=always 重建库。",
                    dbVersion, EXPECTED_VERSION);
            log.error(msg);
            throw new IllegalStateException(msg);
        }

        log.info("[SchemaVersionGuard] schema 版本校验通过：DB={}", dbVersion);
    }
}
