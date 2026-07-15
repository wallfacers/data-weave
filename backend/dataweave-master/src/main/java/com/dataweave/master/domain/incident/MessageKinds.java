package com.dataweave.master.domain.incident;

/** 事故线程消息种类常量（incident_message.kind 取值）。 */
public final class MessageKinds {

    public static final String AGENT_STEP = "AGENT_STEP";
    public static final String AGENT_SAY = "AGENT_SAY";
    public static final String HUMAN_SAY = "HUMAN_SAY";
    public static final String ACTION = "ACTION";
    public static final String PROPOSAL = "PROPOSAL";
    public static final String SYSTEM = "SYSTEM";

    private MessageKinds() {
    }
}
