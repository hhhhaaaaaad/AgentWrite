package cn.sutone.ai.infrastructure.adapter.repository;

import cn.sutone.ai.domain.agent.adapter.repository.IOutboxEventRepository;
import cn.sutone.ai.domain.agent.model.entity.OutboxEventEntity;
import cn.sutone.ai.domain.agent.model.valobj.OutboxEventVO;
import cn.sutone.ai.infrastructure.dao.IOutboxEventDao;
import cn.sutone.ai.infrastructure.dao.po.OutboxEventPO;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class OutboxEventRepository implements IOutboxEventRepository {

    private final IOutboxEventDao dao;

    public OutboxEventRepository(IOutboxEventDao dao) {
        this.dao = dao;
    }

    @Override
    public void save(OutboxEventEntity event) {
        OutboxEventPO po = toPO(event);
        dao.insert(po);
        // 回填自增主键
        event.setEventId(po.getEventId());
    }

    @Override
    public List<OutboxEventEntity> claimPublishable(int limit) {
        // UPDATE 原子抢占行锁保证多实例安全，无需 @Transactional（两条 SQL 间无需原子回滚）
        // 这样实现的原因，本质是为了解决消息重复发送的问题！
        // 原来尝试使用 @Transactional，但是发现即使在同一个实例快速连续发送大量消息时，
        // 也会出现消息重复发送的问题。原因是在高并发下，多个线程几乎同时调用 publishPendingEvents，
        // 导致多条消息被抢占，从而引发重复发送。
        String publisherId = UUID.randomUUID().toString();
        // 将可发布的任务（恢复的和新建的任务）状态批量设置为 SENDING
        dao.claimPublishableBatch(publisherId, limit);

        // Step 2: 根据 publisher_id 回查已抢占的事件
        return dao.findClaimedByPublisherId(publisherId).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public void markPublished(Long eventId) {
        dao.markPublished(eventId);
    }

    @Override
    public void scheduleRetry(Long eventId, int retryCount, String errorMsg) {
        // Issue 4: 指数退避 min(10 * 2^retry, 600) 秒
        long delaySeconds = Math.min(10L * (1L << Math.min(retryCount, 6)), 600L);
        dao.scheduleRetry(eventId, LocalDateTime.now().plusSeconds(delaySeconds), errorMsg);
    }

    @Override
    public void markFailed(Long eventId, String errorMsg) {
        dao.markFailed(eventId, errorMsg);
    }

    @Override
    public void updatePayload(Long eventId, String payload) {
        dao.updatePayload(eventId, payload);
    }

    @Override
    public boolean hasPendingEventForAggregate(String aggregateId) {
        return dao.countPendingByAggregateId(aggregateId) > 0;
    }

    @Override
    public int recoverStaleSending(int timeoutMinutes) {
        return dao.recoverStaleSending(LocalDateTime.now().minusMinutes(timeoutMinutes));
    }

    @Override
    public int countPublishableEvents() {
        return dao.countPublishableEvents();
    }

    @Override
    public OutboxEventEntity findLatestByAggregateId(Long aggregateId) {
        OutboxEventPO po = dao.findLatestByAggregateId(aggregateId);
        return toEntity(po);
    }

    private OutboxEventPO toPO(OutboxEventEntity entity) {
        return OutboxEventPO.builder()
                .eventId(entity.getEventId())
                .eventType(entity.getEventType())
                .aggregateId(entity.getAggregateId())
                .topic(entity.getTopic())
                .payload(entity.getPayload())
                .status(entity.getStatus().getCode())
                .retryCount(entity.getRetryCount())
                .nextRetryAt(entity.getNextRetryAt())
                .publishedAt(entity.getPublishedAt())
                .lastError(entity.getLastError())
                .publisherId(entity.getPublisherId())
                .createTime(entity.getCreateTime())
                .updateTime(entity.getUpdateTime())
                .build();
    }

    private OutboxEventEntity toEntity(OutboxEventPO po) {
        if (null == po) return null;
        return OutboxEventEntity.builder()
                .eventId(po.getEventId())
                .eventType(po.getEventType())
                .aggregateId(po.getAggregateId())
                .topic(po.getTopic())
                .payload(po.getPayload())
                .status(po.getStatus() != null ? OutboxEventVO.valueOf(po.getStatus()) : null)
                .retryCount(po.getRetryCount())
                .nextRetryAt(po.getNextRetryAt())
                .publishedAt(po.getPublishedAt())
                .lastError(po.getLastError())
                .publisherId(po.getPublisherId())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .build();
    }
}
