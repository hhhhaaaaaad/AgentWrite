package cn.sutone.ai.domain.agent.adapter.repository;

import cn.sutone.ai.domain.agent.model.entity.UserModelConfigEntity;

import java.util.List;
import java.util.Optional;

/**
 * 用户模型配置仓储接口
 */
public interface IUserModelConfigRepository {

    /** 查询用户默认配置（is_default=1 且 is_enabled=1），解密 apiKey 后注入 entity.apiKeyPlain */
    Optional<UserModelConfigEntity> queryDefaultByUserId(Long userId);

    Optional<UserModelConfigEntity> queryById(Long configId);

    void save(UserModelConfigEntity entity);

    void update(UserModelConfigEntity entity);

    void delete(Long id, Long userId);

    List<UserModelConfigEntity> listByUserId(Long userId);
}
