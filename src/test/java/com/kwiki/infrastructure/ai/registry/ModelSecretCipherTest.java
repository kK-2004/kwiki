package com.kwiki.infrastructure.ai.registry;

import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelSecretCipherTest {

    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test
    void 加密后可解密且每次密文不同() {
        ModelSecretCipher cipher = new ModelSecretCipher(KEY);
        String a = cipher.encrypt("sk-secret-value");
        String b = cipher.encrypt("sk-secret-value");
        assertThat(a).startsWith("v1:").isNotEqualTo(b).doesNotContain("sk-secret-value");
        assertThat(cipher.decrypt(a)).isEqualTo("sk-secret-value");
    }

    @Test
    void 篡改密文时失败且不泄露内容() {
        ModelSecretCipher cipher = new ModelSecretCipher(KEY);
        byte[] raw = Base64.getDecoder().decode(cipher.encrypt("sk-secret-value").substring(3));
        raw[raw.length - 1] ^= 1;
        String tampered = "v1:" + Base64.getEncoder().encodeToString(raw);
        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("sk-secret");
    }

    @Test
    void 主密钥必须是32字节() {
        assertThatThrownBy(() -> new ModelSecretCipher(Base64.getEncoder().encodeToString(new byte[16])))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new ModelSecretCipher("").configured()).isFalse();
        assertThatThrownBy(() -> new ModelSecretCipher("").encrypt("x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kwiki.model-config.master-key");
    }

    @Test
    void 掩码规则() {
        assertThat(ModelSecretCipher.hint("sk-abcdef123456")).isEqualTo("sk-****3456");
        assertThat(ModelSecretCipher.hint("abc12")).isEqualTo("****12");
        assertThat(ModelSecretCipher.hint("")).isEqualTo("****");
    }
}
