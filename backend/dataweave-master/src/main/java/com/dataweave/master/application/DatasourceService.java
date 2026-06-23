package com.dataweave.master.application;

import com.dataweave.master.domain.Datasource;
import com.dataweave.master.domain.DatasourceRepository;
import com.dataweave.master.domain.TaskDefRepository;
import com.dataweave.master.i18n.BizException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.StreamSupport;

import static com.dataweave.master.application.DatasourceDtos.*;

/**
 * Datasource CRUD service with password encryption and masking.
 */
@Service
public class DatasourceService {

    private static final String MASKED_PASSWORD = "******";
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    private final DatasourceRepository datasourceRepository;
    private final TaskDefRepository taskDefRepository;
    private final DatasourceEncryptor encryptor;

    public DatasourceService(DatasourceRepository datasourceRepository,
                             TaskDefRepository taskDefRepository,
                             DatasourceEncryptor encryptor) {
        this.datasourceRepository = datasourceRepository;
        this.taskDefRepository = taskDefRepository;
        this.encryptor = encryptor;
    }

    /** List all active datasources for a project, with passwords masked. */
    public List<DatasourceVO> listByProject(Long tenantId, Long projectId) {
        return datasourceRepository
                .findByTenantIdAndProjectIdAndDeleted(tenantId, projectId, 0)
                .stream()
                .map(this::toVO)
                .toList();
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
        ds.setPort(req.port());
        ds.setDatabaseName(req.databaseName());
        ds.setJdbcUrl(req.jdbcUrl());
        ds.setUsername(req.username());
        ds.setDescription(req.description());
        ds.setPropsJson(req.propsJson());
        ds.setStatus("ACTIVE");
        ds.setDeleted(0);
        ds.setVersion(0);

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

        // Password: only update if non-empty
        if (req.password() != null && !req.password().isEmpty()) {
            ds.setPasswordEnc(encryptor.encrypt(req.password()));
        }

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

    // --- private helpers ---

    private DatasourceVO toVO(Datasource ds) {
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
                ds.getCreatedAt() != null ? ds.getCreatedAt().format(DT_FMT) : null,
                ds.getUpdatedAt() != null ? ds.getUpdatedAt().format(DT_FMT) : null
        );
    }
}
