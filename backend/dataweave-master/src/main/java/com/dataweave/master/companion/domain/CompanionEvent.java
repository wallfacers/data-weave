package com.dataweave.master.companion.domain;

import java.util.List;

/**
 * SSE 直播流事件族（契约 companion-api.md SSE 节）。每个 record 覆写 {@link #eventName()}
 * 返回 SSE {@code event:} 字段名，经 {@code CompanionEventPublisher} 序列化为 {@code Envelope(event, data)}
 * 扇出到项目级频道。
 *
 * <p>瞬态事件（{@link Delta}）只走 EventBus 不落库；语义完整消息（{@link MessageAppended}）落 companion_message。
 */
public sealed interface CompanionEvent {

    /** SSE event 字段名。 */
    String eventName();

    /**
     * SSE data 负载（契约 frozen：{@code message}→MessageView 本身、{@code briefing}→Briefing 本身，
     * 其余事件即记录本体）。默认即记录自身；需解包的事件覆写。
     */
    default Object payload() {
        return this;
    }

    /** 连接即全量：管家形态 + 概况 + 未关闭汇报（时间倒序）。 */
    record Snapshot(String state, Briefing briefing, List<ReportView> reports) implements CompanionEvent {
        @Override public String eventName() { return "snapshot"; }
    }

    /** 管家形态变更（服务端归一）。 */
    record StateChanged(String state, String reason) implements CompanionEvent {
        @Override public String eventName() { return "state"; }
    }

    /** 汇报到达/项目级关闭同步（{@code type=created|closed}）。 */
    record ReportEvent(String type, ReportView report) implements CompanionEvent {
        @Override public String eventName() { return "report"; }
    }

    /** 概况变更。SSE data = Briefing 本身（契约）。 */
    record BriefingChanged(Briefing briefing) implements CompanionEvent {
        @Override public String eventName() { return "briefing"; }
        @Override public Object payload() { return briefing; }
    }

    /** 完整消息落库（用户回显/管家整条）。SSE data = MessageView 本身（契约）。 */
    record MessageAppended(MessageView message) implements CompanionEvent {
        @Override public String eventName() { return "message"; }
        @Override public Object payload() { return message; }
    }

    /** 管家流式增量（瞬态，不落库）。 */
    record Delta(String messageId, String chunk) implements CompanionEvent {
        @Override public String eventName() { return "delta"; }
    }

    /** 单条流式结束。 */
    record StreamEnd(String messageId, boolean interrupted) implements CompanionEvent {
        @Override public String eventName() { return "end"; }
    }
}
