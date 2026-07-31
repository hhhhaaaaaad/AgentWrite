package cn.sutone.ai.domain.agent.adapter.repository;

/**
 * Outbox 即时投递接口
 * <p>
 * 事务提交后立即尝试将 Outbox 事件投递到 MQ，成功则标记 PUBLISHED，
 * 失败则静默忽略，由定时任务（AiTaskOutboxPublisher）兜底补发。
 * </p>
 */
public interface IOutboxImmediatePublisher {

    /**
     * 尝试即时投递指定任务的 Outbox 事件
     *
     * @param taskId 任务 ID（即 outbox_event 的 aggregate_id）
     */
    void tryPublish(Long taskId);
}
