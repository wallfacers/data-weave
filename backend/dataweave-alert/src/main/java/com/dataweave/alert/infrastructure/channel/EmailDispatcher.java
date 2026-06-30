package com.dataweave.alert.infrastructure.channel;

import com.dataweave.alert.domain.AlertChannel;
import com.dataweave.alert.domain.AlertEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * EMAIL 通道分发器（v1 桩：记录日志，真实 SMTP 后置）。
 */
@Component
public class EmailDispatcher implements ChannelDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

    @Override
    public String channelType() {
        return "EMAIL";
    }

    @Override
    public DispatchResult dispatch(AlertEvent event, AlertChannel channel) {
        log.info("[EmailDispatcher] EMAIL channel {}: severity={} fingerprint={}",
                channel.getName(), event.getSeverity(), event.getFingerprint());
        // v1: 桩实现——记录日志；后续集成 JavaMailSender
        return DispatchResult.sent("email sent (stub)");
    }
}
