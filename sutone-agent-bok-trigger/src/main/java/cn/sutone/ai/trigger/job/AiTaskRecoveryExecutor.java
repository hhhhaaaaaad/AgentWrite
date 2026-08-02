package cn.sutone.ai.trigger.job;

import cn.sutone.ai.domain.agent.adapter.repository.IAiTaskRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IOutboxEventRepository;
import cn.sutone.ai.domain.agent.model.entity.AiTaskEntity;
import cn.sutone.ai.domain.agent.model.entity.OutboxEventEntity;
import cn.sutone.ai.types.dto.AiTaskMessage;
import com.alibaba.fastjson.JSON;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 任务恢复执行器 — 独立 Component 确保 @Transactional 通过 AOP 代理生效
 */
@Slf4j
@Component
public class AiTaskRecoveryExecutor {

    private final IAiTaskRepository aiTaskRepository;
    private final IOutboxEventRepository outboxEventRepository;

    @Value("${ai-writing.recovery.max-retry-count:3}")
    private int maxRetry;

    @Value("${ai-writing.mq.topic:ai-writing-task}")
    private String mqTopic;

    public AiTaskRecoveryExecutor(IAiTaskRepository aiTaskRepository,
                                  IOutboxEventRepository outboxEventRepository) {
        this.aiTaskRepository = aiTaskRepository;
        this.outboxEventRepository = outboxEventRepository;
    }

    @Transactional
    public void recoverSingleTask(AiTaskEntity task) {
        int currentRetry = null == task.getRetryCount() ? 0 : task.getRetryCount();
        if (currentRetry >= maxRetry) {
            aiTaskRepository.markFailed(task.getTaskId(), "超时重试次数超限");
            log.warn("任务重试次数超限 taskId={} retry={}", task.getTaskId(), currentRetry);
            return;
        }

        // Issue 6: 检查是否已有未投递事件，避免重复创建
        String aggregateId = String.valueOf(task.getTaskId());
        if (outboxEventRepository.hasPendingEventForAggregate(aggregateId)) {
            log.info("任务已有未投递 Outbox 事件，跳过创建 taskId={}", task.getTaskId());
            return;
        }
        // 标记为重试中，并创建 Outbox 事件
        aiTaskRepository.markRetryingImmediate(task.getTaskId(), "Worker 心跳超时");
        OutboxEventEntity retryEvent = OutboxEventEntity.newEvent(
                task.getTaskId(), "AI_WRITING_TASK_CREATED",
                mqTopic, "{}");
        outboxEventRepository.save(retryEvent);
        AiTaskMessage message = AiTaskMessage.builder()
                .taskId(task.getTaskId())
                .eventId(retryEvent.getEventId())
                .createdAt(LocalDateTime.now().toString())
                .build();
        outboxEventRepository.updatePayload(retryEvent.getEventId(), JSON.toJSONString(message));
        log.info("补偿重试 taskId={} eventId={} retry={}/{}", task.getTaskId(), retryEvent.getEventId(), currentRetry + 1, maxRetry);
    }
}
