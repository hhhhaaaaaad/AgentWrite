package cn.sutone.ai.domain.agent.service.armory.node;

import cn.sutone.ai.domain.agent.model.entity.ArmoryCommandEntity;
import cn.sutone.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.sutone.ai.domain.agent.model.valobj.AiAgentRegisterVO;
import cn.sutone.ai.domain.agent.model.valobj.UserModelConfigVO;
import cn.sutone.ai.domain.agent.service.armory.AbstractArmorySupport;
import cn.sutone.ai.domain.agent.service.armory.factory.DefaultArmoryFactory;
import cn.sutone.ai.domain.agent.service.armory.matter.patch.CustomApiInterceptor;
import cn.sutone.ai.domain.agent.service.armory.matter.patch.CustomApiWebClientFilter;
import cn.bugstack.wrench.design.framework.tree.StrategyHandler;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import javax.annotation.Resource;

/**
 * AI API 节点：构建 OpenAiApi 客户端
 * <p>多租户场景下，优先使用用户自定义配置（baseUrl/apiKey），
 * 降级到系统默认配置。</p>
 */
@Slf4j
@Service
public class AiApiNode extends AbstractArmorySupport {

    @Resource
    private ChatModelNode chatModelNode;

    @Override
    protected AiAgentRegisterVO doApply(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        log.info("Ai Agent 装配操作 - AiApiNode");

        AiAgentConfigTableVO aiAgentConfigTableVO = requestParameter.getAiAgentConfigTableVO();
        AiAgentConfigTableVO.Module.AiApi aiApiConfig = aiAgentConfigTableVO.getModule().getAiApi();

        // 多租户：优先使用用户自定义模型配置，降级到系统默认
        UserModelConfigVO userCfg = requestParameter.getUserModelConfig();
        String baseUrl = (userCfg != null) ? userCfg.baseUrl() : aiApiConfig.getBaseUrl();
        String apiKey  = (userCfg != null) ? userCfg.apiKeyPlain() : aiApiConfig.getApiKey();
        String completionsPath = (userCfg != null)
                ? userCfg.completionsPath()
                : (StringUtils.isNotBlank(aiApiConfig.getCompletionsPath()) ? aiApiConfig.getCompletionsPath() : "v1/chat/completions");
        String embeddingsPath = StringUtils.isNotBlank(aiApiConfig.getEmbeddingsPath()) ? aiApiConfig.getEmbeddingsPath() : "v1/embeddings";

        OpenAiApi openAiApi = OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .completionsPath(completionsPath)
                .embeddingsPath(embeddingsPath)
                .restClientBuilder(RestClient.builder().requestInterceptor(new CustomApiInterceptor()))
                .webClientBuilder(WebClient.builder().filter(new CustomApiWebClientFilter()))
                .build();

        dynamicContext.setOpenAiApi(openAiApi);

        return router(requestParameter, dynamicContext);
    }

    @Override
    public StrategyHandler<ArmoryCommandEntity, DefaultArmoryFactory.DynamicContext, AiAgentRegisterVO> get(ArmoryCommandEntity requestParameter, DefaultArmoryFactory.DynamicContext dynamicContext) throws Exception {
        return chatModelNode;
    }

}
