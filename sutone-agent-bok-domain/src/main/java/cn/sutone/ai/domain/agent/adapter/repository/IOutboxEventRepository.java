package cn.sutone.ai.domain.agent.adapter.repository;

import cn.sutone.ai.domain.agent.model.entity.OutboxEventEntity;

import java.util.List;

/**
 * Outbox 事件仓储接口
 */
public interface IOutboxEventRepository {

    void save(OutboxEventEntity event);

    List<OutboxEventEntity> claimPublishable(int limit);

    void markPublished(Long eventId);

    /**
     * @param retryCount 当前重试次数，用于计算指数退避延迟
     */
    void scheduleRetry(Long eventId, int retryCount, String errorMsg);

    void markFailed(Long eventId, String errorMsg);

    /**
     * 更新事件 payload（用于在拿到真实 eventId 后回填消息体）
     */
    void updatePayload(Long eventId, String payload);

    /**
     * 检查指定 aggregateId 是否已有未投递事件（NEW/RETRYING/SENDING）
     */
    boolean hasPendingEventForAggregate(String aggregateId);

    /**
     * 恢复超时的 SENDING 事件（被崩溃的 Publisher 遗留），返回恢复数量
     */
    int recoverStaleSending(int timeoutMinutes);

    /**
     * 待投递事件数量（用于监控指标）
     */
    int countPublishableEvents();

    /**
     * 查询指定 aggregateId 的最新一条事件（用于即时投递）
     */
    OutboxEventEntity findLatestByAggregateId(Long aggregateId);
}
