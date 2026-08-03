package cn.sutone.ai.infrastructure.adapter.repository;

import cn.sutone.ai.domain.agent.adapter.repository.IUserModelConfigRepository;
import cn.sutone.ai.domain.agent.model.entity.UserModelConfigEntity;
import cn.sutone.ai.infrastructure.dao.IUserModelConfigDao;
import cn.sutone.ai.infrastructure.dao.po.UserModelConfigPO;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 用户模型配置仓储实现
 */
@Repository
public class UserModelConfigRepository implements IUserModelConfigRepository {

    private final IUserModelConfigDao dao;

    public UserModelConfigRepository(IUserModelConfigDao dao) {
        this.dao = dao;
    }

    @Override
    public Optional<UserModelConfigEntity> queryDefaultByUserId(Long userId) {
        UserModelConfigPO po = dao.selectDefaultByUserId(userId);
        return Optional.ofNullable(toEntity(po));
    }

    @Override
    public Optional<UserModelConfigEntity> queryById(Long configId) {
        UserModelConfigPO po = dao.selectById(configId);
        return Optional.ofNullable(toEntity(po));
    }

    @Override
    public void save(UserModelConfigEntity entity) {
        UserModelConfigPO po = toPO(entity);
        dao.insert(po);
        entity.setId(po.getId());
    }

    @Override
    public void update(UserModelConfigEntity entity) {
        dao.update(toPO(entity));
    }

    @Override
    public void delete(Long id, Long userId) {
        dao.delete(id, userId);
    }

    @Override
    public List<UserModelConfigEntity> listByUserId(Long userId) {
        return dao.selectByUserId(userId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    private UserModelConfigPO toPO(UserModelConfigEntity entity) {
        return UserModelConfigPO.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .configName(entity.getConfigName())
                .provider(entity.getProvider())
                .baseUrl(entity.getBaseUrl())
                .apiKeyCipher(entity.getApiKeyCipher())
                .modelName(entity.getModelName())
                .completionsPath(entity.getCompletionsPath())
                .isDefault(entity.isDefault() ? 1 : 0)
                .isEnabled(entity.isEnabled() ? 1 : 0)
                .keyVersion(entity.getKeyVersion())
                .build();
    }

    private UserModelConfigEntity toEntity(UserModelConfigPO po) {
        if (po == null) return null;
        return UserModelConfigEntity.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .configName(po.getConfigName())
                .provider(po.getProvider())
                .baseUrl(po.getBaseUrl())
                .apiKeyCipher(po.getApiKeyCipher())
                .modelName(po.getModelName())
                .completionsPath(po.getCompletionsPath())
                .isDefault(po.getIsDefault() != null && po.getIsDefault() == 1)
                .isEnabled(po.getIsEnabled() != null && po.getIsEnabled() == 1)
                .keyVersion(po.getKeyVersion() != null ? po.getKeyVersion() : 1)
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}
