package com.dataweave.api.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Schema 版本契约测试 —— 把 spec/contracts 的 C1–C6 固化为可执行断言。
 *
 * <p>H2 内存库 + 唯一随机库名隔离 + redis health off + {@link DirtiesContext}
 * 防 seed 漂移（遵守 backend 测试隔离不变量）。</p>
 *
 * <h3>覆盖契约</h3>
 * <ul>
 *   <li><b>C1</b>：单行 + 合法 SemVer + {@code = 0.0.1}</li>
 *   <li><b>C2</b>：库内版本 = 基线常量 = 项目版本</li>
 *   <li><b>C3</b>：上下文成功启动 → H2 建库成功（双库兼容）</li>
 *   <li><b>C4</b>：db/migration/ 死目录不存在（防回退重现）</li>
 *   <li><b>C5</b>：schema.sql 不含已移除表 task_diagnosis/finding；
 *       若保留 demo-data.sql，则其 INSERT 目标表在 schema 中均存在</li>
 *   <li><b>C6</b>：既有 api 集成测试套件全绿 = 结构与代码一致、无漂移（复用回归基线，不新增重复断言）</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("h2")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:dataweave-schemaver;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE"
})
@DisplayName("Schema 版本契约 (C1–C6)")
class SchemaVersionIT {

    /** 基线 schema 版本 = 项目发布版本（去 -SNAPSHOT）。023 升至 0.3.0（占位，合并期定终值）。 */
    static final String BASELINE_VERSION = "0.3.0";

    /** 合法 SemVer 正则：MAJOR.MINOR.PATCH，纯数字。 */
    static final Pattern SEMVER_PATTERN = Pattern.compile("^\\d+\\.\\d+\\.\\d+$");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ResourceLoader resourceLoader;

    // ═══════════════════════════════════════════════════════════════
    // C1: 库内可查唯一版本
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("C1 schema_version 恰好 1 行，version 为合法 SemVer 且 = 0.3.0，applied_at 非空")
    void c1SingleRowValidSemVerBaseline() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT version, applied_at FROM schema_version");

        assertThat(rows)
                .as("schema_version 应恰好 1 行")
                .hasSize(1);

        Map<String, Object> row = rows.get(0);
        String version = (String) row.get("version");

        assertThat(version)
                .as("version 应为合法 SemVer (MAJOR.MINOR.PATCH)")
                .matches(SEMVER_PATTERN);

        assertThat(version)
                .as("version 应等于基线 0.0.1")
                .isEqualTo(BASELINE_VERSION);

        assertThat(row.get("applied_at"))
                .as("applied_at 应非空")
                .isNotNull();
    }

    // ═══════════════════════════════════════════════════════════════
    // C2: 三处同源恒等
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("C2 库内 version = 基线常量 0.3.0（= 项目发布版本）")
    void c2VersionEqualsProjectVersion() {
        String dbVersion = jdbc.queryForObject(
                "SELECT version FROM schema_version", String.class);

        assertThat(dbVersion)
                .as("库内 schema_version.version 应等于基线常量（= 项目发布版本）")
                .isEqualTo(BASELINE_VERSION);
    }

    // ═══════════════════════════════════════════════════════════════
    // C3: 启动建库成功 + 双库兼容
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("C3 上下文成功启动 → H2 建库成功（双库兼容）")
    void c3ContextStartedSuccessfully() {
        // @SpringBootTest 上下文成功加载即证明 H2 建库成功。
        // 额外验证 schema_version 表确实存在且可查。
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM schema_version", Integer.class);

        assertThat(count)
                .as("上下文成功启动后 schema_version 应存在且可查")
                .isEqualTo(1);
    }

    // ═══════════════════════════════════════════════════════════════
    // C4: 无孤立增量脚本
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("C4 db/migration/ 死目录不存在（防回退重现）")
    void c4NoMigrationDirectory() {
        // 以具体迁移文件检查（比检查目录更鲁棒，避免空目录残留误判）
        Resource migrationFile = resourceLoader.getResource(
                "classpath:db/migration/V__add-master-nodes-pg.sql");
        assertThat(migrationFile.exists())
                .as("classpath 上不应存在 db/migration/ 迁移文件")
                .isFalse();

        // 同时检查 db/migration 父目录也不存在
        Resource migrationDir = resourceLoader.getResource(
                "classpath:db/migration/");
        assertThat(migrationDir.exists())
                .as("classpath 上不应存在 db/migration/ 目录")
                .isFalse();
    }

    // ═══════════════════════════════════════════════════════════════
    // C5: AI 拆除残留已清
    // ═══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("C5 schema.sql 不含已移除表 task_diagnosis / finding")
    void c5NoRemovedTableReferences() throws Exception {
        Resource schemaResource = resourceLoader.getResource(
                "classpath:schema.sql");
        assertThat(schemaResource.exists())
                .as("schema.sql 应存在于 classpath")
                .isTrue();

        String content = Files.readString(Path.of(schemaResource.getURI()));

        // 按 CREATE TABLE 粒度断言：匹配 CREATE TABLE <table> 定义，避免裸子串误杀
        Pattern removedTablePattern = Pattern.compile(
                "CREATE\\s+TABLE\\s+(task_diagnosis|finding)\\b",
                Pattern.CASE_INSENSITIVE);
        assertThat(content)
                .as("schema.sql 不应包含已移除表 task_diagnosis / finding 的 CREATE TABLE 定义")
                .doesNotContainPattern(removedTablePattern);
    }

    /**
     * 若 demo-data.sql 仍保留，则其每个 INSERT 目标表应在 schema 中均存在。
     * 若已删除则本测试断言资源不存在即通过。
     */
    @Test
    @DisplayName("C5-extra 若保留 demo-data.sql，其 INSERT 目标表在 schema 中均存在")
    void c5DemoDataTargetTablesExistIfRetained() throws Exception {
        Resource demoResource = resourceLoader.getResource(
                "classpath:demo-data.sql");
        if (!demoResource.exists()) {
            // demo-data.sql 已按预期删除 —— 通过
            return;
        }

        // demo-data.sql 保留 → 核对其 INSERT 目标表
        String demoContent = Files.readString(Path.of(demoResource.getURI()));

        // 提取所有 INSERT INTO <table> 的目标表名
        Pattern insertPattern = Pattern.compile(
                "INSERT INTO (\\w+)", Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher matcher = insertPattern.matcher(demoContent);

        // 收集 schema.sql 中定义的所有表名
        Resource schemaResource = resourceLoader.getResource(
                "classpath:schema.sql");
        String schemaContent = Files.readString(Path.of(schemaResource.getURI()));

        while (matcher.find()) {
            String targetTable = matcher.group(1).toLowerCase();
            // schema.sql 应有该表的 CREATE TABLE 定义
            Pattern createPattern = Pattern.compile(
                    "CREATE TABLE " + targetTable + "\\b", Pattern.CASE_INSENSITIVE);
            assertThat(createPattern.matcher(schemaContent).find())
                    .as("demo-data.sql INSERT 目标表 %s 应在 schema.sql 中存在", targetTable)
                    .isTrue();
        }
    }
}
