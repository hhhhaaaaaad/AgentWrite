package cn.sutone.ai.domain.agent.service.ai_writing.intent;

import cn.sutone.ai.types.common.RedisKeyConstants;
import cn.sutone.ai.types.enums.ResponseCode;
import cn.sutone.ai.types.exception.AppException;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 预检凭证服务：签发 / 校验 / 消费 precheckToken。
 *
 * <p>使用 Redis 存储，适配多实例部署。token 采用 consume-once 语义，
 * 但由 submit 成功落库后显式调用 {@link #consume(String)} 删除；
 * 网络重试导致的重复提交由现有 AI_TASK_LOCK 分布式锁兜底，与本 token 职责分离。</p>
 */
@Slf4j
@Service
public class PrecheckTokenService {

    /** 默认有效期（秒），120~300 区间内 */
    public static final long TOKEN_TTL_SECONDS = 180;

    private final RedissonClient redissonClient;

    public PrecheckTokenService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 签发预检凭证。
     *
     * @param tokenType PASS | CONFIRM
     */
    public String issue(Long userId, Long draftId, String taskType, String promptHash, String tokenType) {
        String token = UUID.randomUUID().toString().replace("-", "");
        JSONObject payload = new JSONObject();
        payload.put("userId", userId);
        payload.put("draftId", draftId);
        payload.put("taskType", taskType);
        payload.put("promptHash", promptHash);
        payload.put("tokenType", tokenType);
        payload.put("issuedAt", System.currentTimeMillis());
        payload.put("expireAt", System.currentTimeMillis() + TOKEN_TTL_SECONDS * 1000);

        RBucket<String> bucket = redissonClient.getBucket(RedisKeyConstants.PRECHECK_TOKEN_PREFIX + token);
        bucket.set(JSON.toJSONString(payload), TOKEN_TTL_SECONDS, TimeUnit.SECONDS);
        return token;
    }

    /**
     * 校验凭证：存在且 userId/draftId/taskType/promptHash 一致且未过期。
     *
     * @return tokenType（PASS | CONFIRM）
     */
    public String verify(Long userId, Long draftId, String taskType, String promptHash, String token) {
        if (null == token || token.isBlank()) {
            throw new AppException(ResponseCode.PRECHECK_REQUIRED.getCode(), "请先完成写作意图预检");
        }
        RBucket<String> bucket = redissonClient.getBucket(RedisKeyConstants.PRECHECK_TOKEN_PREFIX + token);
        String raw = bucket.get();
        if (null == raw || raw.isBlank()) {
            throw new AppException(ResponseCode.PRECHECK_TOKEN_INVALID.getCode(), "确认信息已过期或不匹配，请重新提交");
        }
        JSONObject payload = JSON.parseObject(raw);
        Long payloadUserId = payload.getLong("userId");
        Long payloadDraftId = payload.getLong("draftId");
        String payloadTaskType = payload.getString("taskType");
        String payloadPromptHash = payload.getString("promptHash");

        if (!Objects.equals(userId, payloadUserId)
                || !Objects.equals(draftId, payloadDraftId)
                || !Objects.equals(taskType, payloadTaskType)
                || !Objects.equals(promptHash, payloadPromptHash)) {
            throw new AppException(ResponseCode.PRECHECK_TOKEN_INVALID.getCode(), "确认信息已过期或不匹配，请重新提交");
        }
        return payload.getString("tokenType");
    }

    /** 消费（删除）凭证，submit 成功落库后调用。 */
    public void consume(String token) {
        if (null != token && !token.isBlank()) {
            redissonClient.getBucket(RedisKeyConstants.PRECHECK_TOKEN_PREFIX + token).delete();
        }
    }

    /**
     * 计算 promptHash：只 hash 稳定、可感知输入，
     * 不纳入 selectedText（实时选区）与 draftContent（正文可能被自动保存）。
     */
    public static String promptHash(Long draftId, String taskType, String customInstruction, String formatInstruction) {
        String raw = (null == draftId ? "" : draftId) + "|"
                + (null == taskType ? "" : taskType) + "|"
                + nullToEmpty(customInstruction) + "|"
                + nullToEmpty(formatInstruction);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("promptHash 计算降级为 hashCode: {}", e.getMessage());
            return Integer.toHexString(raw.hashCode());
        }
    }

    private static String nullToEmpty(String value) {
        return null == value ? "" : value;
    }
}
