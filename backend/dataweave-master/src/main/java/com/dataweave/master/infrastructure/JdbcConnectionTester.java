package com.dataweave.master.infrastructure;

import com.dataweave.master.application.ConnectionTester;
import com.dataweave.master.application.DatasourceDtos.ConnectionTestResult;
import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceType;
import com.dataweave.master.domain.DatasourceTypeRepository;
import com.dataweave.master.domain.DriverJar;
import com.dataweave.master.domain.DriverJarRepository;
import com.dataweave.master.i18n.Messages;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

/**
 * JDBC-family connection tester. Tests connectivity by establishing a real JDBC connection
 * and executing a validation query.
 *
 * <p>驱动来源优先级（datasource-driver-isolation）：
 * <ol>
 *   <li>数据源绑定上传 jar（{@code driver_jar_id} 非 NULL 且资产 {@code ACTIVE}）
 *       → 隔离 {@code URLClassLoader} 加载，直接 {@code driver.connect}（绕过 {@code DriverManager}）</li>
 *   <li>内置默认驱动在 classpath → {@code DriverManager.getConnection}（SPI 自动发现）</li>
 *   <li>两者均不可用 → 「驱动未安装」</li>
 * </ol>
 * 驱动类名优先取 {@code datasource_types.driver} 字段，缺失则内置兜底。
 * 文案经 {@link Messages} 按 locale 本地化（i18n 规则②）。
 */
@Component
public class JdbcConnectionTester implements ConnectionTester {

    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    private static final Set<String> JDBC_TYPES = Set.of(
            "MYSQL", "POSTGRES", "ORACLE", "SQLSERVER", "MARIADB", "DB2",
            "HIVE", "IMPALA", "CLICKHOUSE", "STARROCKS", "DORIS"
    );

    private final DatasourceTypeRepository datasourceTypeRepository;
    private final DriverJarRepository driverJarRepository;
    private final IsolatedDriverLoader isolatedLoader;
    private final Messages messages;

    public JdbcConnectionTester(DatasourceTypeRepository datasourceTypeRepository,
                                DriverJarRepository driverJarRepository,
                                IsolatedDriverLoader isolatedLoader,
                                Messages messages) {
        this.datasourceTypeRepository = datasourceTypeRepository;
        this.driverJarRepository = driverJarRepository;
        this.isolatedLoader = isolatedLoader;
        this.messages = messages;
    }

    @Override
    public boolean supports(String typeCode) {
        return JDBC_TYPES.contains(typeCode);
    }

    @Override
    public ConnectionTestResult test(Datasource ds, String decryptedPassword, Locale locale) {
        String typeCode = ds.getTypeCode();
        String jdbcUrl = ds.getJdbcUrl();
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = buildJdbcUrl(typeCode, ds.getHost(), ds.getPort(), ds.getDatabaseName());
        }

        // 驱动来源优先级 ①：绑定上传 jar（ACTIVE）→ 隔离加载；否则走内置默认。
        Optional<DriverJar> boundJar = resolveBoundJar(ds);
        boolean uploaded = boundJar.isPresent();

        Properties props = new Properties();
        if (ds.getUsername() != null) props.setProperty("user", ds.getUsername());
        if (decryptedPassword != null) props.setProperty("password", decryptedPassword);
        props.setProperty("connectTimeout", String.valueOf(DEFAULT_TIMEOUT_SECONDS * 1000));

        long startTime = System.currentTimeMillis();
        try {
            DriverManager.setLoginTimeout(DEFAULT_TIMEOUT_SECONDS);

            Connection conn;
            if (uploaded) {
                conn = isolatedLoader.connect(boundJar.get(), jdbcUrl, props);
            } else {
                String driver = resolveDriver(typeCode);
                try {
                    Class.forName(driver);
                } catch (ClassNotFoundException e) {
                    return new ConnectionTestResult(false,
                            messages.get("datasource.test.driver_missing", locale, driver), 0, null);
                }
                conn = DriverManager.getConnection(jdbcUrl, props);
            }

            try (Connection c = conn) {
                String validationQuery = resolveValidationQuery(typeCode);
                try (Statement stmt = c.createStatement();
                     ResultSet rs = stmt.executeQuery(validationQuery)) {
                    rs.next();
                }

                int latencyMs = (int) (System.currentTimeMillis() - startTime);
                String serverVersion = null;
                try {
                    serverVersion = c.getMetaData().getDatabaseProductName()
                            + " " + c.getMetaData().getDatabaseProductVersion();
                } catch (Exception ignored) {
                    // version info is optional
                }
                String sourceKey = uploaded ? "datasource.test.success_uploaded" : "datasource.test.success_builtin";
                return new ConnectionTestResult(true, messages.get(sourceKey, locale), latencyMs, serverVersion);
            }
        } catch (SQLException e) {
            int latencyMs = (int) (System.currentTimeMillis() - startTime);
            if (latencyMs >= DEFAULT_TIMEOUT_SECONDS * 1000 - 100) {
                return new ConnectionTestResult(false,
                        messages.get("datasource.test.timeout", locale, DEFAULT_TIMEOUT_SECONDS), latencyMs, null);
            }
            return new ConnectionTestResult(false,
                    messages.get("datasource.test.failed", locale, extractRootCause(e)), latencyMs, null);
        } catch (Exception e) {
            int latencyMs = (int) (System.currentTimeMillis() - startTime);
            return new ConnectionTestResult(false,
                    messages.get("datasource.test.error", locale, extractRootCause(e)), latencyMs, null);
        }
    }

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
            default -> "com.mysql.cj.jdbc.Driver";
        };
    }

    private String resolveValidationQuery(String typeCode) {
        return "ORACLE".equals(typeCode) ? "SELECT 1 FROM DUAL" : "SELECT 1";
    }

    private String buildJdbcUrl(String typeCode, String host, Integer port, String database) {
        if (host == null) return null;
        int p = port != null ? port : 0;
        String db = database != null ? database : "";
        return switch (typeCode) {
            case "MYSQL", "STARROCKS", "DORIS" -> "jdbc:mysql://" + host + ":" + p + "/" + db;
            case "POSTGRES" -> "jdbc:postgresql://" + host + ":" + p + "/" + db;
            case "ORACLE" -> "jdbc:oracle:thin:@" + host + ":" + p + ":" + db;
            case "SQLSERVER" -> "jdbc:sqlserver://" + host + ":" + p + ";databaseName=" + db;
            case "MARIADB" -> "jdbc:mariadb://" + host + ":" + p + "/" + db;
            case "DB2" -> "jdbc:db2://" + host + ":" + p + "/" + db;
            case "HIVE" -> "jdbc:hive2://" + host + ":" + p + "/" + db;
            case "CLICKHOUSE" -> "jdbc:clickhouse://" + host + ":" + p + "/" + db;
            default -> "jdbc:" + typeCode.toLowerCase() + "://" + host + ":" + p + "/" + db;
        };
    }

    private String extractRootCause(Throwable e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        return msg != null ? msg : cause.getClass().getSimpleName();
    }
}
