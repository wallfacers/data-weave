package com.dataweave.alert.infrastructure.channel;

import com.dataweave.alert.domain.AlertChannel;
import com.dataweave.alert.domain.AlertEvent;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 026 US2：EmailDispatcher 真发邮件（GreenMail 捕获）、失败/未配置三态。
 */
class EmailDispatcherTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    private AlertEvent event() {
        AlertEvent e = new AlertEvent();
        e.setId(1L);
        e.setTenantId(10L);
        e.setSeverity("HIGH");
        e.setValue("8.0");
        e.setFingerprint("metric:task.fail_rate");
        e.setContextJson("{\"metric_key\":\"task.fail_rate\",\"value\":8.0}");
        return e;
    }

    private AlertChannel channel(String configJson) {
        AlertChannel c = new AlertChannel();
        c.setId(1L);
        c.setTenantId(10L);
        c.setName("ops-email");
        c.setType("EMAIL");
        c.setConfigJson(configJson);
        c.setEnabled(1);
        return c;
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        ObjectProvider<JavaMailSender> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(sender);
        return p;
    }

    private JavaMailSenderImpl senderTo(String host, int port) {
        JavaMailSenderImpl s = new JavaMailSenderImpl();
        s.setHost(host);
        s.setPort(port);
        return s;
    }

    @Test
    void sends_real_email_to_recipients() throws Exception {
        JavaMailSenderImpl sender = senderTo("127.0.0.1", greenMail.getSmtp().getPort());
        EmailDispatcher dispatcher = new EmailDispatcher(provider(sender));

        DispatchResult result = dispatcher.dispatch(event(),
                channel("{\"recipients\":[\"ops@example.com\"]}"));

        assertThat(result.success()).isTrue();
        assertThat(greenMail.waitForIncomingEmail(5000, 1)).isTrue();
        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()[0].toString()).isEqualTo("ops@example.com");
        String body = greenMail.getReceivedMessages()[0].getContent().toString();
        assertThat(body).contains("HIGH").contains("8.0");
    }

    @Test
    void failed_when_smtp_unreachable() {
        // 指向无人监听的端口 → 发送异常（设短超时，快速失败，避免默认无超时长卡）
        JavaMailSenderImpl sender = senderTo("127.0.0.1", 1);
        sender.getJavaMailProperties().put("mail.smtp.connectiontimeout", "1000");
        sender.getJavaMailProperties().put("mail.smtp.timeout", "1000");
        EmailDispatcher dispatcher = new EmailDispatcher(provider(sender));

        DispatchResult result = dispatcher.dispatch(event(),
                channel("{\"recipients\":[\"ops@example.com\"]}"));

        assertThat(result.success()).isFalse();
        assertThat(result.configured()).isTrue(); // 配了但发失败，区别于未配置
        assertThat(result.error()).contains("email send failed");
    }

    @Test
    void not_configured_when_no_mail_sender() {
        EmailDispatcher dispatcher = new EmailDispatcher(provider(null));

        DispatchResult result = dispatcher.dispatch(event(),
                channel("{\"recipients\":[\"ops@example.com\"]}"));

        assertThat(result.success()).isFalse();
        assertThat(result.configured()).isFalse(); // 未配置
    }

    @Test
    void not_configured_when_no_recipients() {
        JavaMailSenderImpl sender = senderTo("127.0.0.1", greenMail.getSmtp().getPort());
        EmailDispatcher dispatcher = new EmailDispatcher(provider(sender));

        DispatchResult result = dispatcher.dispatch(event(), channel("{}"));

        assertThat(result.success()).isFalse();
        assertThat(result.configured()).isFalse(); // 无收件人 = 未配置
    }
}
