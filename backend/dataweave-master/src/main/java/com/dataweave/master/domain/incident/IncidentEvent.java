package com.dataweave.master.domain.incident;

import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/**
 * Incident 时间线条目 —— append-only 流水（043）。
 *
 * <p>无 UPDATE/DELETE 路径（FR-006）。字段 = data-model.md 表 2。
 * kind: SIGNAL / STATE_CHANGE / ACTION / APPROVAL / NOTE（预留 AGENT_FINDING）。
 */
@Table("incident_event")
public class IncidentEvent {

    private Long id;
    private Long tenantId;
    private Long incidentId;
    private int seq;
    private String kind;
    private String payloadJson;
    private String actor;
    private LocalDateTime createdAt;

    public IncidentEvent() {}

    // ── auto-generated ─────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTenantId() { return tenantId; }
    public void setTenantId(Long tenantId) { this.tenantId = tenantId; }

    public Long getIncidentId() { return incidentId; }
    public void setIncidentId(Long incidentId) { this.incidentId = incidentId; }

    public int getSeq() { return seq; }
    public void setSeq(int seq) { this.seq = seq; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    /** 契约 JSON 字段名（contracts/incident-api.md §3: timeline "payload"）。 */
    @com.fasterxml.jackson.annotation.JsonProperty("payload")
    public String getPayload() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
