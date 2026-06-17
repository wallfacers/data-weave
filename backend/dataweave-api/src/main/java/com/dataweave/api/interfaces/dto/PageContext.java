package com.dataweave.api.interfaces.dto;

import com.dataweave.master.i18n.Messages;
import java.util.Locale;

/**
 * 右舷逐消息携带的页面上下文（cockpit 缺口①）。随每条用户消息变化，是消息级而非会话级。
 *
 * @param module     业务模块（如 /ops、/fleet）
 * @param pathname   当前路由
 * @param taskId     选中的任务 id（可空）
 * @param instanceId 选中的任务实例 id（可空）
 * @param nodeId     选中的节点 code（可空）
 */
public record PageContext(String module, String pathname, String taskId, String instanceId, String nodeId) {

    public boolean isEmpty() {
        return blank(module) && blank(pathname) && blank(taskId) && blank(instanceId) && blank(nodeId);
    }

    /**
     * 拼成给 agent 的本地化上下文段，如 {@code [上下文] 模块=/ops 路径=/ops/100 实例=#100}
     * （英文：{@code [Context] module=/ops path=/ops/100 instance=#100}）。
     *
     * <p>DTO 为 record 不便注入 {@link Messages} bean，故由调用方（持有 Messages 的编排器）传入。
     */
    public String toPromptSegment(Locale locale, Messages messages) {
        if (isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(messages.get("agent.context.prefix", locale));
        if (!blank(module)) {
            sb.append(" ").append(messages.get("agent.context.module", locale, module));
        }
        if (!blank(pathname)) {
            sb.append(" ").append(messages.get("agent.context.pathname", locale, pathname));
        }
        if (!blank(taskId)) {
            sb.append(" ").append(messages.get("agent.context.task", locale, taskId));
        }
        if (!blank(instanceId)) {
            sb.append(" ").append(messages.get("agent.context.instance", locale, instanceId));
        }
        if (!blank(nodeId)) {
            sb.append(" ").append(messages.get("agent.context.node", locale, nodeId));
        }
        return sb.toString();
    }

    /** 实例 id 解析为 UUIDv7（实例类主键）。无法解析返回 null。 */
    public java.util.UUID instanceIdAsUuid() {
        if (blank(instanceId)) {
            return null;
        }
        try {
            return java.util.UUID.fromString(instanceId.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Long taskIdAsLong() {
        return parse(taskId);
    }

    private static Long parse(String s) {
        if (blank(s)) {
            return null;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
