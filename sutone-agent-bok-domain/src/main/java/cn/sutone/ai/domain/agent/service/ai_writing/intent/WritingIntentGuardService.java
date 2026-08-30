package cn.sutone.ai.domain.agent.service.ai_writing.intent;

import cn.sutone.ai.domain.agent.service.ai_writing.intent.model.WritingIntentDecisionVO;
import cn.sutone.ai.domain.agent.service.ai_writing.intent.model.WritingIntentPrecheckContextVO;
import cn.sutone.ai.domain.agent.service.ai_writing.intent.model.WritingIntentPrecheckResultVO;
import cn.sutone.ai.domain.agent.service.ai_writing.intent.model.WritingIntentTypeVO;
import cn.sutone.ai.domain.content.model.entity.DraftEntity;
import cn.sutone.ai.domain.content.service.draft.DraftDomainService;
import cn.sutone.ai.types.common.RedisKeyConstants;
import cn.sutone.ai.types.enums.ResponseCode;
import cn.sutone.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 写作意图守卫编排服务。
 *
 * <p>职责：预检（规则预筛 → 模型二判 → 签发凭证）与 submit 前凭证兜底校验。
 * 不修改现有异步写作主链路，仅在任务创建前增加守卫。</p>
 */
@Slf4j
@Service
public class WritingIntentGuardService {

    /** 预检接口限流：每用户每分钟次数 */
    private static final long PRECHECK_RATE = 10;
    /** 模型二判限流：每用户每分钟次数 */
    private static final long MODEL_RATE = 5;
    /** 预检结果缓存 TTL（秒） */
    private static final long CACHE_TTL_SECONDS = 60;

    private final WritingIntentRuleEngine ruleEngine;
    private final WritingIntentModelClassifier modelClassifier;
    private final PrecheckTokenService tokenService;
    private final DraftDomainService draftDomainService;
    private final RedissonClient redissonClient;

    /** 灰度开关：submit 侧是否强制要求 precheckToken（全量验证后切 true） */
    @Value("${ai-writing.guard.enforce:false}")
    private boolean enforce;

    /** 规则预筛开关 */
    @Value("${ai-writing.guard.rule-enabled:true}")
    private boolean ruleEnabled;

    /** 模型二判开关 */
    @Value("${ai-writing.guard.model-enabled:true}")
    private boolean modelEnabled;

    public WritingIntentGuardService(WritingIntentRuleEngine ruleEngine,
                                     WritingIntentModelClassifier modelClassifier,
                                     PrecheckTokenService tokenService,
                                     DraftDomainService draftDomainService,
                                     RedissonClient redissonClient) {
        this.ruleEngine = ruleEngine;
        this.modelClassifier = modelClassifier;
        this.tokenService = tokenService;
        this.draftDomainService = draftDomainService;
        this.redissonClient = redissonClient;
    }

    /**
     * 预检入口：返回最终决策与预检凭证。
     */
    public WritingIntentPrecheckResultVO precheck(Long userId, Long draftId, String taskType,
                                                  Map<String, Object> promptParams, Boolean enableIllustration) {
        // 0. 接口限流
        acquirePrecheck(userId);
        // 1. 查草稿（含 owner 校验）
        DraftEntity draft = draftDomainService.queryDraftDetail(draftId, userId);
        // 2. 构建上下文
        WritingIntentPrecheckContextVO ctx = buildContext(userId, draft, taskType, promptParams, enableIllustration);
        // 3. 缓存 key
        String promptHash = PrecheckTokenService.promptHash(draftId, taskType,
                ctx.getCustomInstruction(), ctx.getFormatInstruction());
        String cacheKey = cacheKey(userId, draftId, taskType, promptHash);

        // 4. 命中缓存：跳过规则/模型，直接重新签发凭证返回
        WritingIntentPrecheckResultVO cached = loadCache(cacheKey);
        if (null != cached) {
            log.info("写作意图预检命中缓存 userId={} draftId={} taskType={}", userId, draftId, taskType);
            return withToken(cached, userId, draftId, taskType, promptHash);
        }

        // 5. 规则预筛（ruleEnabled 关闭时跳过规则，直接交模型）
        WritingIntentDecisionVO ruleDecision = ruleEnabled
                ? ruleEngine.evaluate(ctx)
                : WritingIntentDecisionVO.UNCERTAIN;
        log.info("写作意图预检规则结果 decision={} ruleEnabled={} userId={} draftId={} taskType={}",
                ruleDecision.getCode(), ruleEnabled, userId, draftId, taskType);

        WritingIntentPrecheckResultVO result;
        if (ruleDecision == WritingIntentDecisionVO.PASS) {
            result = passResult();
        } else if (ruleDecision == WritingIntentDecisionVO.BLOCK) {
            result = blockResult(ctx);
        } else {
            // UNCERTAIN -> 模型二判（独立限流，超限直接 CONFIRM_REQUIRED）
            if (!modelEnabled) {
                result = confirmRequiredResult("模型二判未启用");
            } else if (!tryAcquireModel(userId)) {
                log.warn("模型二判限流，降级 CONFIRM_REQUIRED userId={} taskType={}", userId, taskType);
                result = confirmRequiredResult("模型二判限流");
            } else {
                result = modelClassifier.classify(ctx);
            }
        }

        // 6. 写缓存（只缓存决策，不含 token）
        saveCache(cacheKey, result);

        // 7. 签发凭证
        return withToken(result, userId, draftId, taskType, promptHash);
    }

