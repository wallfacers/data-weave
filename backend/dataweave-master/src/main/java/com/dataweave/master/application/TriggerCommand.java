package com.dataweave.master.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * TRIGGER_WORKFLOW 的 {@code command} 字段编解码（手动运行子图范围，design D5）。
 *
 * <p>手动运行可携带运行范围 {@code scope}（FULL/TO_NODE/DOWNSTREAM）与目标节点 {@code targetNodeKey}。
 * {@code AgentAction} 仅持久化标量 {@code command}，故把 {@code bizDate + scope + targetNodeKey}
 * 编码进一个字符串：用控制符  分隔，{@code targetNodeKey} 经 Base64（node_key 含特殊字符也安全）。
 *
 * <p><b>向后兼容</b>：{@code scope} 为空或 FULL（默认）时编码退化为纯 {@code bizDate}（不含分隔符），
 * 历史路径（command 即纯 bizDate）与 cron 触发原样工作；{@link #decode} 见不到分隔符即按纯 bizDate + FULL 解。
 */
public final class TriggerCommand {

    private static final String SEP = "";

    private TriggerCommand() {
    }

    /** 编码：scope 为空或 FULL 时返回纯 bizDate（兼容旧路径/cron）。 */
    public static String encode(String bizDate, String scope, String targetNodeKey) {
        if (scope == null || scope.isBlank() || "FULL".equals(scope)) {
            return bizDate;
        }
        return (bizDate == null ? "" : bizDate) + SEP + scope + SEP + b64(targetNodeKey);
    }

    /** 解码：无分隔符 → 纯 bizDate + FULL；否则拆出 bizDate/scope/targetNodeKey。 */
    public static Decoded decode(String command) {
        if (command == null || !command.contains(SEP)) {
            return new Decoded(command, "FULL", null);
        }
        String[] parts = command.split(SEP, -1);
        String bizDate = parts[0].isEmpty() ? null : parts[0];
        String scope = parts.length > 1 && !parts[1].isEmpty() ? parts[1] : "FULL";
        String targetNodeKey = parts.length > 2 ? unb64(parts[2]) : null;
        return new Decoded(bizDate, scope, targetNodeKey);
    }

    private static String b64(String s) {
        if (s == null) {
            return "";
        }
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String unb64(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8);
    }

    /** 解码结果：scope 缺省 FULL，bizDate/targetNodeKey 可空。 */
    public record Decoded(String bizDate, String scope, String targetNodeKey) {
    }
}
