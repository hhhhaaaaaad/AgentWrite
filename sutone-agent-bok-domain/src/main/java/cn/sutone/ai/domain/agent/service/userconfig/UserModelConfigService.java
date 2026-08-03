package cn.sutone.ai.domain.agent.service.userconfig;

import cn.sutone.ai.domain.agent.adapter.port.IApiKeyCryptoService;
import cn.sutone.ai.domain.agent.adapter.repository.IUserModelConfigRepository;
import cn.sutone.ai.domain.agent.model.entity.AiTaskEntity;
import cn.sutone.ai.domain.agent.model.entity.UserModelConfigEntity;
import cn.sutone.ai.domain.agent.model.valobj.UserModelConfigVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 用户模型配置领域服务
 * <p>
 * 负责解析任务执行时应使用的模型配置：
 * 1. 若 task 指定了 modelConfigId → 按 ID 查
 * 2. 否则查用户默认配置
 * 3. 都没有 → 返回 Optional.empty()，调用方降级到系统默认
 * </p>
 */
@Slf4j
@Service
public class UserModelConfigService {

    private final IUserModelConfigRepository configRepository;
    private final IApiKeyCryptoService cryptoService;

    public UserModelConfigService(IUserModelConfigRepository configRepository,
                                  IApiKeyCryptoService cryptoService) {
        this.configRepository = configRepository;
        this.cryptoService = cryptoService;
    }

    /**
     * 解析执行任务时使用的模型配置
     */
    public Optional<UserModelConfigVO> resolveForTask(AiTaskEntity task) {
        Long configId = task.getModelConfigId();
        Optional<UserModelConfigEntity> entity = (configId != null)
                ? configRepository.queryById(configId)
                : configRepository.queryDefaultByUserId(task.getUserId());

        return entity.map(e -> {
            String plain = cryptoService.decrypt(e.getApiKeyCipher());
            return new UserModelConfigVO(
                    e.getId(), e.getBaseUrl(), plain,
                    e.getModelName(), e.getCompletionsPath());
        });
    }

    /**
     * 查询用户默认配置（用于提交任务时快照 configId）
     */
    public Optional<UserModelConfigEntity> queryDefaultByUserId(Long userId) {
        return configRepository.queryDefaultByUserId(userId);
    }

    /**
     * 保存用户模型配置（加密后落库）
     */
    public void saveConfig(UserModelConfigEntity entity) {
        if (entity.getApiKeyPlain() != null) {
            entity.setApiKeyCipher(cryptoService.encrypt(entity.getApiKeyPlain()));
            entity.setApiKeyPlain(null);  // 防止明文意外泄漏
        }
        configRepository.save(entity);
    }

    /**
     * 更新用户模型配置
     */
    public void updateConfig(UserModelConfigEntity entity) {
        if (entity.getApiKeyPlain() != null) {
            entity.setApiKeyCipher(cryptoService.encrypt(entity.getApiKeyPlain()));
            entity.setApiKeyPlain(null);
        }
        configRepository.update(entity);
    }

    /** 删除配置 */
    public void delete(Long id, Long userId) {
        configRepository.delete(id, userId);
    }

    /** 查询用户所有配置（密文，不解密） */
    public java.util.List<UserModelConfigEntity> listByUserId(Long userId) {
        return configRepository.listByUserId(userId);
    }

    /** 测试 Key 有效性：用提供的 Key 发送简单请求 */
    public boolean verifyApiKey(String baseUrl, String apiKey) {
        // TODO: 调用 /models 接口验证 Key
        return true;
    }
}
