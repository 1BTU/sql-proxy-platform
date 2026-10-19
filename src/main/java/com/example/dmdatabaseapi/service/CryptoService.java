package com.example.dmdatabaseapi.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** 使用部署主密钥加密数据库密码和可回显的代理令牌。 */
@Service
public class CryptoService {
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH = 128;
    private final SecretKeySpec key;
    private final SecureRandom secureRandom = new SecureRandom();

    public CryptoService(@Value("${app.encryption-key:}") String configuredKey) {
        if (configuredKey == null || configuredKey.isBlank()) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY 未配置，请提供 Base64 编码的 32 字节密钥");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(configuredKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY 必须是有效的 Base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("APP_ENCRYPTION_KEY 解码后必须正好为 32 字节");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] output = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, output, 0, iv.length);
            System.arraycopy(encrypted, 0, output, iv.length, encrypted.length);
            return Base64.getEncoder().encodeToString(output);
        } catch (Exception e) {
            throw new IllegalStateException("敏感数据加密失败", e);
        }
    }

    public String decrypt(String ciphertext) {
        try {
            byte[] input = Base64.getDecoder().decode(ciphertext);
            if (input.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文长度无效");
            }
            byte[] iv = new byte[IV_LENGTH];
            byte[] encrypted = new byte[input.length - IV_LENGTH];
            System.arraycopy(input, 0, iv, 0, IV_LENGTH);
            System.arraycopy(input, IV_LENGTH, encrypted, 0, encrypted.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("敏感数据解密失败，请检查 APP_ENCRYPTION_KEY 是否一致", e);
        }
    }

    public String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("摘要计算失败", e);
        }
    }
}
