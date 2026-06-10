package com.dataweave.api.interfaces.dto;

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

    /** 拼成给 agent 的上下文段，如 {@code [上下文] 模块=/ops 路径=/ops/100 实例=#100}。 */
    public String toPromptSegment() {
        if (isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("[上下文]");
        if (!blank(module)) {
            sb.append(" 模块=").append(module);
        }
        if (!blank(pathname)) {
            sb.append(" 路径=").append(pathname);
        }
        if (!blank(taskId)) {
            sb.append(" 任务=#").append(taskId);
        }
        if (!blank(instanceId)) {
            sb.append(" 实例=#").append(instanceId);
        }
        if (!blank(nodeId)) {
            sb.append(" 节点=").append(nodeId);
        }
        return sb.toString();
    }

    public Long instanceIdAsLong() {
        return parse(instanceId);
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
