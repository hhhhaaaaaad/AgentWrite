package cn.sutone.ai.domain.agent.model.exception;

/**
 * 向量存储写路径失败异常。
 * Qdrant 等向量库 upsert/delete 失败时抛出，由上层决定是否标记 PENDING 并进入补偿。
 */
public class MemoryVectorStoreException extends RuntimeException {

    public MemoryVectorStoreException(String message) {
        super(message);
    }

    public MemoryVectorStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
