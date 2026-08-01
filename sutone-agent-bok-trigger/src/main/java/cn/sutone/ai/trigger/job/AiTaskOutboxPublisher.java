package cn.sutone.ai.trigger.job;

import cn.sutone.ai.domain.agent.adapter.repository.IOutboxEventRepository;
import cn.sutone.ai.infrastructure.metrics.MqMetrics;
import cn.sutone.ai.domain.agent.model.entity.OutboxEventEntity;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Outbox 定时投递器（兜底机制）
 *
 * <p>职责：每 2 秒扫描 outbox_event 表中状态为 NEW/RETRYING 的事件，投递到 RocketMQ。
 * 即使即时投递（OutboxImmediatePublisher）失败，此定时任务也能保证消息最终送达。</p>
 *
 * <p>状态流转：NEW → SENDING → PUBLISHED（成功）/ RETRYING（失败可重试）/ FAILED（超过最大重试次数）</p>
 *
 * <p>重试策略：指数退避 min(10 * 2^retry, 600) 秒，最多重试 5 次后标记 FAILED。</p>
 */
@Slf4j
@Component
public class AiTaskOutboxPublisher {

    private final IOutboxEventRepository outboxEventRepository;
    private final RocketMQTemplate rocketMQTemplate;
    private final MqMetrics mqMetrics;

    @Value("${ai-writing.outbox.batch-size:100}")
    private int batchSize;

    @Value("${ai-writing.outbox.max-retry-count:5}")
    private int maxRetry;

    @Value("${rocketmq.producer.send-message-timeout:10000}")
    private int sendTimeout;

    @Value("${ai-writing.outbox.sending-timeout-minutes:5}")
    private int sendingTimeoutMinutes;

    public AiTaskOutboxPublisher(IOutboxEventRepository outboxEventRepository,
                                  RocketMQTemplate rocketMQTemplate,
                                  MqMetrics mqMetrics) {
        this.outboxEventRepository = outboxEventRepository;
        this.rocketMQTemplate = rocketMQTemplate;
        this.mqMetrics = mqMetrics;
    }

    /**
     * 定时投递主逻辑（每 2 秒执行一次）
     *
     * <p>流程：
     * 1. 原子抢占：批量将 status=NEW/RETRYING 的事件 CAS 改为 SENDING（防多实例重复投递）
     * 2. 逐条投递：调用 rocketMQTemplate.syncSend() 发送到 MQ
     * 3. 成功 → markPublished；失败 → 判断是否超过最大重试次数，决定 scheduleRetry 或 markFailed
     * </p>
     */
    @Scheduled(fixedDelayString = "${ai-writing.outbox.publish-delay-ms:2000}")
    public void publishPendingEvents() {
        // Step 1: 原子抢占待投递事件，先 update 本实例下的状态，再查本实例下刚刚update 的 批次 id 的待投递事件（SELECT WHERE status=SENDING）
        // 防止在多实例的场景下，同一个事件被多个实例重复投递。
        List<OutboxEventEntity> events = outboxEventRepository.claimPublishable(batchSize);

        for (OutboxEventEntity event : events) {
            try {
                // Step 2: 真正发送到 RocketMQ（同步发送，确认 Broker 收到才返回）
                Object payloadObj = JSON.parse(event.getPayload());
                SendResult result = rocketMQTemplate.syncSend(event.getTopic(), payloadObj, sendTimeout);
                // Step 3a: 发送成功 → 标记为 PUBLISHED，后续不会再被扫描到
                outboxEventRepository.markPublished(event.getEventId());
                mqMetrics.incrementPublished();
                log.info("Outbox 投递成功 eventId={} taskId={} msgId={}",
                        event.getEventId(), event.getAggregateId(), result.getMsgId());
            } catch (Exception e) {
                // Step 3b: 发送失败 → 判断是否超过最大重试次数
                mqMetrics.incrementFailed();
                log.error("Outbox 投递失败 eventId={} taskId={} retry={}/{}",
                        event.getEventId(), event.getAggregateId(), event.getRetryCount(), maxRetry, e);
                int retryCount = event.getRetryCount() != null ? event.getRetryCount() : 0;
                if (retryCount >= maxRetry) {
                    // 超过最大重试次数 → 标记 FAILED，不再重试（需人工介入）
                    outboxEventRepository.markFailed(event.getEventId(),
                            "超过最大重试次数: " + safeMsg(e));
                } else {
                    // 状态设为恢复中，并且使用指数退避重试策略，之后再定时任务重试
                    // 未超过 → 指数退避重试（10s → 20s → 40s → 80s → 160s → 320s → 最大 600s）
                    outboxEventRepository.scheduleRetry(event.getEventId(), retryCount, safeMsg(e));
                }
            }
        }
    }

    /**
     * 补偿任务：恢复超时的 SENDING 事件（每 60 秒执行一次）
     *
     * <p>场景：Publisher 实例在 claimPublishable 后崩溃，事件卡在 SENDING 状态。
     * 此任务将超时（默认 5 分钟）的 SENDING 事件回退为 RETRYING，让下次轮询重新投递。</p>
     */
    @Scheduled(fixedDelay = 60_000)
    public void recoverStaleSending() {
        int recovered = outboxEventRepository.recoverStaleSending(sendingTimeoutMinutes);
        if (recovered > 0) {
            log.warn("恢复 {} 个超时 SENDING 事件 (>{} 分钟)", recovered, sendingTimeoutMinutes);
        }
    }

    private String safeMsg(Exception e) {
        String msg = e.getMessage();
        if (null == msg || msg.isBlank()) return e.getClass().getSimpleName();
        return msg.length() > 1000 ? msg.substring(0, 1000) : msg;
    }
}
