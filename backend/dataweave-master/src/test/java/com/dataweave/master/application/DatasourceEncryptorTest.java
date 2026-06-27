package com.dataweave.master.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatasourceEncryptorTest {

    private DatasourceEncryptor encryptor;

    // 64-char hex = 32 bytes (valid AES-256 key)
    private static final String TEST_MASTER_KEY =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @BeforeEach
    void setUp() throws Exception {
        encryptor = new DatasourceEncryptor();
        // Inject test master key via reflection (bypasses @Value/Spring)
        Field field = DatasourceEncryptor.class.getDeclaredField("masterKeyHex");
        field.setAccessible(true);
        field.set(encryptor, TEST_MASTER_KEY);
        encryptor.init();
    }

    @Test
    void encryptAndDecrypt_roundTrip() {
        String plain = "my_secret_password";
        String encrypted = encryptor.encrypt(plain);

        assertThat(encrypted).isNotNull();
        assertThat(encrypted).isNotEqualTo(plain);

        String decrypted = encryptor.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(plain);
    }

    @Test
    void encrypt_producesDifferentCiphertextEachTime() {
        String plain = "same_password";
        String enc1 = encryptor.encrypt(plain);
        String enc2 = encryptor.encrypt(plain);

        // Random IV → different ciphertext for same plaintext
        assertThat(enc1).isNotEqualTo(enc2);

        // But both decrypt to same plaintext
        assertThat(encryptor.decrypt(enc1)).isEqualTo(plain);
        assertThat(encryptor.decrypt(enc2)).isEqualTo(plain);
    }

    @Test
    void decrypt_withWrongKey_throwsException() throws Exception {
        String encrypted = encryptor.encrypt("secret");

        // Create a new encryptor with a different key
        DatasourceEncryptor otherEncryptor = new DatasourceEncryptor();
        Field field = DatasourceEncryptor.class.getDeclaredField("masterKeyHex");
        field.setAccessible(true);
        field.set(otherEncryptor,
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
        otherEncryptor.init();

        assertThatThrownBy(() -> otherEncryptor.decrypt(encrypted))
                .isInstanceOf(DatasourceDecryptException.class)
                .hasMessageContaining("解密失败");
    }

    @Test
    void decrypt_tamperedCiphertext_throwsException() {
        String encrypted = encryptor.encrypt("secret");
        // Tamper with the ciphertext
        String tampered = encrypted.substring(0, encrypted.length() - 2) + "XX";

        assertThatThrownBy(() -> encryptor.decrypt(tampered))
                .isInstanceOf(DatasourceDecryptException.class);
    }

    @Test
    void encrypt_longPassword_200chars() {
        String longPassword = "A".repeat(200);
        String encrypted = encryptor.encrypt(longPassword);

        assertThat(encrypted).isNotNull();
        assertThat(encrypted.length()).isLessThanOrEqualTo(512);

        String decrypted = encryptor.decrypt(encrypted);
        assertThat(decrypted).isEqualTo(longPassword);
    }

    @Test
    void encrypt_null_returnsNull() {
        assertThat(encryptor.encrypt(null)).isNull();
        assertThat(encryptor.decrypt(null)).isNull();
    }

    @Test
    void encrypt_empty_returnsEmpty() {
        assertThat(encryptor.encrypt("")).isEmpty();
        assertThat(encryptor.decrypt("")).isEmpty();
    }

    @Test
    void init_invalidKeyLength_throwsException() throws Exception {
        DatasourceEncryptor bad = new DatasourceEncryptor();
        Field field = DatasourceEncryptor.class.getDeclaredField("masterKeyHex");
        field.setAccessible(true);
        field.set(bad, "0123456789abcdef"); // only 8 bytes

        assertThatThrownBy(bad::init)
                .isInstanceOf(com.dataweave.master.i18n.BizException.class)
                .hasMessageContaining("master_key_invalid_length");
    }

    @Test
    void init_missingKey_throwsException() throws Exception {
        DatasourceEncryptor bad = new DatasourceEncryptor();
        Field field = DatasourceEncryptor.class.getDeclaredField("masterKeyHex");
        field.setAccessible(true);
        field.set(bad, null);

        assertThatThrownBy(bad::init)
                .isInstanceOf(com.dataweave.master.i18n.BizException.class)
                .hasMessageContaining("master_key_required");
    }
}
