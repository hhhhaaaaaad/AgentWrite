package cn.sutone.ai.domain.agent.service.armory.matter.plugin;

import cn.sutone.ai.domain.agent.service.chat.CustomApiConfigManager;
import com.google.adk.agents.CallbackContext;
import com.google.adk.models.LlmRequest;
import com.google.adk.models.LlmResponse;
import com.google.adk.plugins.BasePlugin;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.HttpOptions;
import io.reactivex.rxjava3.core.Maybe;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service("customConfigPlugin")
public class CustomConfigPlugin extends BasePlugin {

    public CustomConfigPlugin() {
        super("CustomConfigPlugin");
    }

    // 在模型调用之前执行的回调方法
    @Override
    public Maybe<LlmResponse> beforeModelCallback(CallbackContext context, LlmRequest.Builder requestBuilder) {
        // 取自定义 API 配置管理器中的配置
        String sessionId = context.sessionId();
        // 取配置
        CustomApiConfigManager.CustomApiConfig config = CustomApiConfigManager.getConfig(sessionId);

        if (config != null) {
            GenerateContentConfig.Builder configBuilder;
            if (requestBuilder.config().isPresent()) {
                configBuilder = requestBuilder.config().get().toBuilder();
            } else {
                configBuilder = GenerateContentConfig.builder();
            }

            HttpOptions.Builder httpOptionsBuilder;
            if (configBuilder.build().httpOptions().isPresent()) {
                httpOptionsBuilder = configBuilder.build().httpOptions().get().toBuilder();
            } else {
                httpOptionsBuilder = HttpOptions.builder();
            }

            Map<String, String> headers = new HashMap<>();
            if (httpOptionsBuilder.build().headers().isPresent()) {
                headers.putAll(httpOptionsBuilder.build().headers().get());
            }

            // 判断是否有任何自定义配置（baseUrl、apiKey、completionsPath 中至少一个非空，或用户主动选了自定义模型）
            boolean hasCustomBaseUrl = StringUtils.isNotBlank(config.getBaseUrl());
            boolean hasCustomApiKey = StringUtils.isNotBlank(config.getApiKey());
            boolean hasCustomCompletionsPath = StringUtils.isNotBlank(config.getCompletionsPath());

            if (hasCustomBaseUrl) {
                headers.put("X-Custom-Base-Url", config.getBaseUrl());
            }
            if (hasCustomApiKey) {
                headers.put("X-Custom-Api-Key", config.getApiKey());
            }
            if (hasCustomCompletionsPath) {
                headers.put("X-Custom-Completions-Path", config.getCompletionsPath());
            }

            // 关键：只有当用户主动选择了自定义模型时才设置 requestBuilder.model()
            // config.isCustomModelSelected() 由 Controller 层根据前端传入的 customModel 字段是否非空来设置
            // 这样可以区分「用户选默认模型」和「用户选自定义模型」两种情况
            if (config.isCustomModelSelected() && StringUtils.isNotBlank(config.getModel())) {
                requestBuilder.model(config.getModel());
                // 同时在 headers 中传递标记，供 MyMessageConverter 判断
                headers.put("X-Custom-Model-Selected", "true");
            }

            httpOptionsBuilder.headers(headers);
            configBuilder.httpOptions(httpOptionsBuilder.build());
            requestBuilder.config(configBuilder.build());
        }

        return super.beforeModelCallback(context, requestBuilder);
    }
}
