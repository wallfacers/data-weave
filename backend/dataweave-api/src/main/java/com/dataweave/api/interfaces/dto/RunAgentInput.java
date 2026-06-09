package com.dataweave.api.interfaces.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * AG-UI 请求体（宽松 DTO，忽略未知字段）。至少解析 threadId / runId / messages。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RunAgentInput {

    private String threadId;
    private String runId;
    private List<Message> messages;

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
