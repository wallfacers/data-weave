package com.dataweave.master.application;

import com.dataweave.master.i18n.BizException;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加密/解密服务，用于数据源密码的安全存储。
 *
 * <p>主密钥从环境变量 {@code DATASOURCE_MASTER_KEY} 读取（32 字节 hex，共 64 字符）。
 * 每次加密生成随机 12 字节 IV，密文格式为 {@code base64(iv + ciphertext + tag)}。
 */
@Service
public class DatasourceEncryptor {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    @Value("${datasource.master-key:#{null}}")
    private String masterKeyHex;

    private SecretKeySpec secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @PostConstruct
    void init() {
        if (masterKeyHex == null || masterKeyHex.isBlank()) {
            throw new BizException("datasource.master_key_required");
        }
        byte[] keyBytes = hexToBytes(masterKeyHex.trim());
        if (keyBytes.length != 32) {
            throw new BizException("datasource.master_key_invalid_length", keyBytes.length);
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    /**
     * 加密明文密码。
     *
     * @param plainPassword 明文密码
     * @return base64 编码的密文（iv + ciphertext + tag）
     */
    public String encrypt(String plainPassword) {
        if (plainPassword == null || plainPassword.isEmpty()) {
            return plainPassword;
        }
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, gcmSpec);

            byte[] ciphertext = cipher.doFinal(plainPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // iv (12) + ciphertext + tag (16) are all in ciphertext array (GCM appends tag)
            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + ciphertext.length);
            byteBuffer.put(iv);
            byteBuffer.put(ciphertext);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt password", e);
        }
    }

    /**
     * 解密密文。
     *
     * @param passwordEnc base64 编码的密文
     * @return 解密后的明文密码
     * @throws DatasourceDecryptException 解密失败时抛出
     */
    public String decrypt(String passwordEnc) {
        if (passwordEnc == null || passwordEnc.isEmpty()) {
            return passwordEnc;
        }
        // If it doesn't look like base64 ciphertext (no padding, short), treat as plain (legacy)
        if (!passwordEnc.contains("=") && passwordEnc.length() < 20) {
            return passwordEnc;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(passwordEnc);
            if (decoded.length < IV_LENGTH_BYTES + 16) {
                throw new DatasourceDecryptException("Invalid ciphertext: too short");
            }

            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);
            byte[] iv = new byte[IV_LENGTH_BYTES];
            byteBuffer.get(iv);
            byte[] ciphertext = new byte[byteBuffer.remaining()];
            byteBuffer.get(ciphertext);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, gcmSpec);

            byte[] plainBytes = cipher.doFinal(ciphertext);
            return new String(plainBytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (DatasourceDecryptException e) {
            throw e;
        } catch (Exception e) {
            throw new DatasourceDecryptException("数据源密码解密失败");
        }
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
