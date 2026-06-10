package com.dataweave.master.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

/** 一次工具调用步骤。tool_use_id 与 workhorse SSE 事件天然 join。 */
@Table("agent_step")
public class AgentStep {

    @Id
    private Long id;
    private Long runId;
    private Integer seq;
    private String toolUseId;
    private String toolName;
    private String inputJson;
    private String outputPreview;
    private String outputRef;
    private Integer outputBytes;
    private Integer truncated;
    private String decision;        // ALLOW / DENY
    private String decisionSource;  // rule / default / prompt / timeout
    private Long durationMs;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public AgentStep() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getRunId() {
        return runId;
    }

    public void setRunId(Long runId) {
        this.runId = runId;
    }

    public Integer getSeq() {
        return seq;
    }

    public void setSeq(Integer seq) {
        this.seq = seq;
    }

    public String getToolUseId() {
        return toolUseId;
    }

    public void setToolUseId(String toolUseId) {
        this.toolUseId = toolUseId;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public String getInputJson() {
        return inputJson;
    }

    public void setInputJson(String inputJson) {
        this.inputJson = inputJson;
    }

    public String getOutputPreview() {
        return outputPreview;
    }

    public void setOutputPreview(String outputPreview) {
        this.outputPreview = outputPreview;
    }

    public String getOutputRef() {
        return outputRef;
    }

    public void setOutputRef(String outputRef) {
        this.outputRef = outputRef;
    }

    public Integer getOutputBytes() {
        return outputBytes;
    }

    public void setOutputBytes(Integer outputBytes) {
        this.outputBytes = outputBytes;
    }

    public Integer getTruncated() {
        return truncated;
    }

    public void setTruncated(Integer truncated) {
        this.truncated = truncated;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public String getDecisionSource() {
        return decisionSource;
    }

    public void setDecisionSource(String decisionSource) {
        this.decisionSource = decisionSource;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
