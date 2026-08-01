package cn.sutone.ai.infrastructure.dao.po;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户模型配置 PO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserModelConfigPO {

    private Long id;
    private Long userId;
    private String configName;
    private String provider;
    private String baseUrl;
    private String apiKeyCipher;
    private String modelName;
    private String completionsPath;
    private Integer isDefault;
    private Integer isEnabled;
    private Integer keyVersion;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
