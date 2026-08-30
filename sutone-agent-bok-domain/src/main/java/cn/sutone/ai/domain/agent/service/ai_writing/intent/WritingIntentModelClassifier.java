package cn.sutone.ai.domain.agent.service.ai_writing.intent;

import cn.sutone.ai.domain.agent.service.IChatService;
import cn.sutone.ai.domain.agent.service.ai_writing.intent.model.WritingIntentDecisionVO;
import cn.sutone.ai.domain.agent.service.ai_writing.intent.model.WritingIntentPrecheckContextVO;
import cn.sutone.ai.domain.agent.service.ai_writing.intent.model.WritingIntentPrecheckResultVO;
import cn.sutone.ai.domain.agent.service.ai_writing.intent.model.WritingIntentTypeVO;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 写作意图模型二判。
 *
 * <p>规则层无法确定的边界请求，交给轻量意图分类 Agent（agentId 通过配置指定，需在 agent 配置表注册）
 * 二次判定。复用 IChatService.handleMessage，输出单行 JSON 协议。</p>
 */
@Slf4j
@Component
public class WritingIntentModelClassifier {

    /** 超时阈值：轻量分类应足够快 */
    private static final long TIMEOUT_MS = 3000;

    /** 高置信度阈值 */
    private static final double HIGH_CONFIDENCE = 0.80;

    private final IChatService chatService;

    /** 轻量意图分类 Agent ID，需在 agent 配置表注册 */
    @Value("${ai-writing.guard.classifier-agent-id:300006}")
    private String intentClassifierAgentId;

    public WritingIntentModelClassifier(IChatService chatService) {
        this.chatService = chatService;
    }

    /**
     * 模型二判入口：超时、异常、解析失败均降级为 CONFIRM_REQUIRED。
     */
    public WritingIntentPrecheckResultVO classify(WritingIntentPrecheckContextVO ctx) {
        String prompt = buildClassifyPrompt(ctx);
        String userId = String.valueOf(ctx.getUserId());
        try {
            String sessionId = chatService.createSession(intentClassifierAgentId, userId, false);
            CompletableFuture<List<String>> future = CompletableFuture.supplyAsync(
                    () -> chatService.handleMessage(intentClassifierAgentId, userId, sessionId, prompt));
            List<String> outputs = future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
            JSONObject json = parseFirstJson(outputs);
            if (null == json) {
                log.warn("模型二判输出解析失败，降级 CONFIRM_REQUIRED taskType={}", ctx.getTaskType());
                return confirmRequired("模型输出解析失败");
            }
            return toDecision(json);
        } catch (TimeoutException e) {
            log.warn("模型二判超时，降级 CONFIRM_REQUIRED taskType={}", ctx.getTaskType());
            return confirmRequired("模型二判超时");
        } catch (Exception e) {
            log.warn("模型二判异常，降级 CONFIRM_REQUIRED taskType={}: {}", ctx.getTaskType(), e.getMessage());
            return confirmRequired("模型二判异常");
        }
    }

    private JSONObject parseFirstJson(List<String> outputs) {
        if (null == outputs || outputs.isEmpty()) {
            return null;
        }
        for (String line : outputs) {
            if (null == line || line.isBlank()) {
                continue;
            }
            try {
                return JSON.parseObject(line.trim());
            } catch (Exception ignored) {
                // 尝试下一行
            }
        }
        return null;
    }

    private WritingIntentPrecheckResultVO toDecision(JSONObject json) {
        String intentCode = json.getString("intent");
        Double confidence = json.getDouble("confidence");
        String reason = json.getString("reason");
        String suggestion = json.getString("suggestion");
        WritingIntentTypeVO intent = WritingIntentTypeVO.fromCode(intentCode);

        WritingIntentDecisionVO decision;
        if (intent == WritingIntentTypeVO.WRITE_ARTICLE && null != confidence && confidence >= HIGH_CONFIDENCE) {
            decision = WritingIntentDecisionVO.PASS;
        } else if ((intent == WritingIntentTypeVO.CHAT || intent == WritingIntentTypeVO.DRAW_DIAGRAM)
                && null != confidence && confidence >= HIGH_CONFIDENCE) {
            decision = WritingIntentDecisionVO.BLOCK;
        } else {
            decision = WritingIntentDecisionVO.CONFIRM_REQUIRED;
        }

        // BLOCK 时兜底建议去向，避免 suggestion 为空导致前端无法分流
        if (decision == WritingIntentDecisionVO.BLOCK && (null == suggestion || suggestion.isBlank())) {
            suggestion = intent == WritingIntentTypeVO.DRAW_DIAGRAM ? "SWITCH_TO_DRAWIO" : "SWITCH_TO_CHAT";
        }

        return WritingIntentPrecheckResultVO.builder()
                .decision(decision)
                .intent(intent)
                .confidence(confidence)
                .reason(reason)
                .suggestedAction(suggestion)
                .build();
    }

    private WritingIntentPrecheckResultVO confirmRequired(String reason) {
        return WritingIntentPrecheckResultVO.builder()
                .decision(WritingIntentDecisionVO.CONFIRM_REQUIRED)
                .intent(WritingIntentTypeVO.UNKNOWN)
                .reason(reason)
                .suggestedAction("ASK_CONFIRM")
                .build();
    }

    private String buildClassifyPrompt(WritingIntentPrecheckContextVO ctx) {
        return """
                你是一个写作意图分类器，判断用户在当前写作面板中的请求意图。
                你必须且只能输出一行 JSON，格式：
                {"intent":"WRITE_ARTICLE|CHAT|DRAW_DIAGRAM|UNKNOWN","confidence":0.85,"reason":"...","suggestion":"CONTINUE_WRITING|SWITCH_TO_CHAT|SWITCH_TO_DRAWIO|ASK_CONFIRM"}
                严禁输出 JSON 以外的任何内容。

                当前场景：QUICK_WRITING_PANEL
                任务类型：%s
                草稿标题：%s
                草稿摘要：%s
                草稿正文字符数：%d
                是否有选中文本：%s
                用户指令：%s
                """.formatted(
                nullToEmpty(ctx.getTaskType()),
                nullToEmpty(ctx.getDraftTitle()),
                nullToEmpty(ctx.getDraftSummary()),
                null == ctx.getDraftContentLength() ? 0 : ctx.getDraftContentLength(),
                hasText(ctx.getSelectedText()) ? "是" : "否",
                nullToEmpty(ctx.getCustomInstruction()));
    }

    private static String nullToEmpty(String value) {
        return null == value ? "" : value;
    }

    private static boolean hasText(String value) {
        return null != value && !value.isBlank();
    }
}
