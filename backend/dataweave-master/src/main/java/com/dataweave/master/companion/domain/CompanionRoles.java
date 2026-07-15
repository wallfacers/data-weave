package com.dataweave.master.companion.domain;

/**
 * 管家会话消息发言角色。
 *
 * <ul>
 *   <li>{@code USER}——用户（actor=服务端认定 username，actor_name=displayName）</li>
 *   <li>{@code AGENT}——管家 Vega（actor=companion-agent）</li>
 *   <li>{@code SYSTEM}——系统消息（降级提示/审批回报等，actor=system）</li>
 * </ul>
 */
public final class CompanionRoles {

    public static final String USER = "USER";
    public static final String AGENT = "AGENT";
    public static final String SYSTEM = "SYSTEM";

    private CompanionRoles() {}
}