    /**
     * submit 前凭证兜底校验（灰度）。
     *
     * <p>enforce=false 时缺失 token 放行（记 warn 日志供观察），enforce=true 时缺失即拒绝。</p>
     */
    public void verifySubmit(Long userId, Long draftId, String taskType,
                             Map<String, Object> promptParams, String precheckToken) {
        String promptHash = PrecheckTokenService.promptHash(draftId, taskType,
                objectToString(promptParams == null ? null : promptParams.get("customInstruction")),
                objectToString(promptParams == null ? null : promptParams.get("formatInstruction")));

        if (null == precheckToken || precheckToken.isBlank()) {
            if (enforce) {
                throw new AppException(ResponseCode.PRECHECK_REQUIRED.getCode(), "请先完成写作意图预检");
            }
            log.warn("灰度期：submit 缺失 precheckToken 放行 userId={} draftId={} taskType={}", userId, draftId, taskType);
            return;
        }
        tokenService.verify(userId, draftId, taskType, promptHash, precheckToken);
    }

    /** 消费凭证（submit 成功落库后调用）。 */
    public void consume(String precheckToken) {
        tokenService.consume(precheckToken);
    }

    // ==================== 私有方法 ====================

    private WritingIntentPrecheckResultVO passResult() {
        return WritingIntentPrecheckResultVO.builder()
                .decision(WritingIntentDecisionVO.PASS)
                .intent(WritingIntentTypeVO.WRITE_ARTICLE)
                .reason("规则命中：明确写作任务")
                .suggestedAction("CONTINUE_WRITING")
                .build();
    }

    private WritingIntentPrecheckResultVO blockResult(WritingIntentPrecheckContextVO ctx) {
        return WritingIntentPrecheckResultVO.builder()
                .decision(WritingIntentDecisionVO.BLOCK)
                .intent(WritingIntentTypeVO.UNKNOWN)
                .reason("规则命中：明显非写作请求")
                .suggestedAction(suggestForBlock(ctx))
                .build();
    }

    private WritingIntentPrecheckResultVO confirmRequiredResult(String reason) {
        return WritingIntentPrecheckResultVO.builder()
                .decision(WritingIntentDecisionVO.CONFIRM_REQUIRED)
                .intent(WritingIntentTypeVO.UNKNOWN)
                .reason(reason)
                .suggestedAction("ASK_CONFIRM")
                .build();
    }

    /** 签发凭证并设置 token 元信息。 */
    private WritingIntentPrecheckResultVO withToken(WritingIntentPrecheckResultVO result, Long userId,
                                                    Long draftId, String taskType, String promptHash) {
        if (result.getDecision() == WritingIntentDecisionVO.PASS) {
            result.setPrecheckToken(tokenService.issue(userId, draftId, taskType, promptHash, "PASS"));
            result.setTokenType("PASS");
        } else if (result.getDecision() == WritingIntentDecisionVO.CONFIRM_REQUIRED) {
            result.setPrecheckToken(tokenService.issue(userId, draftId, taskType, promptHash, "CONFIRM"));
            result.setTokenType("CONFIRM");
        }
        result.setTokenExpireSeconds((int) PrecheckTokenService.TOKEN_TTL_SECONDS);
        return result;
    }

