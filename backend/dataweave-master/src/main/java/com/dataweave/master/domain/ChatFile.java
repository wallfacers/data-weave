package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * 聊天附件文件元数据（chat-attachments）：用户在输入框上传的真实文件。
 *
 * <p>主键 {@code id} 为内容 sha256（内容寻址，天然去重）；字节存外部 {@link com.dataweave.master.infrastructure.ChatFileStorage}，
 * 库里只留元数据 + {@code storageKey}。附件经 {@code forwardedProps.dataweave.attachments} 透传给 Agent。
 *
 * <p>id 为业务赋值（非自增），故实现 {@link Persistable} 显式声明 isNew，避免 Spring Data JDBC
 * 因 id 非空而误判为 UPDATE。
 */
@Table("agent_chat_file")
public class ChatFile implements Persistable<String> {

    @Id
    private String id;
    private Long tenantId;
    private String originalName;
    private String mimeType;
    private Long sizeBytes;
    private String storageType;
    private String storageKey;
    private Long createdBy;
    private LocalDateTime createdAt;

    @Transient
    private boolean isNew;

    public ChatFile() {}

    @Override
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    @Override
    public boolean isNew() { return isNew; }
    public ChatFile markNew() { this.isNew = true; return this; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }

    public String getMimeType() { return mimeType; }
    public void setMimeType(String mimeType) { this.mimeType = mimeType; }

    public Long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Long sizeBytes) { this.sizeBytes = sizeBytes; }

    public String getStorageType() { return storageType; }
    public void setStorageType(String storageType) { this.storageType = storageType; }

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }

    public Long getCreatedBy() { return createdBy; }
    public void setCreatedBy(Long createdBy) { this.createdBy = createdBy; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
