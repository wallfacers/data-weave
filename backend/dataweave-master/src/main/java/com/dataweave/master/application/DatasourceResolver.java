package com.dataweave.master.application;

import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.DriverJar;
import com.dataweave.master.domain.DriverJarRepository;
import com.dataweave.master.i18n.BizException;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves datasource connections for different task types.
 * <ul>
 *   <li>SQL → DataSourceRef (jdbcUrl, username, password, + driverJar 元数据供隔离加载)</li>
 *   <li>SHELL → environment variables (DW_DS_*)</li>
 *   <li>PYTHON → temp JSON file path (DW_DATASOURCE_CONFIG)</li>
 * </ul>
 *
 * <p>datasource-driver-isolation：SQL 解析时若数据源绑定了上传 jar（driver_jar_id），
 * 查 driver_jars 填充 driverClass/storageKey，供 worker 隔离加载（绕过内置 DriverManager）。
 */
@Service
public class DatasourceResolver {

    private final DatasourceRepository datasourceRepository;
    private final DriverJarRepository driverJarRepository;
    private final DatasourceEncryptor encryptor;
    private final ObjectMapper objectMapper;

    public DatasourceResolver(DatasourceRepository datasourceRepository,
                              DriverJarRepository driverJarRepository,
                              DatasourceEncryptor encryptor,
                              ObjectMapper objectMapper) {
        this.datasourceRepository = datasourceRepository;
        this.driverJarRepository = driverJarRepository;
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
            case "SPARK" -> buildSparkRef(ds);
            case "FLINK" -> buildEngineRef(ds, "FLINK");
            case "DATAX" -> buildEngineRef(ds, "DATAX");
            case "SEATUNNEL" -> buildEngineRef(ds, "SEATUNNEL");
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
        // datasource-driver-isolation：绑定了上传 jar → 填 driverClass/storageKey 供 worker 隔离加载
        Long jarId = ds.getDriverJarId();
        String driverClass = null;
        String storageKey = null;
        if (jarId != null) {
            Optional<DriverJar> jar = driverJarRepository.findById(jarId)
                    .filter(j -> "ACTIVE".equals(j.getStatus()))
                    .filter(j -> j.getDeleted() == null || j.getDeleted() == 0);
            driverClass = jar.map(DriverJar::getDriverClass).orElse(null);
            storageKey = jar.map(DriverJar::getStorageKey).orElse(null);
        }
        return ResolvedConnection.sql(ds.getName(), ds.getTypeCode(), jdbcUrl, ds.getUsername(), password,
                jarId, driverClass, storageKey);
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
            Map<String, Object> config = buildPythonConfigMap(ds, password);

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

    /** PYTHON 数据源配置内容（all-in-one 落临时文件 / distributed over-wire 序列化共用）。 */
    private Map<String, Object> buildPythonConfigMap(Datasource ds, String password) {
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
        return config;
    }

    /**
     * distributed over-wire：返回 PYTHON 数据源配置 JSON 内容（不落 master 临时文件），
     * 供 worker 侧自行落盘为 {@code DW_DATASOURCE_CONFIG}（contracts C4.2，worker 不新增 DB 依赖）。
     */
    public String pythonConfigJson(Long datasourceId) {
        if (datasourceId == null) return null;
        Datasource ds = datasourceRepository.findById(datasourceId)
                .filter(d -> d.getDeleted() == null || d.getDeleted() == 0)
                .orElse(null);
        if (ds == null) return null;
        try {
            return objectMapper.writeValueAsString(buildPythonConfigMap(ds, decryptPassword(ds)));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * SPARK 数据源解析（contracts C5 / data-model §3）：从 props_json 解析
     * {@code {sparkHome,master,deployMode,queue,conf}}。缺关键字段 → 部分填充，执行器侧据空字段判 SKIPPED
     * （解析不抛错，保零依赖底线）。
     */
    @SuppressWarnings("unchecked")
    private ResolvedConnection buildSparkRef(Datasource ds) {
        String sparkHome = null;
        String master = null;
        String deployMode = null;
        String queue = null;
        Map<String, String> conf = null;
        String props = ds.getPropsJson();
        if (props != null && !props.isBlank()) {
            try {
                Map<String, Object> root = objectMapper.readValue(props, Map.class);
                sparkHome = asString(root.get("sparkHome"));
                master = asString(root.get("master"));
                deployMode = asString(root.get("deployMode"));
                queue = asString(root.get("queue"));
                Object confObj = root.get("conf");
                if (confObj instanceof Map<?, ?> cm) {
                    conf = new LinkedHashMap<>();
                    for (var entry : cm.entrySet()) {
                        conf.put(String.valueOf(entry.getKey()),
                                entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
                    }
                }
            } catch (Exception ignored) {
                // 解析失败：留空字段，执行器判 SKIPPED（contracts C5，零依赖底线）
            }
        }
        return ResolvedConnection.spark(sparkHome, master, deployMode, queue, conf);
    }

    /**
     * 通用引擎解析（FLINK/DATAX/SEATUNNEL）：Flink 从绑定数据源 props_json 取集群配置；
     * DataX/SeaTunnel 从环境变量取 *_HOME（无数据源绑定 → engineHome 从 env）。
     * 解析失败不抛错——留空字段，执行器判 SKIPPED（零依赖底线）。
     */
    @SuppressWarnings("unchecked")
    private ResolvedConnection buildEngineRef(Datasource ds, String kind) {
        String engineHome = null;
        Map<String, String> props = null;
        String propsJson = ds.getPropsJson();
        if (propsJson != null && !propsJson.isBlank()) {
            try {
                Map<String, Object> root = objectMapper.readValue(propsJson, Map.class);
                engineHome = asString(root.get("engineHome"));
                Object confObj = root.get("conf");
                if (confObj instanceof Map<?, ?> cm) {
                    props = new LinkedHashMap<>();
                    for (var entry : cm.entrySet()) {
                        props.put(String.valueOf(entry.getKey()),
                                entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
                    }
                }
            } catch (Exception ignored) {
                // 解析失败：留空字段，执行器判 SKIPPED
            }
        }
        // DataX/SeaTunnel：HOME 优先从环境变量取（ds 可能未绑定，engineHome 为 null → 执行器从 env 探测）
        if (engineHome == null || engineHome.isBlank()) {
            engineHome = System.getenv(kind + "_HOME");
        }
        return ResolvedConnection.engine(kind, engineHome, props);
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
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
            // datasource-driver-isolation（SQL 隔离加载用）
            Long driverJarId, String driverClass, String storageKey,
            // Shell
            Map<String, String> shellEnvVars,
            // Python
            String pythonConfigPath,
            // Spark
            SparkClusterRef spark,
            // Engine (FLINK/DATAX/SEATUNNEL)
            EngineClusterRef engine
    ) {
        public static ResolvedConnection sql(String name, String typeCode, String jdbcUrl,
                                              String username, String password,
                                              Long driverJarId, String driverClass, String storageKey) {
            return new ResolvedConnection(name, typeCode, jdbcUrl, username, password,
                    driverJarId, driverClass, storageKey, null, null, null, null);
        }

        public static ResolvedConnection shell(Map<String, String> envVars) {
            return new ResolvedConnection(null, null, null, null, null, null, null, null, envVars, null, null, null);
        }

        public static ResolvedConnection python(String configPath) {
            return new ResolvedConnection(null, null, null, null, null, null, null, null, null, configPath, null, null);
        }

        public static ResolvedConnection spark(String sparkHome, String master, String deployMode,
                                               String queue, Map<String, String> conf) {
            return new ResolvedConnection(null, null, null, null, null, null, null, null, null, null,
                    new SparkClusterRef(sparkHome, master, deployMode, queue, conf), null);
        }

        public static ResolvedConnection engine(String kind, String engineHome, Map<String, String> props) {
            return new ResolvedConnection(null, null, null, null, null, null, null, null, null, null, null,
                    new EngineClusterRef(kind, engineHome, props));
        }

        /** Spark 集群提交配置（SPARK 数据源解析产物；任务声明的 sparkMode/jar/mainClass 由调用方合入）。 */
        public record SparkClusterRef(String sparkHome, String master, String deployMode,
                                      String queue, Map<String, String> conf) {
        }

        /** 通用引擎集群配置（FLINK/DATAX/SEATUNNEL 数据源解析产物）。 */
        public record EngineClusterRef(String kind, String engineHome, Map<String, String> props) {
        }
    }
}
