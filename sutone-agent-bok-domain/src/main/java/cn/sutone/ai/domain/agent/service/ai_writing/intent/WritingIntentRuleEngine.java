package cn.sutone.ai.domain.agent.service.ai_writing.intent;

import cn.sutone.ai.domain.agent.service.ai_writing.intent.model.WritingIntentDecisionVO;
import cn.sutone.ai.domain.agent.service.ai_writing.intent.model.WritingIntentPrecheckContextVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 写作意图规则引擎：只做高确定性规则判断，输出 PASS / BLOCK / UNCERTAIN。
 *
 * <p>按固定顺序短路执行，BLOCK 类规则优先级高于 PASS 类规则（先拦截、再放行）。</p>
 */
@Slf4j
@Component
public class WritingIntentRuleEngine {

    /** 正文充分阈值（字符数） */
    private static final int BODY_SUFFICIENT_LENGTH = 500;

    /** 闲聊关键词（纯闲聊才拦截，混合写作动词时不拦截） */
    private static final List<String> CHAT_KEYWORDS = List.of(
            "你好", "您好", "你是谁", "你能做什么", "在吗", "在不在", "谢谢", "再见", "哈喽", "hello", "hi");

    /** 问答关键词（纯问答才拦截） */
    private static final List<String> QA_KEYWORDS = List.of(
            "解释一下", "什么是", "介绍一下", "为什么", "怎么理解", "原理", "区别", "是什么意思", "给我讲讲");

    /** 画图关键词（与写作互斥，命中即拦截） */
    private static final List<String> DRAW_KEYWORDS = List.of(
            "架构图", "时序图", "流程图", "思维导图", "类图", "用例图", "状态图",
            "画一个", "画个", "画图", "画张", "帮我画", "绘制", "绘图", "生成图", "diagram", "draw.io", "drawio");

    /** 写作动作动词（用于区分"纯闲聊/纯问答"与"闲聊词 + 写作意图"的混合输入） */
    private static final List<String> WRITE_ACTION_KEYWORDS = List.of(
            "润色", "整理", "改写", "续写", "扩写", "缩写", "生成", "撰写", "补充", "调整", "优化", "修改",
            "大纲", "摘要", "标题", "标签", "检查", "质检", "写", "完善", "重写", "翻译");

    /**
     * 规则判断入口。
     */
    public WritingIntentDecisionVO evaluate(WritingIntentPrecheckContextVO ctx) {
        String instruction = normalize(ctx.getCustomInstruction());
        String taskType = ctx.getTaskType();

        // 1. 明显无效输入检测（最高优先）
        if (isInvalidInput(instruction)) {
            return WritingIntentDecisionVO.BLOCK;
        }

        // 2. 明显画图（与写作互斥，命中即拦，优先级最高）
        if (containsAny(instruction, DRAW_KEYWORDS)) {
            return WritingIntentDecisionVO.BLOCK;
        }

        // 3. 纯闲聊 / 纯问答（不含写作动词才算，避免误伤"你好，顺便帮我润色这段"）
        boolean hasWriteAction = containsAny(instruction, WRITE_ACTION_KEYWORDS);
        if (!hasWriteAction && containsAny(instruction, CHAT_KEYWORDS)) {
            return WritingIntentDecisionVO.BLOCK;
        }
        if (!hasWriteAction && containsAny(instruction, QA_KEYWORDS)) {
            return WritingIntentDecisionVO.BLOCK;
        }

        // 4. 局部润色（POLISH_TEXT 且选中文本非空）
        if ("POLISH_TEXT".equals(taskType) && hasText(ctx.getSelectedText())) {
            return WritingIntentDecisionVO.PASS;
        }

        // 5. 明确摘要/标题/标签/质检任务（草稿内容足够）
        if (isDeterministicAuxTask(taskType) && hasText(ctx.getDraftContent())) {
            return WritingIntentDecisionVO.PASS;
        }

        // 6. 生成大纲（正文可空，未命中闲聊/画图/问答即 PASS）
        if ("GENERATE_OUTLINE".equals(taskType)) {
            return WritingIntentDecisionVO.PASS;
        }

        // 7. 正文续写（正文充分且未命中闲聊/画图）
        if ("GENERATE_BODY".equals(taskType)
                && ctx.getDraftContentLength() != null
                && ctx.getDraftContentLength() >= BODY_SUFFICIENT_LENGTH) {
            return WritingIntentDecisionVO.PASS;
        }

        // 8. 其余：进入模型二判
        return WritingIntentDecisionVO.UNCERTAIN;
    }

    /**
     * 判断文本是否包含画图关键词（供 GuardService 生成 BLOCK 建议去向）。
     */
    public static boolean isDrawRelated(String text) {
        return containsAny(normalize(text), DRAW_KEYWORDS);
    }

    private boolean isDeterministicAuxTask(String taskType) {
        return "SUMMARIZE".equals(taskType)
                || "GENERATE_TITLE".equals(taskType)
                || "GENERATE_TAGS".equals(taskType)
                || "QUALITY_CHECK".equals(taskType);
    }

    private boolean isInvalidInput(String instruction) {
        if (null == instruction || instruction.isBlank()) {
            return false;
        }
        // 只有标点/空白
        if (instruction.matches("^[\\p{P}\\p{S}\\s]+$")) {
            return true;
        }
        // 无实质内容的短回复
        return instruction.matches("^(嗯|好的|好|继续|收到|哦|额|对|是的|行|可以)+$");
    }

    private static boolean containsAny(String text, List<String> keywords) {
        if (null == text) {
            return false;
        }
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasText(String text) {
        return null != text && !text.isBlank();
    }

    private static String normalize(String text) {
        if (null == text) {
            return "";
        }
        return text.trim();
    }
}
