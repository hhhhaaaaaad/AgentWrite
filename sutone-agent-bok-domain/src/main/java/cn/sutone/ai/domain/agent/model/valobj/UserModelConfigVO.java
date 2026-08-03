package cn.sutone.ai.domain.agent.model.valobj;

/**
 * 用户自定义模型配置运行时快照（不可变）
 * <p>
 * 传递给策略树用于动态构建 OpenAiApi，
 * configId 为 null 时表示使用系统默认配置。
 * </p>
 */
public record UserModelConfigVO(
        Long configId,
        String baseUrl,
        String apiKeyPlain,
        String modelName,
        String completionsPath) {

    /** 是否为系统默认配置（未指定用户自定义配置时） */
    public boolean isSystemDefault() {
        return configId == null;
    }
}
