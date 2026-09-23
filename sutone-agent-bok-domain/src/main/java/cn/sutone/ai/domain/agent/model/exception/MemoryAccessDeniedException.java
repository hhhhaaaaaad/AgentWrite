package cn.sutone.ai.domain.agent.model.exception;

/**
 * 记忆归属校验失败异常（IDOR 防护）。
 * 当请求用户与记忆归属用户不一致时抛出，供 MemoryManager 带 userId 的删除/查询重载使用。
 */
public class MemoryAccessDeniedException extends RuntimeException {

    public MemoryAccessDeniedException(String message) {
        super(message);
    }

    public MemoryAccessDeniedException(String message, Throwable cause) {
        super(message, cause);
    }
}
