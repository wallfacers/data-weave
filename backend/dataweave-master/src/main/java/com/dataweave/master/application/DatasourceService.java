package com.dataweave.master.application;

import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.DatasourceType;
import com.dataweave.master.domain.DatasourceTypeRepository;
import com.dataweave.master.domain.DriverJar;
import com.dataweave.master.domain.DriverJarRepository;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.i18n.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static com.dataweave.master.application.DatasourceDtos.*;

/**
 * Datasource CRUD service with password encryption and masking.
 */
@Service
public class DatasourceService {

    private static final String MASKED_PASSWORD = "******";

    private final DatasourceRepository datasourceRepository;
    private final DatasourceTypeRepository datasourceTypeRepository;
    private final TaskDefRepository taskDefRepository;
    private final DriverJarRepository driverJarRepository;
    private final DatasourceEncryptor encryptor;
    private final JdbcTemplate jdbcTemplate;

    public DatasourceService(DatasourceRepository datasourceRepository,
                             DatasourceTypeRepository datasourceTypeRepository,
                             TaskDefRepository taskDefRepository,
                             DriverJarRepository driverJarRepository,
                             DatasourceEncryptor encryptor,
                             JdbcTemplate jdbcTemplate) {
        this.datasourceRepository = datasourceRepository;
        this.datasourceTypeRepository = datasourceTypeRepository;
        this.taskDefRepository = taskDefRepository;
        this.driverJarRepository = driverJarRepository;
        this.encryptor = encryptor;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** List all active datasources for a project, with passwords masked. */
    public List<DatasourceVO> listByProject(Long tenantId, Long projectId) {
        return datasourceRepository
                .findByTenantIdAndProjectIdAndDeleted(tenantId, projectId, 0)
                .stream()
                .map(this::toVO)
                .toList();
    }

    /** 动态筛选+分页查询数据源。 */
    public PageResult query(Long tenantId, Long projectId,
                            String search, List<String> typeCodes,
                            String connectionStatus,
                            int page, int size) {
        StringBuilder where = new StringBuilder("WHERE d.tenant_id = ? AND d.project_id = ? AND d.deleted = 0");
        List<Object> params = new ArrayList<>();
        params.add(tenantId);
        params.add(projectId);

        if (search != null && !search.isBlank()) {
            where.append(" AND (d.name LIKE ? OR d.host LIKE ?)");
            String like = "%" + search.trim() + "%";
            params.add(like);
            params.add(like);
        }
        if (typeCodes != null && !typeCodes.isEmpty()) {
            where.append(" AND d.type_code IN (");
            for (int i = 0; i < typeCodes.size(); i++) {
                if (i > 0) where.append(",");
                where.append("?");
                params.add(typeCodes.get(i));
            }
            where.append(")");
        }
        if (connectionStatus != null && !connectionStatus.isBlank()) {
            where.append(" AND d.connection_status = ?");
            params.add(connectionStatus);
        }

        // Count
        String countSql = "SELECT COUNT(*) FROM datasources d " + where;
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        long totalElements = total != null ? total : 0;

        // Page
        int offset = (page - 1) * size;
        String sql = "SELECT d.* FROM datasources d " + where + " ORDER BY d.created_at DESC LIMIT ? OFFSET ?";
        List<Object> pageParams = new ArrayList<>(params);
        pageParams.add(size);
        pageParams.add(offset);

        List<Datasource> content = jdbcTemplate.query(sql, (rs, rowNum) -> {
            Datasource ds = new Datasource();
            ds.setId(rs.getLong("id"));
            ds.setTenantId(rs.getLong("tenant_id"));
            ds.setProjectId(rs.getLong("project_id"));
            ds.setName(rs.getString("name"));
            ds.setTypeCode(rs.getString("type_code"));
            ds.setHost(rs.getString("host"));
            ds.setPort(rs.getObject("port") != null ? rs.getInt("port") : null);
            ds.setDatabaseName(rs.getString("database_name"));
            ds.setJdbcUrl(rs.getString("jdbc_url"));
            ds.setUsername(rs.getString("username"));
            ds.setDescription(rs.getString("description"));
            ds.setStatus(rs.getString("status"));
            ds.setConnectionStatus(rs.getString("connection_status"));
            ds.setDriverJarId(rs.getObject("driver_jar_id") != null ? rs.getLong("driver_jar_id") : null);
            ds.setCreatedAt(rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toLocalDateTime() : null);
            ds.setUpdatedAt(rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toLocalDateTime() : null);
            return ds;
        }, pageParams.toArray());

        return new PageResult(content.stream().map(this::toVO).toList(), totalElements, page, size);
    }

    /** Get datasource by ID with password masked. Throws if not found. */
    public DatasourceVO getById(Long id) {
        Datasource ds = datasourceRepository.findById(id)
                .filter(d -> d.getDeleted() == null || d.getDeleted() == 0)
                .orElseThrow(() ->
                        new BizException("datasource.not_found", id).withHttpStatus(404));
        return toVO(ds);
    }

    /** Get raw entity by ID (with decrypted password). Internal use. */
    public Datasource getEntityById(Long id) {
        return datasourceRepository.findById(id)
                .filter(d -> d.getDeleted() == null || d.getDeleted() == 0)
                .orElseThrow(() ->
                        new BizException("datasource.not_found", id).withHttpStatus(404));
    }

    /** Create a new datasource. Password is encrypted before storage. */
    public DatasourceVO create(DatasourceCreateRequest req, Long tenantId) {
        // Validate required fields
        if (req.name() == null || req.name().isBlank()) {
            throw new BizException("datasource.name_required");
        }
        if (req.typeCode() == null || req.typeCode().isBlank()) {
            throw new BizException("datasource.type_required");
        }
        Long projectId = req.projectId() != null ? req.projectId() : 1L;

        // Check name uniqueness within project
        if (datasourceRepository.existsByProjectIdAndNameAndDeleted(projectId, req.name(), 0)) {
            throw new BizException("datasource.name_duplicate", req.name())
                    .withHttpStatus(409);
        }

        Datasource ds = new Datasource();
        ds.setTenantId(tenantId);
        ds.setProjectId(projectId);
        ds.setName(req.name());
        ds.setTypeCode(req.typeCode());
        ds.setHost(req.host());
        // 端口：前端传了就直接用；未传则查 datasource_types.default_port 兜底
        Integer port = req.port();
        if (port == null) {
            port = datasourceTypeRepository.findByCode(req.typeCode())
                    .map(DatasourceType::getDefaultPort)
                    .orElse(null);
        }
        ds.setPort(port);
        ds.setDatabaseName(req.databaseName());
        ds.setJdbcUrl(req.jdbcUrl());
        ds.setUsername(req.username());
        ds.setDescription(req.description());
        ds.setPropsJson(req.propsJson());
        ds.setStatus("ACTIVE");
        ds.setConnectionStatus("UNKNOWN");
        ds.setDriverJarId(resolveActiveDriverJar(req.driverJarId()));
        ds.setDeleted(0);
        ds.setVersion(0);
        LocalDateTime now = LocalDateTime.now();
        ds.setCreatedAt(now);
        ds.setUpdatedAt(now);

        // Encrypt password
        if (req.password() != null && !req.password().isEmpty()) {
            ds.setPasswordEnc(encryptor.encrypt(req.password()));
        }

        Datasource saved = datasourceRepository.save(ds);
        return toVO(saved);
    }

    /** Update an existing datasource. Empty/null password preserves existing. */
    public DatasourceVO update(Long id, DatasourceUpdateRequest req) {
        Datasource ds = datasourceRepository.findById(id)
                .filter(d -> d.getDeleted() == null || d.getDeleted() == 0)
                .orElseThrow(() ->
                        new BizException("datasource.not_found", id).withHttpStatus(404));

        // Check name uniqueness if name changed
        if (req.name() != null && !req.name().isBlank() && !req.name().equals(ds.getName())) {
            if (datasourceRepository.existsByProjectIdAndNameAndDeletedAndIdNot(
                    ds.getProjectId(), req.name(), 0, id)) {
                throw new BizException("datasource.name_duplicate", req.name())
                        .withHttpStatus(409);
            }
            ds.setName(req.name());
        }
        if (req.typeCode() != null) ds.setTypeCode(req.typeCode());
        if (req.host() != null) ds.setHost(req.host());
        if (req.port() != null) ds.setPort(req.port());
        if (req.databaseName() != null) ds.setDatabaseName(req.databaseName());
        if (req.jdbcUrl() != null) ds.setJdbcUrl(req.jdbcUrl());
        if (req.username() != null) ds.setUsername(req.username());
        if (req.description() != null) ds.setDescription(req.description());
        if (req.propsJson() != null) ds.setPropsJson(req.propsJson());
        if (req.status() != null) ds.setStatus(req.status());
        // driverJarId：非 null 则校验 ACTIVE 并设置（PATCH 语义：null=不改，解绑走 unbindDriverJar）
        if (req.driverJarId() != null) {
            ds.setDriverJarId(resolveActiveDriverJar(req.driverJarId()));
        }

        // Password: only update if non-empty
        if (req.password() != null && !req.password().isEmpty()) {
            ds.setPasswordEnc(encryptor.encrypt(req.password()));
        }

        ds.setUpdatedAt(LocalDateTime.now());

        Datasource saved = datasourceRepository.save(ds);
        return toVO(saved);
    }

    /** 解绑数据源的驱动 jar（driver_jar_id 置 NULL，回退内置默认驱动）。 */
    public DatasourceVO unbindDriverJar(Long id) {
        Datasource ds = datasourceRepository.findById(id)
                .filter(d -> d.getDeleted() == null || d.getDeleted() == 0)
                .orElseThrow(() ->
                        new BizException("datasource.not_found", id).withHttpStatus(404));
        ds.setDriverJarId(null);
        Datasource saved = datasourceRepository.save(ds);
        return toVO(saved);
    }

    /** Soft-delete a datasource. Returns reference count warning. */
    public DeleteResult delete(Long id) {
        Datasource ds = datasourceRepository.findById(id)
                .filter(d -> d.getDeleted() == null || d.getDeleted() == 0)
                .orElseThrow(() ->
                        new BizException("datasource.not_found", id).withHttpStatus(404));

        ds.setDeleted(1);
        datasourceRepository.save(ds);

        // Count task references for warning
        long sourceRef = taskDefRepository.countByDatasourceIdAndDeleted(id, 0);
        long targetRef = taskDefRepository.countByTargetDatasourceIdAndDeleted(id, 0);
        long totalRef = sourceRef + targetRef;

        String warning = null;
        if (totalRef > 0) {
            warning = "该数据源已被 " + totalRef + " 个任务引用，删除后相关任务将无法执行";
        }

        return new DeleteResult(true, totalRef, warning);
    }

    /** Decrypt password for internal use (executor / connection test). */
    public String decryptPassword(Datasource ds) {
        if (ds.getPasswordEnc() == null || ds.getPasswordEnc().isEmpty()) {
            return null;
        }
        return encryptor.decrypt(ds.getPasswordEnc());
    }

    /** 更新数据源连接状态（连通性测试后调用）。 */
    public void updateConnectionStatus(Long id, boolean connected) {
        Datasource ds = datasourceRepository.findById(id)
                .filter(d -> d.getDeleted() == null || d.getDeleted() == 0)
                .orElseThrow(() ->
                        new BizException("datasource.not_found", id).withHttpStatus(404));
        ds.setConnectionStatus(connected ? "CONNECTED" : "DISCONNECTED");
        ds.setUpdatedAt(LocalDateTime.now());
        datasourceRepository.save(ds);
    }

    // --- private helpers ---

    /** 校验 driverJarId 指向 ACTIVE 资产；null 返回 null（走内置默认）。 */
    private Long resolveActiveDriverJar(Long driverJarId) {
        if (driverJarId == null) {
            return null;
        }
        DriverJar jar = driverJarRepository.findById(driverJarId)
                .filter(j -> j.getDeleted() == null || j.getDeleted() == 0)
                .orElseThrow(() -> new BizException("datasource.driver_jar_not_found", driverJarId).withHttpStatus(404));
        if (!"ACTIVE".equals(jar.getStatus())) {
            throw new BizException("datasource.driver_jar_not_active", driverJarId).withHttpStatus(409);
        }
        return driverJarId;
    }

    private DatasourceVO toVO(Datasource ds) {
        String driverSource = ds.getDriverJarId() != null ? "uploaded" : "builtin";
        String connStatus = ds.getConnectionStatus() != null ? ds.getConnectionStatus() : "UNKNOWN";
        return new DatasourceVO(
                ds.getId(),
                ds.getTenantId(),
                ds.getProjectId(),
                ds.getName(),
                ds.getTypeCode(),
                ds.getHost(),
                ds.getPort(),
                ds.getDatabaseName(),
                ds.getJdbcUrl(),
                ds.getUsername(),
                MASKED_PASSWORD,
                ds.getPropsJson(),
                ds.getDescription(),
                ds.getStatus(),
                connStatus,
                ds.getDriverJarId(),
                driverSource,
                ds.getCreatedAt() != null ? ds.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null,
                ds.getUpdatedAt() != null ? ds.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toString() : null
        );
    }
}
