package com.dataweave.alert.domain;

/**
 * 通知发送通道行为接口（骨架）。实体 {@link NotificationChannel} 是其配置数据；
 * 本接口是运行期发送实现的接缝。MVP 仅 {@link com.dataweave.alert.infrastructure.LogNotificationChannel} 打日志实现。
 */
public interface NotificationSender {
    String name();
    void send(String title, String message);
}
