package com.dataweave.master.application;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * TEST_RUN 的 {@code command} 字段编解码（task-run-decouple）。
 *
 * <p>测试运行可携带编辑器临时内容（脚本 + 参数 JSON）。{@code AgentAction} 仅持久化标量 {@code command}，
 * 故把 {@code bizDate + content + paramsJson} 编码进一个字符串：用控制符 {@code } 分隔，
 * content/paramsJson 经 Base64（无引号/换行/分隔符，避免与 JSON/手写解析的转义坑），便于落 agent_action 回放。
 *
 * <p>**向后兼容**：无临时内容时编码退化为纯 {@code bizDate}（不含分隔符），MCP {@code test_run} 工具与历史
 * rerun 路径（command 即纯 bizDate）原样工作；{@link #decode} 见不到分隔符即按纯 bizDate 解。
 */
public final class TestRunCommand {

    private static final String SEP = "";

    private TestRunCommand() {
    }

    /** 编码：无临时内容（content/paramsJson/type 均空）时返回纯 bizDate（兼容旧路径）。 */
    public static String encode(String bizDate, String content, String paramsJson, String type) {
        boolean noOverride = (content == null || content.isBlank())
                && (paramsJson == null || paramsJson.isBlank())
                && (type == null || type.isBlank());
        if (noOverride) {
            return bizDate;
        }
        return (bizDate == null ? "" : bizDate) + SEP + b64(content) + SEP + b64(paramsJson) + SEP + b64(type);
    }

    /** 解码：无分隔符 → 纯 bizDate；否则拆出 bizDate/content/paramsJson/type。 */
    public static Decoded decode(String command) {
        if (command == null || !command.contains(SEP)) {
            return new Decoded(command, null, null, null);
        }
        String[] parts = command.split(SEP, -1);
        String bizDate = parts[0].isEmpty() ? null : parts[0];
        String content = parts.length > 1 ? unb64(parts[1]) : null;
        String params = parts.length > 2 ? unb64(parts[2]) : null;
        String type = parts.length > 3 ? unb64(parts[3]) : null;
        return new Decoded(bizDate, content, params, type);
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

    /** 解码结果：任一字段可空。 */
    public record Decoded(String bizDate, String content, String paramsJson, String type) {
    }
}
