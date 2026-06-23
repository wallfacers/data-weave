package com.dataweave.master.application;

import com.dataweave.master.application.DatasourceDtos.ConnectionTestResult;
import com.dataweave.master.domain.Datasource;

import java.util.Locale;

/**
 * Strategy interface for testing datasource connectivity.
 * Different implementations for JDBC, MongoDB, Redis, etc.
 *
 * <p>i18n 规则②：连通测试文案按调用方传入的 locale（UI 场景=Accept-Language，Agent 场景=agent locale）
 * 本地化，经 {@code Messages} 出口。
 */
public interface ConnectionTester {

    /**
     * Test connectivity to a datasource, localizing messages by the given locale.
     *
     * @param ds the datasource entity (password_enc may still be encrypted)
     * @param decryptedPassword the decrypted password (null if no password)
     * @param locale locale for result message localization
     * @return test result with success/failure, message, latency, server version
     */
    ConnectionTestResult test(Datasource ds, String decryptedPassword, Locale locale);

    /** 默认中文 locale 的向后兼容入口。 */
    default ConnectionTestResult test(Datasource ds, String decryptedPassword) {
        return test(ds, decryptedPassword, Locale.SIMPLIFIED_CHINESE);
    }

    /**
     * Check if this tester supports the given datasource type.
     */
    boolean supports(String typeCode);
}
