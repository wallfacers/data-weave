package com.dataweave.alert.infrastructure;

import com.dataweave.alert.domain.NotificationChannel;
import org.springframework.stereotype.Component;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * 默认 mock 通知通道：仅打日志。后期可替换为真实 email/IM/webhook 实现。
 */
@Component
public class LogNotificationChannel implements NotificationChannel {

    private static final Logger LOG = System.getLogger(LogNotificationChannel.class.getName());

    @Override
    public String name() {
        return "log";
    }

    @Override
    public void send(String title, String message) {
        LOG.log(Level.INFO, "[ALERT][{0}] {1}", title, message);
    }
}
