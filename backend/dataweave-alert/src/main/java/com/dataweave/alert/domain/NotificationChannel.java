package com.dataweave.alert.domain;

/**
 * 通知通道接口（骨架）。后期实现：邮件 / 钉钉 / 企业微信 / Webhook 等多通道。
 */
public interface NotificationChannel {

    /** 通道名，如 log / email / dingtalk。 */
    String name();

    /** 发送通知。 */
    void send(String title, String message);
}
