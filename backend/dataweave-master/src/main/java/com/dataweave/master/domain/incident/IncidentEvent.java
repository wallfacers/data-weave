package com.dataweave.master.domain.incident;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 指挥中心直播流事件族（契约：specs/069-agent-incident-ops/contracts/sse-live-feed.md）。
 * 持久化背书、可重放：Snapshot / IncidentChanged / MessageAppended / BriefingUpdated。
 * 瞬态直播、不重放（智能感层）：Thinking / Chip / Delta / End。
 * {@link #eventName()} 对应 SSE {@code event:} 字段；record 本体经 Jackson3 序列化为 {@code data:}。
 */
public sealed interface IncidentEvent {

    String eventName();

    /** 连接建立时一次：全部未收口事故 + 实时数字。 */
    record Snapshot(List<Incident> incidents, String statsJson) implements IncidentEvent {
        @Override
        public String eventName() {
            return "snapshot";
        }
    }

    /** 事故开立/状态变更/归并/收口。 */
    record IncidentChanged(Incident incident) implements IncidentEvent {
        @Override
        public String eventName() {
            return "incident";
        }
    }

    /** 持久化消息落库后广播。 */
    record MessageAppended(UUID incidentId, IncidentMessage message) implements IncidentEvent {
        @Override
        public String eventName() {
            return "message";
        }
    }

    /** 播报更新（防抖后）。 */
    record BriefingUpdated(String summaryLine, String statsJson, LocalDateTime generatedAt) implements IncidentEvent {
        @Override
        public String eventName() {
            return "briefing";
        }
    }

    /** 思考态指示；phase = START | STOP。 */
    record Thinking(UUID incidentId, String phase, String label) implements IncidentEvent {
        @Override
        public String eventName() {
            return "thinking";
        }
    }

    /** 工具动作点亮；status = RUNNING | DONE | FAILED。 */
    record Chip(UUID incidentId, String chipId, String label, String status) implements IncidentEvent {
        @Override
        public String eventName() {
            return "chip";
        }
    }

    /** 流式文本分片；同 streamId 的分片拼接，收到对应持久化消息（payload.streamId 匹配）即以完整消息替换。 */
    record Delta(UUID incidentId, String streamId, String text) implements IncidentEvent {
        @Override
        public String eventName() {
            return "delta";
        }
    }

    /** 服务端主动关流。 */
    record End(String reason) implements IncidentEvent {
        @Override
        public String eventName() {
            return "end";
        }
    }
}
