package com.dataweave.api.interfaces.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * AG-UI 请求体（宽松 DTO，忽略未知字段）。解析 threadId / runId / messages / forwardedProps。
 *
 * <p>forwardedProps 携带逐消息页面上下文：{@code { "dataweave": { module, pathname, taskId, instanceId, nodeId } }}。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RunAgentInput {

    private String threadId;
    private String runId;
    private List<Message> messages;
    private Map<String, Object> forwardedProps;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Message {
        private String id;
        private String role;
        private String content;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }
    }

    public String getThreadId() {
        return threadId;
    }

    public void setThreadId(String threadId) {
        this.threadId = threadId;
    }

    public String getRunId() {
        return runId;
    }

    public void setRunId(String runId) {
        this.runId = runId;
    }

    public List<Message> getMessages() {
        return messages;
    }

    public void setMessages(List<Message> messages) {
        this.messages = messages;
    }

    public Map<String, Object> getForwardedProps() {
        return forwardedProps;
    }

    public void setForwardedProps(Map<String, Object> forwardedProps) {
        this.forwardedProps = forwardedProps;
    }

    /**
     * 从 forwardedProps 提取页面上下文。CopilotKit v2 把 provider 的 {@code properties} 透传为
     * forwardedProps，落点可能是 forwardedProps.dataweave 或 forwardedProps.properties.dataweave，
     * 故两处都找；无则返回空上下文。
     */
    public PageContext pageContext() {
        Map<?, ?> dw = findDataweave(forwardedProps);
        if (dw == null) {
            return new PageContext(null, null, null, null, null);
        }
        return new PageContext(str(dw, "module"), str(dw, "pathname"),
                str(dw, "taskId"), str(dw, "instanceId"), str(dw, "nodeId"));
    }

    private static Map<?, ?> findDataweave(Map<String, Object> props) {
        if (props == null) {
            return null;
        }
        if (props.get("dataweave") instanceof Map<?, ?> m) {
            return m;
        }
        if (props.get("properties") instanceof Map<?, ?> p && p.get("dataweave") instanceof Map<?, ?> m) {
            return m;
        }
        return null;
    }

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return v == null ? null : v.toString();
    }

    /**
     * 逐消息附件（chat-attachments）：实体引用（任务/实例/发现/数据源）+ 上传文件，
     * 经 {@code forwardedProps.dataweave.attachments}（List&lt;Map&gt;）透传。
     *
     * @param kind     {@code entity} / {@code file}
     * @param refType  实体类型 {@code task|instance|finding|datasource}（kind=entity 时有效）
     * @param refId    实体主键（kind=entity 时有效）
     * @param label    实体展示名（kind=entity）
     * @param fileId   附件文件 id=sha256（kind=file 时有效，对应 /api/chat/files/{id}）
     * @param name     文件名（kind=file；前端文件附件字段为 name 而非 label）
     */
    public record Attachment(String kind, String refType, String refId, String label, String fileId, String name) {
        public boolean isFile() {
            return "file".equalsIgnoreCase(kind);
        }

        /** 展示名优先级：实体 label → 文件 name → refId → fileId。 */
        public String displayLabel() {
            if (label != null && !label.isBlank()) return label;
            if (name != null && !name.isBlank()) return name;
            if (refId != null && !refId.isBlank()) return refId;
            return fileId;
        }
    }

    /** 从 forwardedProps.dataweave.attachments 解析附件列表；无则空列表。 */
    public List<Attachment> attachments() {
        Map<?, ?> dw = findDataweave(forwardedProps);
        if (dw == null || !(dw.get("attachments") instanceof List<?> list)) {
            return List.of();
        }
        List<Attachment> out = new java.util.ArrayList<>();
        for (Object o : list) {
            if (o instanceof Map<?, ?> m) {
                out.add(new Attachment(str(m, "kind"), str(m, "refType"), str(m, "refId"),
                        str(m, "label"), str(m, "fileId"), str(m, "name")));
            }
        }
        return out;
    }

    /** 取最后一条 user 消息内容（无则取最后一条消息内容，再无则空串）。 */
    public String lastUserContent() {
        if (messages == null || messages.isEmpty()) {
            return "";
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m != null && "user".equalsIgnoreCase(m.getRole()) && m.getContent() != null) {
                return m.getContent();
            }
        }
        Message last = messages.get(messages.size() - 1);
        return last != null && last.getContent() != null ? last.getContent() : "";
    }
}
