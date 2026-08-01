package cn.sutone.ai.infrastructure.adapter.port;

import cn.sutone.ai.domain.agent.adapter.port.IApiKeyCryptoService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM API Key 加密/解密实现
 *
 * <p>加密格式：Base64( IV(12B) || CipherText || AuthTag(16B) )
 * 密钥从环境变量 crypto.api-key.secret 加载（32字节十六进制），不进代码库。
 * GCM 提供认证加密（AEAD），防密文篡改且不需要额外 HMAC。
 * </p>
 */
@Slf4j
@Service
public class AesGcmApiKeyCryptoService implements IApiKeyCryptoService {

    private static final int IV_LEN = 12;
    private static final int TAG_LEN = 128;  // GCM auth tag length in bits
    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final SecretKeySpec secretKey;

    public AesGcmApiKeyCryptoService(
            @Value("${crypto.api-key.secret}") String secretHex) {
        if (secretHex == null || secretHex.length() != 64) {
            throw new IllegalArgumentException(
                    "crypto.api-key.secret must be 32-byte hex string (64 chars)");
        }
        byte[] keyBytes = hexToBytes(secretHex);
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String encrypt(String plainKey) {
        try {
            byte[] iv = new byte[IV_LEN];
            SECURE_RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LEN, iv);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

            byte[] cipherText = cipher.doFinal(plainKey.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // IV(12B) + CipherText
            ByteBuffer buffer = ByteBuffer.allocate(IV_LEN + cipherText.length);
            buffer.put(iv);
            buffer.put(cipherText);

            return Base64.getEncoder().encodeToString(buffer.array());
        } catch (Exception e) {
            throw new RuntimeException("API Key encryption failed", e);
        }
    }

    @Override
    public String decrypt(String cipherKey) {
        try {
            byte[] data = Base64.getDecoder().decode(cipherKey);

            // 拆出 IV(12B) 和 CipherText+AuthTag（剩余全部）
            ByteBuffer buffer = ByteBuffer.wrap(data);
            byte[] iv = new byte[IV_LEN];
            buffer.get(iv);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_LEN, iv);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

            byte[] plainBytes = cipher.doFinal(cipherText);
            return new String(plainBytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("API Key decryption failed, key may be corrupted or encrypted with different secret");
            throw new RuntimeException("API Key decryption failed", e);
        }
    }

    private static byte[] hexToBytes(String hex) {
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
