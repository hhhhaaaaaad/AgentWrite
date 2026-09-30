package cn.sutone.ai.domain.agent.model.exception;

/**
 * 评测 fencing 校验失败：run_id 不匹配、版本不符或无 fencing 行。
 *
 * <p>破坏性写（reset / seed / finalize 清理）的前置守卫失败时抛出，由 trigger 层映射为 403。</p>
 */
public class MemoryEvalFencingException extends RuntimeException {

    private static final long serialVersionUID = 8123746510928374651L;

    public MemoryEvalFencingException(String message) {
        super(message);
    }
}
