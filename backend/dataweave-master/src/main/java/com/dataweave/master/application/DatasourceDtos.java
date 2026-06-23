package com.dataweave.master.application;

import java.util.Map;

/**
 * DTOs for datasource CRUD operations.
 */
public final class DatasourceDtos {

    private DatasourceDtos() {}

    /** Request body for creating a datasource. */
    public record DatasourceCreateRequest(
            String name,
            String typeCode,
            Long projectId,
            String host,
            Integer port,
            String databaseName,
            String jdbcUrl,
            String username,
            String password,
            String propsJson,
            String description
    ) {}

    /** Request body for updating a datasource (all fields optional — null means "keep existing"). */
    public record DatasourceUpdateRequest(
            String name,
            String typeCode,
            String host,
            Integer port,
            String databaseName,
            String jdbcUrl,
            String username,
            String password,
            String propsJson,
            String description,
            String status
    ) {}

    /** Response VO for datasource (password always masked). */
    public record DatasourceVO(
            Long id,
            Long tenantId,
            Long projectId,
            String name,
            String typeCode,
            String host,
            Integer port,
            String databaseName,
            String jdbcUrl,
            String username,
            String passwordEnc,
            String propsJson,
            String description,
            String status,
            String createdAt,
            String updatedAt
    ) {}

    /** Result of a datasource delete operation. */
    public record DeleteResult(
            boolean deleted,
            long referencedTaskCount,
            String warning
    ) {}

    /** Result of a connection test. */
    public record ConnectionTestResult(
            boolean success,
            String message,
            int latencyMs,
            String serverVersion
    ) {}
}
