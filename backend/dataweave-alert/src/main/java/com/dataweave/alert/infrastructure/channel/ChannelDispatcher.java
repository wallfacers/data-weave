package com.dataweave.alert.infrastructure.channel;

import com.dataweave.alert.domain.AlertChannel;
import com.dataweave.alert.domain.AlertEvent;

/**
 * 通道分发策略接口：EMAIL / WEBHOOK / DINGTALK / WECOM / FEISHU。
 */
public interface ChannelDispatcher {

    /** 该分发器支持的通道类型 */
    String channelType();

    /** 分发一条告警事件到指定通道，返回分发结果 */
    DispatchResult dispatch(AlertEvent event, AlertChannel channel);
}
