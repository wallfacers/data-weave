package com.dataweave.master.application;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 一次待裁决的副作用操作描述。工具层（MCP）、CLI、applyFix 统一构造它交给 {@link GatedActionService}。
 *
 * <p>裁决维度：toolName 或 command 定基础等级；ownedByPlatform / environment / batchCount 触发抬升。
 * params 携带执行所需的额外参数（nodeCode、fixAction、instanceId 等），由 {@link PlatformActionExecutor} 消费。
 */
public final class ActionRequest {

    private final Long runId;
    private final Long stepId;
    private final String toolName;
    private final String command;
    private final String actionType;
    private final String targetType;
    private final String targetId;
    private final String environment;     // dev / prod；null = 用系统默认
    private final Boolean ownedByPlatform; // null = 由引擎按 targetType 解析
    private final int batchCount;
    private final String actor;
    private final String actorSource;     // AGENT / CLI / UI
    private final String summary;
    private final Map<String, Object> params;
    private final Long incidentId;     // 043 incident 工单反查键；null = 非 incident 发起（既有调用方零影响）

    private ActionRequest(Builder b) {
        this.runId = b.runId;
        this.stepId = b.stepId;
        this.toolName = b.toolName;
        this.command = b.command;
        this.actionType = b.actionType;
        this.targetType = b.targetType;
        this.targetId = b.targetId;
        this.environment = b.environment;
        this.ownedByPlatform = b.ownedByPlatform;
        this.batchCount = b.batchCount;
        this.actor = b.actor;
        this.actorSource = b.actorSource;
        this.summary = b.summary;
        this.params = b.params;
        this.incidentId = b.incidentId;
    }

    public Long runId() {
        return runId;
    }

    public Long stepId() {
        return stepId;
    }

    public String toolName() {
        return toolName;
    }

    public String command() {
        return command;
    }

    public String actionType() {
        return actionType;
    }

    public String targetType() {
        return targetType;
    }

    public String targetId() {
        return targetId;
    }

    public String environment() {
        return environment;
    }

    public Boolean ownedByPlatform() {
        return ownedByPlatform;
    }

    public int batchCount() {
        return batchCount;
    }

    public String actor() {
        return actor;
    }

    public String actorSource() {
        return actorSource;
    }

    public String summary() {
        return summary;
    }

    /** 043 incident 工单反查键（incident 卡片发起的闸门动作回挂工单）；null = 非 incident 发起。 */
    public Long incidentId() {
        return incidentId;
    }

    public Map<String, Object> params() {
        return params;
    }

    public Object param(String key) {
        return params == null ? null : params.get(key);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Long runId;
        private Long stepId;
        private String toolName;
        private String command;
        private String actionType;
        private String targetType;
        private String targetId;
        private String environment;
        private Boolean ownedByPlatform;
        private int batchCount = 1;
        private String actor;
        private String actorSource;
        private String summary;
        private Map<String, Object> params = new LinkedHashMap<>();
        private Long incidentId;

        public Builder runId(Long v) {
            this.runId = v;
            return this;
        }

        public Builder stepId(Long v) {
            this.stepId = v;
            return this;
        }

        public Builder toolName(String v) {
            this.toolName = v;
            return this;
        }

        public Builder command(String v) {
            this.command = v;
            return this;
        }

        public Builder actionType(String v) {
            this.actionType = v;
            return this;
        }

        public Builder targetType(String v) {
            this.targetType = v;
            return this;
        }

        public Builder targetId(String v) {
            this.targetId = v;
            return this;
        }

        public Builder environment(String v) {
            this.environment = v;
            return this;
        }

        public Builder ownedByPlatform(Boolean v) {
            this.ownedByPlatform = v;
            return this;
        }

        public Builder batchCount(int v) {
            this.batchCount = v;
            return this;
        }

        public Builder actor(String v) {
            this.actor = v;
            return this;
        }

        public Builder actorSource(String v) {
            this.actorSource = v;
            return this;
        }

        public Builder summary(String v) {
            this.summary = v;
            return this;
        }

        public Builder incidentId(Long v) {
            this.incidentId = v;
            return this;
        }

        public Builder param(String key, Object value) {
            this.params.put(key, value);
            return this;
        }

        public ActionRequest build() {
            return new ActionRequest(this);
        }
    }
}
