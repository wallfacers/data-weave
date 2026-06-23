package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;
import java.time.LocalDateTime;

/**
 * 上传的 JDBC 驱动 jar 资产（datasource-driver-isolation）。
 *
 * <p>按内容 {@code sha256} 在租户内去重资产化，可被多个 {@link Datasource} 通过
 * {@code driver_jar_id} 引用复用。绑定时经隔离 {@code URLClassLoader} 加载，
 * 直接 {@code java.sql.Driver#connect} 绕过 {@code DriverManager} 的 ClassLoader 校验，
 * 实现多版本驱动按数据源共存（如数据源 A 用 ojdbc6、B 用 ojdbc11）。
 *
 * <p>{@code status} 生命周期：{@code PENDING}（上传后待 PolicyEngine 审批，默认 L2）
 * → {@code ACTIVE}（审批通过，可被数据源绑定生效）/ {@code REJECTED}。
 */
@Table("driver_jars")
public class DriverJar {

    @Id
    private Long id;
    private Long tenantId;
    private String typeCode;
    private String driverClass;
    private String originalName;
    private String sha256;
    private String storageType;
    private String storageKey;
    private Long sizeBytes;
    private String status;
    private Long createdBy;
    private Long updatedBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer deleted;
    private Integer version;

    public DriverJar() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public String getTypeCode() { return typeCode; }
    public void setTypeCode(String typeCode) { this.typeCode = typeCode; }

    public String getDriverClass() { return driverClass; }
    public void setDriverClass(String driverClass) { this.driverClass = driverClass; }

    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }

    public String getSha256() { return sha256; }
    public void setSha256(String sha256) { this.sha256 = sha256; }

    public String getStorageType() { return storageType; }
    public void setStorageType(String storageType) { this.storageType = storageType; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

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
