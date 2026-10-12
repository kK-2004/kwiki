package com.kwiki.infrastructure.ai.registry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * provider API key 的 AES-256-GCM 加解密。主密钥来自 kwiki.model-config.master-key
 * （Base64 编码的 32 字节），密文格式为 {@code v1:} + Base64(nonce‖ciphertext‖tag)。
 * 未配置主密钥时可以构造，但任何加解密都会明确失败；异常信息不含明文或密文。
 */
@Component
public class ModelSecretCipher {

    private static final String PREFIX = "v1:";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public ModelSecretCipher(@Value("${kwiki.model-config.master-key:}") String base64MasterKey) {
        if (base64MasterKey == null || base64MasterKey.isBlank()) {
            this.key = null;
            return;
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64MasterKey.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("kwiki.model-config.master-key 不是合法的 Base64");
        }
        if (raw.length != 32) {
            throw new IllegalStateException("kwiki.model-config.master-key 必须是 32 字节（Base64 编码）");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    public boolean configured() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return PREFIX + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + sealed.length).put(nonce).put(sealed).array());
        } catch (Exception e) {
            throw new IllegalStateException("API key 加密失败");
        }
    }

    public String decrypt(String stored) {
        requireKey();
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("API key 密文格式无法识别");
        }
        try {
            byte[] raw = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, NONCE_BYTES));
            return new String(cipher.doFinal(raw, NONCE_BYTES, raw.length - NONCE_BYTES), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("API key 解密失败：主密钥不匹配或密文已损坏");
        }
    }

    /** 后台展示用掩码：保留前 3 位与后 4 位；短 key 只保留后 2 位。 */
    public static String hint(String plaintext) {
        if (plaintext == null || plaintext.isEmpty()) {
            return "****";
        }
        if (plaintext.length() < 10) {
            return "****" + plaintext.substring(Math.max(0, plaintext.length() - 2));
        }
        return plaintext.substring(0, 3) + "****" + plaintext.substring(plaintext.length() - 4);
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("未配置 kwiki.model-config.master-key，无法读写模型 API key");
        }
    }
}
