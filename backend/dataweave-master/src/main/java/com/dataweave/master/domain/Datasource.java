package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

@Table("datasources")
public class Datasource {

    @Id
    private Long id;
    private Long tenantId;
    private Long projectId;
    private String name;
    private String typeCode;
    private String host;
    private Integer port;
    private String databaseName;
    private String jdbcUrl;
    private String username;
    private String passwordEnc;
    private String propsJson;
    private String description;
    private String status;
    /** 绑定的上传驱动 jar 资产 id（datasource-driver-isolation）；NULL = 走内置默认驱动。 */
    private Long driverJarId;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Integer version;

    public Datasource() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getTypeCode() { return typeCode; }
    public void setTypeCode(String typeCode) { this.typeCode = typeCode; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }

    public String getJdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordEnc() { return passwordEnc; }
    public void setPasswordEnc(String passwordEnc) { this.passwordEnc = passwordEnc; }

    public String getPropsJson() { return propsJson; }
    public void setPropsJson(String propsJson) { this.propsJson = propsJson; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Long getDriverJarId() { return driverJarId; }
    public void setDriverJarId(Long driverJarId) { this.driverJarId = driverJarId; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public Long getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(Long updatedBy) { this.updatedBy = updatedBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public Integer getDeleted() { return deleted; }
    public void setDeleted(Integer deleted) { this.deleted = deleted; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
}
