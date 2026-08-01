package cn.sutone.ai.trigger.job;

import cn.sutone.ai.domain.agent.adapter.repository.IOutboxEventRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IOutboxImmediatePublisher;
import cn.sutone.ai.domain.agent.model.entity.OutboxEventEntity;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Outbox 即时投递实现
 * <p>
 * 事务提交后立即尝试投递到 RocketMQ，成功则标记 PUBLISHED，
 * 失败则静默忽略，由 {@link AiTaskOutboxPublisher} 定时兜底。
 * </p>
 */
@Slf4j
@Component
public class OutboxImmediatePublisher implements IOutboxImmediatePublisher {

    private final IOutboxEventRepository outboxEventRepository;
    private final RocketMQTemplate rocketMQTemplate;

    @Value("${rocketmq.producer.send-message-timeout:10000}")
    private int sendTimeout;

    public OutboxImmediatePublisher(IOutboxEventRepository outboxEventRepository,
                                    RocketMQTemplate rocketMQTemplate) {
        this.outboxEventRepository = outboxEventRepository;
        this.rocketMQTemplate = rocketMQTemplate;
    }

    @Override
    public void tryPublish(Long taskId) {
        try {
            // 查询该任务最新的 outbox 事件（刚刚在事务中写入的）
            OutboxEventEntity event = outboxEventRepository.findLatestByAggregateId(taskId);
            if (event == null) {
                log.debug("即时投递跳过: 未找到 outbox 事件 taskId={}", taskId);
                return;
            }
            // 只投递 NEW 状态的事件，避免与定时任务冲突
            if (!"NEW".equals(event.getStatus().getCode())) {
                log.debug("即时投递跳过: 事件状态非 NEW taskId={} status={}", taskId, event.getStatus());
                return;
            }

            Object payloadObj = JSON.parse(event.getPayload());
            // mq 如果发送不成功，不会抛异常，所以发送失败会直接跳过，等待定时兜底
            // mq 发送失败，直接跳到 catch 中
            SendResult result = rocketMQTemplate.syncSend(event.getTopic(), payloadObj, sendTimeout);
            // ！会出现一种极端情况：mq发成功了，但是由于 网络抖动/DB 宕机 导致 数据库 没更新成功，
            // 没关系：会有定时任务兜底，虽然消息重复投递，但是Consumer 做了幂等兜底
            outboxEventRepository.markPublished(event.getEventId());
            log.info("即时投递成功 taskId={} eventId={} msgId={}", taskId, event.getEventId(), result.getMsgId());
        } catch (Exception e) {
            log.warn("即时投递失败 taskId={}, 等待定时兜底: {}", taskId, e.getMessage());
            // 不抛异常，不影响 submitTask 的返回
        }
    }
}
