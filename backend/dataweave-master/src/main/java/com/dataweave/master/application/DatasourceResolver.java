package com.dataweave.master.application;

import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.i18n.BizException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static com.dataweave.master.application.DatasourceDtos.ConnectionTestResult;

/**
 * Resolves datasource connections for different task types.
 * <ul>
 *   <li>SQL → DataSourceRef (jdbcUrl, username, password)</li>
 *   <li>SHELL → environment variables (DW_DS_*)</li>
 *   <li>PYTHON → temp JSON file path (DW_DATASOURCE_CONFIG)</li>
 * </ul>
 */
@Service
public class DatasourceResolver {

    private final DatasourceRepository datasourceRepository;
    private final DatasourceEncryptor encryptor;
    private final ObjectMapper objectMapper;

    public DatasourceResolver(DatasourceRepository datasourceRepository,
                              DatasourceEncryptor encryptor,
                              ObjectMapper objectMapper) {
        this.datasourceRepository = datasourceRepository;
        this.encryptor = encryptor;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolve a datasource connection for the given task type.
     *
     * @return resolved connection, or null if datasourceId is null
     */
    public ResolvedConnection resolve(Long datasourceId, String taskType) {
        if (datasourceId == null) {
            return null;
        }

        Datasource ds = datasourceRepository.findById(datasourceId)
                .filter(d -> d.getDeleted() == null || d.getDeleted() == 0)
                .orElseThrow(() -> new BizException("datasource.not_found", datasourceId)
                        .withHttpStatus(404));

        String password = decryptPassword(ds);

        return switch (taskType != null ? taskType : "SQL") {
            case "SQL" -> buildSqlRef(ds, password);
            case "SHELL" -> buildShellEnvVars(ds, password);
            case "PYTHON" -> buildPythonConfig(ds, password);
            default -> buildSqlRef(ds, password); // fallback to SQL-style
        };
    }

    /**
     * Cleanup temporary files created for Python tasks.
     */
    public void cleanup(String pythonConfigPath) {
        if (pythonConfigPath == null) return;
        try {
            Files.deleteIfExists(Path.of(pythonConfigPath));
        } catch (IOException e) {
            // best-effort cleanup
        }
    }

    /** Decrypt password, wrapping DatasourceDecryptException into BizException. */
    public String decryptPassword(Datasource ds) {
        if (ds.getPasswordEnc() == null || ds.getPasswordEnc().isEmpty()) {
            return null;
        }
        try {
            return encryptor.decrypt(ds.getPasswordEnc());
        } catch (DatasourceDecryptException e) {
            throw new BizException("datasource.decrypt_failed").withHttpStatus(500);
        }
    }

    // --- Private builders ---

    private ResolvedConnection buildSqlRef(Datasource ds, String password) {
        String jdbcUrl = ds.getJdbcUrl();
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = buildJdbcUrl(ds);
        }
        return ResolvedConnection.sql(
                ds.getName(), ds.getTypeCode(), jdbcUrl, ds.getUsername(), password);
    }

    private ResolvedConnection buildShellEnvVars(Datasource ds, String password) {
        Map<String, String> env = new LinkedHashMap<>();
        String jdbcUrl = ds.getJdbcUrl();
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            jdbcUrl = buildJdbcUrl(ds);
        }
        if (jdbcUrl != null) env.put("DW_DS_URL", jdbcUrl);
        if (ds.getHost() != null) env.put("DW_DS_HOST", ds.getHost());
        if (ds.getPort() != null) env.put("DW_DS_PORT", String.valueOf(ds.getPort()));
        if (ds.getDatabaseName() != null) env.put("DW_DS_DATABASE", ds.getDatabaseName());
        if (ds.getUsername() != null) env.put("DW_DS_USER", ds.getUsername());
        if (password != null) env.put("DW_DS_PASSWORD", password);
        env.put("DW_DS_TYPE", ds.getTypeCode());
        return ResolvedConnection.shell(env);
    }

    private ResolvedConnection buildPythonConfig(Datasource ds, String password) {
        try {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("type", ds.getTypeCode());
            config.put("host", ds.getHost());
            config.put("port", ds.getPort());
            config.put("database", ds.getDatabaseName());
            config.put("username", ds.getUsername());
            config.put("password", password);
            String jdbcUrl = ds.getJdbcUrl();
            if (jdbcUrl == null || jdbcUrl.isBlank()) {
                jdbcUrl = buildJdbcUrl(ds);
            }
            config.put("jdbc_url", jdbcUrl);
            config.put("props", ds.getPropsJson());

            // Write to temp file
            String instanceId = String.valueOf(System.nanoTime());
            Path tempFile = Files.createTempFile("dw-ds-" + instanceId + "-", ".json");
            byte[] json = objectMapper.writeValueAsBytes(config);
            Files.write(tempFile, json);

            // Set permissions to owner-only (600)
            try {
                Files.setPosixFilePermissions(tempFile, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE));
            } catch (UnsupportedOperationException ignored) {
                // Non-POSIX filesystem (e.g., Windows) — skip
            }

            return ResolvedConnection.python(tempFile.toString());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create Python datasource config file", e);
        }
    }

    private String buildJdbcUrl(Datasource ds) {
        if (ds.getHost() == null) return null;
        int port = ds.getPort() != null ? ds.getPort() : 0;
        String db = ds.getDatabaseName() != null ? ds.getDatabaseName() : "";
        return switch (ds.getTypeCode()) {
            case "MYSQL", "STARROCKS", "DORIS" -> "jdbc:mysql://" + ds.getHost() + ":" + port + "/" + db;
            case "POSTGRES" -> "jdbc:postgresql://" + ds.getHost() + ":" + port + "/" + db;
            case "MARIADB" -> "jdbc:mariadb://" + ds.getHost() + ":" + port + "/" + db;
            case "HIVE" -> "jdbc:hive2://" + ds.getHost() + ":" + port + "/" + db;
            case "CLICKHOUSE" -> "jdbc:clickhouse://" + ds.getHost() + ":" + port + "/" + db;
            default -> null; // Non-JDBC types don't have URL
        };
    }

    /**
     * Resolved connection output — different fields populated based on task type.
     */
    public record ResolvedConnection(
            // SQL
            String name, String typeCode, String jdbcUrl, String username, String password,
            // Shell
            Map<String, String> shellEnvVars,
            // Python
            String pythonConfigPath
    ) {
        public static ResolvedConnection sql(String name, String typeCode, String jdbcUrl,
                                              String username, String password) {
            return new ResolvedConnection(name, typeCode, jdbcUrl, username, password, null, null);
        }

        public static ResolvedConnection shell(Map<String, String> envVars) {
            return new ResolvedConnection(null, null, null, null, null, envVars, null);
        }

        public static ResolvedConnection python(String configPath) {
            return new ResolvedConnection(null, null, null, null, null, null, configPath);
        }
    }
}