    private WritingIntentPrecheckContextVO buildContext(Long userId, DraftEntity draft, String taskType,
                                                        Map<String, Object> promptParams, Boolean enableIllustration) {
        String customInstruction = objectToString(promptParams == null ? null : promptParams.get("customInstruction"));
        String selectedText = objectToString(promptParams == null ? null : promptParams.get("selectedText"));
        String formatInstruction = objectToString(promptParams == null ? null : promptParams.get("formatInstruction"));
        String content = draft.getContentMd();
        return WritingIntentPrecheckContextVO.builder()
                .userId(userId)
                .draftId(draft.getDraftId())
                .taskType(taskType)
                .enableIllustration(enableIllustration)
                .draftTitle(draft.getTitle())
                .draftSummary(draft.getSummary())
                .draftContent(content)
                .draftContentLength(null == content ? 0 : content.length())
                .selectedText(selectedText)
                .selectedTextLength(null == selectedText ? 0 : selectedText.length())
                .customInstruction(customInstruction)
                .formatInstruction(formatInstruction)
                .build();
    }

    private String suggestForBlock(WritingIntentPrecheckContextVO ctx) {
        if (WritingIntentRuleEngine.isDrawRelated(ctx.getCustomInstruction())) {
            return "SWITCH_TO_DRAWIO";
        }
        return "SWITCH_TO_CHAT";
    }

    private String objectToString(Object value) {
        return null == value ? null : String.valueOf(value);
    }

    // ==================== 限流与缓存 ====================

    private void acquirePrecheck(Long userId) {
        RRateLimiter limiter = redissonClient.getRateLimiter(RedisKeyConstants.PRECHECK_RATE_LIMIT_PREFIX + userId);
        limiter.trySetRate(RateType.OVERALL, PRECHECK_RATE, 1, RateIntervalUnit.MINUTES);
        if (!limiter.tryAcquire()) {
            throw new AppException(ResponseCode.PRECHECK_RATE_LIMIT.getCode(), ResponseCode.PRECHECK_RATE_LIMIT.getInfo());
        }
    }

    private boolean tryAcquireModel(Long userId) {
        RRateLimiter limiter = redissonClient.getRateLimiter(RedisKeyConstants.PRECHECK_MODEL_RATE_LIMIT_PREFIX + userId);
        limiter.trySetRate(RateType.OVERALL, MODEL_RATE, 1, RateIntervalUnit.MINUTES);
        return limiter.tryAcquire();
    }

    private String cacheKey(Long userId, Long draftId, String taskType, String promptHash) {
        return userId + ":" + draftId + ":" + taskType + ":" + promptHash;
    }

    private WritingIntentPrecheckResultVO loadCache(String cacheKey) {
        RBucket<String> bucket = redissonClient.getBucket(RedisKeyConstants.PRECHECK_CACHE_PREFIX + cacheKey);
        String raw = bucket.get();
        if (null == raw || raw.isBlank()) {
            return null;
        }
        try {
            JSONObject json = JSON.parseObject(raw);
            return WritingIntentPrecheckResultVO.builder()
                    .decision(WritingIntentDecisionVO.fromCode(json.getString("decision")))
                    .intent(WritingIntentTypeVO.fromCode(json.getString("intent")))
                    .confidence(json.getDouble("confidence"))
                    .reason(json.getString("reason"))
                    .suggestedAction(json.getString("suggestedAction"))
                    .build();
        } catch (Exception e) {
            log.warn("预检缓存解析失败，忽略缓存: {}", e.getMessage());
            return null;
        }
    }

    private void saveCache(String cacheKey, WritingIntentPrecheckResultVO result) {
        JSONObject json = new JSONObject();
        json.put("decision", result.getDecision() == null ? null : result.getDecision().getCode());
        json.put("intent", result.getIntent() == null ? null : result.getIntent().getCode());
        json.put("confidence", result.getConfidence());
        json.put("reason", result.getReason());
        json.put("suggestedAction", result.getSuggestedAction());
        RBucket<String> bucket = redissonClient.getBucket(RedisKeyConstants.PRECHECK_CACHE_PREFIX + cacheKey);
        bucket.set(JSON.toJSONString(json), CACHE_TTL_SECONDS, TimeUnit.SECONDS);
    }
}
