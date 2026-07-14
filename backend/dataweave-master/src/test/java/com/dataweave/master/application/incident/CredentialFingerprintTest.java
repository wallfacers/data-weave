package com.dataweave.master.application.incident;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 067 T027：确定性凭据故障指纹——命中即免 LLM 直判 CONFIG_CREDENTIAL（US1/research R4）。
 */
class CredentialFingerprintTest {

    @Test
    void matches_englishAuthPatterns() {
        assertThat(CredentialFingerprint.matches("java.sql.SQLException: Authentication failed")).isTrue();
        assertThat(CredentialFingerprint.matches("Access denied for user 'etl'@'%'")).isTrue();
        assertThat(CredentialFingerprint.matches("Invalid username or password")).isTrue();
        assertThat(CredentialFingerprint.matches("Login failed for user 'sa'")).isTrue();
        assertThat(CredentialFingerprint.matches("ORA-01017: invalid username/password; logon denied")).isTrue();
        assertThat(CredentialFingerprint.matches("ERROR 1045 (28000): Access denied for user")).isTrue();
        assertThat(CredentialFingerprint.matches("FATAL: password authentication failed for user \"pg\"")).isTrue();
    }

    @Test
    void matches_chinesePatterns() {
        assertThat(CredentialFingerprint.matches("连接失败：用户名或密码错误")).isTrue();
        assertThat(CredentialFingerprint.matches("数据源鉴权失败，请检查配置")).isTrue();
        assertThat(CredentialFingerprint.matches("Token 认证失败")).isTrue();
    }

    @Test
    void matches_unrelatedFailure_returnsFalse() {
        assertThat(CredentialFingerprint.matches("Table 'orders' doesn't exist")).isFalse();
        assertThat(CredentialFingerprint.matches("java.lang.OutOfMemoryError: Java heap space")).isFalse();
        assertThat(CredentialFingerprint.matches("Connection timed out after 30000ms")).isFalse();
    }

    @Test
    void matches_blankOrNull_returnsFalse() {
        assertThat(CredentialFingerprint.matches(null)).isFalse();
        assertThat(CredentialFingerprint.matches("")).isFalse();
    }
}
