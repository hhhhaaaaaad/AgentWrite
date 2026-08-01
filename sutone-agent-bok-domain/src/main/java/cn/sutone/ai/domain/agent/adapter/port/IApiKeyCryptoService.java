package cn.sutone.ai.domain.agent.adapter.port;

/**
 * API Key 加密/解密服务接口
 * <p>
 * 加密格式：Base64( 12字节 IV || CipherText || 16字节 AuthTag )
 * 使用 AES-256-GCM 认证加密（AEAD），防密文篡改且不需要额外 HMAC。
 * </p>
 */
public interface IApiKeyCryptoService {

    /** 加密明文 API Key */
    String encrypt(String plainKey);

    /** 解密为明文 API Key */
    String decrypt(String cipherKey);
}
