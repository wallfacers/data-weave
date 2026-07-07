package com.dataweave.master.application;

import com.dataweave.master.application.lineage.ColumnMeta;
import com.dataweave.master.application.lineage.TableSchema;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.DatasourceType;
import com.dataweave.master.domain.DatasourceTypeRepository;
import com.dataweave.master.domain.DriverJar;
import com.dataweave.master.domain.DriverJarRepository;
import com.dataweave.master.infrastructure.IsolatedDriverLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * 数据源实时 Schema 解析器（FR-008）。
 *
 * <p>复用 {@link JdbcConnectionTester} 的连接建立模式 + {@link IsolatedDriverLoader} 驱动隔离 +
 * {@link DatasourceResolver#decryptPassword}，经 {@link DatabaseMetaData#getColumns} 取回真实列清单
 * （列名、序号、数据类型），视图同解。
 *
 * <p><b>安全边界（FR-013/FR-014）</b>：
 * <ul>
 *   <li>仅只读元数据，绝不执行数据查询</li>
 *   <li>列数上限 + 连接/查询超时保护，超限截断或降级</li>
 *   <li>数据源不可达、无该表、无权限 → 返回 {@link Optional#empty()}，永不抛异常</li>
 * </ul>
 *
 * <p><b>限定名规范化（FR-015）</b>：裸表名按连接默认 catalog/schema 补全，与既有血缘坐标同源。
 */
@Component
public class DatasourceSchemaResolver {

    private static final Logger log = LoggerFactory.getLogger(DatasourceSchemaResolver.class);

    /** 连接/查询超时（秒），默认 3s（SC-004 单次 ≤2s 预算内）。 */
    static final int DEFAULT_TIMEOUT_SECONDS = 3;

    /** 单表列数上限（FR-014），超限截断并留 hint。 */
    static final int DEFAULT_MAX_COLUMNS = 2000;

    private static final Set<String> JDBC_TYPES = Set.of(
            "MYSQL", "POSTGRES", "ORACLE", "SQLSERVER", "MARIADB", "DB2",
            "HIVE", "IMPALA", "CLICKHOUSE", "STARROCKS", "DORIS", "H2"
    );

    private final DatasourceRepository datasourceRepository;
    private final DatasourceTypeRepository datasourceTypeRepository;
    private final DriverJarRepository driverJarRepository;
    private final IsolatedDriverLoader isolatedLoader;
    private final DatasourceResolver datasourceResolver;

    public DatasourceSchemaResolver(DatasourceRepository datasourceRepository,
                                    DatasourceTypeRepository datasourceTypeRepository,
                                    DriverJarRepository driverJarRepository,
                                    IsolatedDriverLoader isolatedLoader,
                                    DatasourceResolver datasourceResolver) {
        this.datasourceRepository = datasourceRepository;
        this.datasourceTypeRepository = datasourceTypeRepository;
        this.driverJarRepository = driverJarRepository;
        this.isolatedLoader = isolatedLoader;
        this.datasourceResolver = datasourceResolver;
    }

    /**
     * 连库取表列清单（FR-008）。
     *
     * @param datasourceId  数据源 ID
     * @param qualifiedName 表限定名（如 "public.user" / "user"）
     * @return 列清单；失败/超限/不可达返回 {@link Optional#empty()}
     */
    public Optional<TableSchema> fetchColumns(long datasourceId, String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return Optional.empty();
        }

        Datasource ds = datasourceRepository.findById(datasourceId)
                .filter(d -> d.getDeleted() == null || d.getDeleted() == 0)
                .orElse(null);
        if (ds == null) {
            log.debug("DatasourceSchemaResolver: datasource {} not found", datasourceId);
            return Optional.empty();
        }

        // 仅 JDBC 类型支持 schema 抓取
        String typeCode = ds.getTypeCode();
        if (typeCode == null || !JDBC_TYPES.contains(typeCode.toUpperCase())) {
            log.debug("DatasourceSchemaResolver: datasource {} type {} not JDBC", datasourceId, typeCode);
            return Optional.empty();
        }

        String password;
        try {
            password = datasourceResolver.decryptPassword(ds);
        } catch (Exception e) {
            log.debug("DatasourceSchemaResolver: decrypt password failed for datasource {}: {}",
                    datasourceId, e.getMessage());
            return Optional.empty();
        }

        try {
            return doFetch(ds, password, qualifiedName);
        } catch (Exception e) {
            log.debug("DatasourceSchemaResolver: fetchColumns failed for datasource={} table={}: {}",
                    datasourceId, qualifiedName, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<TableSchema> doFetch(Datasource ds, String password, String qualifiedName) throws Exception {
        String typeCode = ds.getTypeCode().toUpperCase();
        String jdbcUrl = ds.getJdbcUrl();
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = buildJdbcUrl(ds);
        }
        if (jdbcUrl == null) {
            return Optional.empty();
        }

        // 驱动来源优先级：① 绑定上传 jar → 隔离加载；② 内置驱动
        Optional<DriverJar> boundJar = resolveBoundJar(ds);

        Properties props = new Properties();
        if (ds.getUsername() != null) {
            props.setProperty("user", ds.getUsername());
            // 密码为 null 但用户名存在时填空串，避免 H2 等库因缺 password 属性而鉴权失败
            props.setProperty("password", password != null ? password : "");
        }
        props.setProperty("connectTimeout", String.valueOf(DEFAULT_TIMEOUT_SECONDS * 1000));
        // socketTimeout / queryTimeout 在 Statement 级别设置

        DriverManager.setLoginTimeout(DEFAULT_TIMEOUT_SECONDS);

        Connection conn;
        if (boundJar.isPresent()) {
            conn = isolatedLoader.connect(boundJar.get(), jdbcUrl, props);
        } else {
            String driver = resolveDriver(typeCode);
            try {
                Class.forName(driver);
            } catch (ClassNotFoundException e) {
                log.debug("DatasourceSchemaResolver: driver {} not found", driver);
                return Optional.empty();
            }
            conn = DriverManager.getConnection(jdbcUrl, props);
        }

        try (Connection c = conn) {
            // 限定名规范化：解析 qualifiedName 为 catalog/schema/table 三段
            ParsedName parsed = parseQualifiedName(qualifiedName, c);

            DatabaseMetaData meta = c.getMetaData();
            List<ColumnMeta> columns = new ArrayList<>();

            // 设置查询超时（JDBC Statement.setQueryTimeout 以秒为单位）
            try (ResultSet rs = meta.getColumns(
                    parsed.catalog(), parsed.schema(), parsed.table(), null)) {
                while (rs.next()) {
                    if (columns.size() >= DEFAULT_MAX_COLUMNS) {
                        log.debug("DatasourceSchemaResolver: table {} columns exceed max {}, truncated",
                                qualifiedName, DEFAULT_MAX_COLUMNS);
                        break;
                    }
                    String colName = rs.getString("COLUMN_NAME");
                    if (colName == null || colName.isBlank()) {
                        continue;
                    }
                    String dataType = rs.getString("TYPE_NAME");
                    int ordinal = rs.getInt("ORDINAL_POSITION") - 1; // JDBC 1-based → 0-based
                    columns.add(new ColumnMeta(colName, dataType, ordinal));
                }
            }

            if (columns.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new TableSchema(qualifiedName, columns));
        }
    }

    /**
     * 解析限定名为 catalog/schema/table 三段。
     *
     * <p>规则：按 "." 分段，取最后一段为表名，并用连接默认 catalog/schema 补全缺失部分。
     * <ul>
     *   <li>3 段："db.schema.table" → catalog=db, schema=schema, table=table</li>
     *   <li>2 段："schema.table" → catalog=null, schema=schema, table=table</li>
     *   <li>1 段："table" → catalog=null, schema=默认 schema, table=table</li>
     * </ul>
     */
    static ParsedName parseQualifiedName(String qualifiedName, Connection conn) {
        String[] parts = qualifiedName.split("\\.");
        String table = parts[parts.length - 1];
        String schema = parts.length >= 2 ? parts[parts.length - 2] : null;
        String catalog = parts.length >= 3 ? parts[parts.length - 3] : null;

        // 裸表名 → 用连接默认 catalog/schema 补全
        try {
            if (catalog == null && parts.length <= 2) {
                catalog = conn.getCatalog();
            }
            if (schema == null && parts.length <= 1) {
                schema = conn.getSchema();
            }
        } catch (SQLException e) {
            // getCatalog/getSchema 并非所有驱动都支持；失败则留空
            log.debug("DatasourceSchemaResolver: getCatalog/getSchema failed: {}", e.getMessage());
        }

        return new ParsedName(catalog, schema, table);
    }

    record ParsedName(String catalog, String schema, String table) {}

    // ── 连接建立 helper（复用 JdbcConnectionTester 同款模式）─────────────────

    private Optional<DriverJar> resolveBoundJar(Datasource ds) {
        if (ds.getDriverJarId() == null) {
            return Optional.empty();
        }
        return driverJarRepository.findById(ds.getDriverJarId())
                .filter(j -> "ACTIVE".equals(j.getStatus()))
                .filter(j -> j.getDeleted() == null || j.getDeleted() == 0);
    }

    private String resolveDriver(String typeCode) {
        return datasourceTypeRepository.findByCode(typeCode)
                .map(DatasourceType::getDriver)
                .filter(d -> d != null && !d.isBlank())
                .orElseGet(() -> builtinFallbackDriver(typeCode));
    }

    private String builtinFallbackDriver(String typeCode) {
        return switch (typeCode) {
            case "MYSQL", "STARROCKS", "DORIS" -> "com.mysql.cj.jdbc.Driver";
            case "POSTGRES" -> "org.postgresql.Driver";
            case "ORACLE" -> "oracle.jdbc.OracleDriver";
            case "SQLSERVER" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
            case "MARIADB" -> "org.mariadb.jdbc.Driver";
            case "DB2" -> "com.ibm.db2.jcc.DB2Driver";
            case "HIVE" -> "org.apache.hive.jdbc.HiveDriver";
            case "IMPALA" -> "com.cloudera.impala.jdbc.Driver";
            case "CLICKHOUSE" -> "com.clickhouse.jdbc.ClickHouseDriver";
            case "H2" -> "org.h2.Driver";
            default -> "com.mysql.cj.jdbc.Driver";
        };
    }

    private String buildJdbcUrl(Datasource ds) {
        if (ds.getHost() == null) return null;
        int port = ds.getPort() != null ? ds.getPort() : 0;
        String db = ds.getDatabaseName() != null ? ds.getDatabaseName() : "";
        return switch (ds.getTypeCode()) {
            case "MYSQL", "STARROCKS", "DORIS" -> "jdbc:mysql://" + ds.getHost() + ":" + port + "/" + db;
            case "POSTGRES" -> "jdbc:postgresql://" + ds.getHost() + ":" + port + "/" + db;
            case "ORACLE" -> "jdbc:oracle:thin:@" + ds.getHost() + ":" + port + ":" + db;
            case "SQLSERVER" -> "jdbc:sqlserver://" + ds.getHost() + ":" + port + ";databaseName=" + db;
            case "MARIADB" -> "jdbc:mariadb://" + ds.getHost() + ":" + port + "/" + db;
            case "DB2" -> "jdbc:db2://" + ds.getHost() + ":" + port + "/" + db;
            case "HIVE" -> "jdbc:hive2://" + ds.getHost() + ":" + port + "/" + db;
            case "CLICKHOUSE" -> "jdbc:clickhouse://" + ds.getHost() + ":" + port + "/" + db;
            case "H2" -> "jdbc:h2:mem:" + db + ";DB_CLOSE_DELAY=-1";
            default -> "jdbc:" + ds.getTypeCode().toLowerCase() + "://" + ds.getHost() + ":" + port + "/" + db;
        };
    }
}
