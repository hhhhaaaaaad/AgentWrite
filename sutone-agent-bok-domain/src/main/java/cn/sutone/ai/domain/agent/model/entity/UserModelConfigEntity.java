package cn.sutone.ai.domain.agent.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户自定义模型配置实体
 * <p>
 * apiKeyCipher 存储 AES-GCM 加密密文，apiKeyPlain 仅运行时临时持有，不入库不序列化。
 * </p>
 */
@Data
@Builder
public class UserModelConfigEntity {

    private Long id;
    private Long userId;
    private String configName;
    private String provider;
    private String baseUrl;
    private String apiKeyCipher;       // 密文（落库）
    private String modelName;
    private String completionsPath;
    private boolean isDefault;
    private boolean isEnabled;
    private int keyVersion;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 解密后的明文 Key，仅运行时临时持有，不序列化不落库 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private transient String apiKeyPlain;
}
