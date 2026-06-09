package com.dataweave.alert.domain;

/**
 * 告警规则实体（骨架）。MVP 不实现规则引擎，仅固定结构。
 *
 * <p>TODO（后期接入）：规则表达式解析、阈值/同环比、触发频次、静默窗口、与指标/任务实例联动。
 */
public class AlertRule {

    private Long id;
    private String name;
    private String targetType;   // METRIC / TASK
    private String targetId;
    private String condition;    // 如 "GMV < 1000"
    private String channel;      // 通知通道名
    private boolean enabled;

    public AlertRule() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTargetType() {
        return targetType;
    }

    public void setTargetType(String targetType) {
        this.targetType = targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public void setTargetId(String targetId) {
        this.targetId = targetId;
    }

    public String getCondition() {
        return condition;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
