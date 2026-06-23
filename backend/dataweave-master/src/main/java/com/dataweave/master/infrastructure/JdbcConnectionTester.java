package com.dataweave.master.infrastructure;

import com.dataweave.master.application.ConnectionTester;
import com.dataweave.master.application.DatasourceDtos.ConnectionTestResult;
import com.dataweave.master.domain.Datasource;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;
import java.util.Set;

/**
 * JDBC-family connection tester. Tests connectivity by establishing a real JDBC connection
 * and executing a validation query.
 */
@Component
public class JdbcConnectionTester implements ConnectionTester {

    private static final int DEFAULT_TIMEOUT_SECONDS = 10;

    private static final Set<String> JDBC_TYPES = Set.of(
            "MYSQL", "POSTGRES", "ORACLE", "SQLSERVER", "MARIADB", "DB2",
            "HIVE", "IMPALA", "CLICKHOUSE", "STARROCKS", "DORIS"
    );

    @Override
    public boolean supports(String typeCode) {
        return JDBC_TYPES.contains(typeCode);
    }

    @Override
    public ConnectionTestResult test(Datasource ds, String decryptedPassword) {
        String typeCode = ds.getTypeCode();
        String jdbcUrl = ds.getJdbcUrl();
        String driver = resolveDriver(typeCode);

        // Check driver availability
        try {
            Class.forName(driver);
        } catch (ClassNotFoundException e) {
            return new ConnectionTestResult(
                    false,
                    "驱动未安装: " + driver + "，请添加对应驱动 jar",
                    0,
                    null
            );
        }

        // Build JDBC URL if not explicitly set
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = buildJdbcUrl(typeCode, ds.getHost(), ds.getPort(), ds.getDatabaseName());
        }

        long startTime = System.currentTimeMillis();
        int timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;

        try {
            DriverManager.setLoginTimeout(timeoutSeconds);

            Properties props = new Properties();
            if (ds.getUsername() != null) props.setProperty("user", ds.getUsername());
            if (decryptedPassword != null) props.setProperty("password", decryptedPassword);
            props.setProperty("connectTimeout", String.valueOf(timeoutSeconds * 1000));

            try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
                String validationQuery = resolveValidationQuery(typeCode);
                try (Statement stmt = conn.createStatement();
                     ResultSet rs = stmt.executeQuery(validationQuery)) {
                    rs.next();
                }

                int latencyMs = (int) (System.currentTimeMillis() - startTime);
                String serverVersion = null;
                try {
                    serverVersion = conn.getMetaData().getDatabaseProductName()
                            + " " + conn.getMetaData().getDatabaseProductVersion();
                } catch (Exception ignored) {
                    // version info is optional
                }

                return new ConnectionTestResult(true, "连接成功", latencyMs, serverVersion);
            }
        } catch (java.sql.SQLException e) {
            int latencyMs = (int) (System.currentTimeMillis() - startTime);
            if (latencyMs >= timeoutSeconds * 1000 - 100) {
                return new ConnectionTestResult(false, "连接超时 (" + timeoutSeconds + "s)", latencyMs, null);
            }
            return new ConnectionTestResult(false, "连接失败: " + extractRootCause(e), latencyMs, null);
        } catch (Exception e) {
            int latencyMs = (int) (System.currentTimeMillis() - startTime);
            return new ConnectionTestResult(false, "连接异常: " + e.getMessage(), latencyMs, null);
        }
    }

    private String resolveDriver(String typeCode) {
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
