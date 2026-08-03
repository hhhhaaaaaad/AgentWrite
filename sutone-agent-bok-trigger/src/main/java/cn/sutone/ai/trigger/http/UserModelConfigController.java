package cn.sutone.ai.trigger.http;

import cn.sutone.ai.api.response.Response;
import cn.sutone.ai.domain.agent.model.entity.UserModelConfigEntity;
import cn.sutone.ai.domain.agent.service.userconfig.UserModelConfigService;
import cn.sutone.ai.trigger.security.AuthUtil;
import cn.sutone.ai.types.enums.ResponseCode;
import cn.sutone.ai.types.exception.AppException;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 用户模型配置 Controller
 * <p>
 * 提供 API Key 和模型 URL 的增删改查，以及 Key 有效性验证。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/model-config")
@CrossOrigin(origins = "*")
public class UserModelConfigController {

    @Resource
    private UserModelConfigService userModelConfigService;

    /** 保存/更新配置 */
    @PostMapping("/save")
    public Response<Void> save(@RequestBody SaveModelConfigRequest req) {
        try {
            Long userId = AuthUtil.getCurrentUserId();
            UserModelConfigEntity entity = UserModelConfigEntity.builder()
                    .id(req.getId())
                    .userId(userId)
                    .configName(req.getConfigName())
                    .provider(req.getProvider())
                    .baseUrl(req.getBaseUrl())
                    .apiKeyPlain(req.getApiKey())  // 明文，service 层加密后落库
                    .modelName(req.getModelName())
                    .completionsPath(req.getCompletionsPath() != null ? req.getCompletionsPath() : "/v1/chat/completions")
                    .isDefault(req.isDefault())
                    .isEnabled(true)
                    .build();

            if (req.getId() != null) {
                userModelConfigService.updateConfig(entity);
            } else {
                userModelConfigService.saveConfig(entity);
            }
            return Response.<Void>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .build();
        } catch (Exception e) {
            log.error("保存模型配置失败", e);
            return fail(e);
        }
    }

    /** 获取用户所有配置（Key 脱敏，只展示末 4 位） */
    @GetMapping("/list")
    public Response<List<ModelConfigDTO>> list() {
        Long userId = AuthUtil.getCurrentUserId();
        List<UserModelConfigEntity> configs = userModelConfigService.listByUserId(userId);
        List<ModelConfigDTO> dtos = configs.stream()
                .map(c -> {
                    // 密文脱敏：展示 **** + 末 4 位 base64 字符
                    String masked = c.getApiKeyCipher() != null && c.getApiKeyCipher().length() > 4
                            ? "****" + c.getApiKeyCipher().substring(c.getApiKeyCipher().length() - 4)
                            : "****";
                    return new ModelConfigDTO(c.getId(), c.getConfigName(), c.getProvider(),
                            c.getBaseUrl(), masked, c.getModelName(),
                            c.getCompletionsPath(), c.isDefault(), c.isEnabled());
                })
                .collect(Collectors.toList());
        return Response.<List<ModelConfigDTO>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(dtos)
                .build();
    }

    /** 删除配置 */
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable Long id) {
        Long userId = AuthUtil.getCurrentUserId();
        userModelConfigService.delete(id, userId);
        return Response.<Void>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .build();
    }

    /** 设为默认配置 */
    @PutMapping("/{id}/default")
    public Response<Void> setDefault(@PathVariable Long id) {
        // 先取消当前所有默认，再设置新的默认（简化实现：直接调一次 update）
        Long userId = AuthUtil.getCurrentUserId();
        UserModelConfigEntity entity = userModelConfigService.queryDefaultByUserId(userId)
                .orElseThrow(() -> new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "配置不存在"));
        entity.setDefault(true);
        userModelConfigService.updateConfig(entity);
        return Response.<Void>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .build();
    }

    /** 验证 API Key 有效性 */
    @PostMapping("/verify")
    public Response<VerifyResult> verifyKey(@RequestBody VerifyKeyRequest req) {
        try {
            // 发送一个最简单的 /models 请求验证 Key
            // 实现略（用 req.getBaseUrl() + req.getApiKey() 调一次 API）
            boolean valid = userModelConfigService.verifyApiKey(req.getBaseUrl(), req.getApiKey());
            return Response.<VerifyResult>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(valid ? "Key 有效" : "Key 无效")
                    .data(new VerifyResult(valid))
                    .build();
        } catch (Exception e) {
            return Response.<VerifyResult>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info("Key 验证失败: " + e.getMessage())
                    .data(new VerifyResult(false))
                    .build();
        }
    }

    @Data
    public static class SaveModelConfigRequest {
        private Long id;
        private String configName;
        private String provider;
        private String baseUrl;
        private String apiKey;
        private String modelName;
        private String completionsPath;
        private boolean isDefault;
    }

    public record ModelConfigDTO(Long id, String configName, String provider, String baseUrl,
                                  String apiKeyMasked, String modelName, String completionsPath,
                                  boolean isDefault, boolean isEnabled) {}

    public record VerifyResult(boolean valid) {}

    @Data
    public static class VerifyKeyRequest {
        private String baseUrl;
        private String apiKey;
    }

    private <T> Response<T> fail(Exception e) {
        String code = ResponseCode.UN_ERROR.getCode();
        String info = e.getMessage();
        if (e instanceof AppException ae) {
            code = ae.getCode() != null ? ae.getCode() : code;
            info = ae.getInfo() != null ? ae.getInfo() : info;
        }
        return Response.<T>builder().code(code).info(info).build();
    }
}
