package cn.sutone.ai.trigger.listener;

import cn.sutone.ai.domain.agent.adapter.repository.IAiTaskRepository;
import cn.sutone.ai.domain.agent.service.IAiWritingService;
import cn.sutone.ai.domain.agent.service.ai_writing.RetryableAgentException;
import cn.sutone.ai.infrastructure.metrics.MqMetrics;
import cn.sutone.ai.types.dto.AiTaskMessage;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * AI 写作任务 Consumer：接收 RocketMQ 消息，原子抢占并执行 Agent 编排
 *
 * <p>消费流程：
 * 1. 收到 MQ 消息（包含 taskId）
 * 2. CAS 抢占任务：UPDATE ai_task SET status='RUNNING' WHERE id=? AND status='PENDING'
 *    - affectedRows=0 说明已被其他 Consumer 实例抢占，直接跳过（幂等保证）
 *    - affectedRows=1 抢占成功，开始执行
 * 3. 调用 aiWritingService.executeTask() 执行 Agent 编排（analyst→generator→reviewer→配图）
 * 4. 异常处理：
 *    - RetryableAgentException → 抛出，RocketMQ 自动重试（最多 3 次）
 *    - 其他异常 → executeTask 内部已标记 FAILED，此处正常 ACK 不重试
 * </p>
 *
 * <p>幂等保证：即使同一条消息被投递多次（即时投递 + 定时兜底可能重复），
 * claimTask 的 CAS 机制保证只有一个 Consumer 能成功执行。</p>
 */
@Slf4j
@Component
@RocketMQMessageListener(
        topic = "${ai-writing.mq.topic:ai-writing-task}",
        consumerGroup = "${ai-writing.mq.consumer-group:ai-writing-worker-group}",
        maxReconsumeTimes = 3  // RocketMQ 层面最多重试 3 次，超过进入死信队列
)
public class AiTaskConsumer implements RocketMQListener<AiTaskMessage> {

    private final IAiTaskRepository aiTaskRepository;
    private final IAiWritingService aiWritingService;
    private final MqMetrics mqMetrics;

    public AiTaskConsumer(IAiTaskRepository aiTaskRepository, IAiWritingService aiWritingService,
                          MqMetrics mqMetrics) {
        this.aiTaskRepository = aiTaskRepository;
        this.aiWritingService = aiWritingService;
        this.mqMetrics = mqMetrics;
    }

    @Override
    public void onMessage(AiTaskMessage message) {
        Long taskId = message.getTaskId();
        log.info("Consumer 收到任务 taskId={} eventId={}", taskId, message.getEventId());

        // Step 1: CAS 原子抢占（UPDATE ... WHERE status='PENDING'）
        // 多实例部署时，同一条消息可能被多个 Consumer 收到，只有一个能 claim 成功
        // workId 标记谁在进行工作，还有方便后续的故障恢复
        int affectedRows = aiTaskRepository.claimTask(taskId, getWorkerId());
        if (affectedRows == 0) {
            // 已被其他实例抢占，或任务状态已不是 PENDING（重复消息），直接跳过
            log.info("任务已被抢占或不可执行 taskId={}", taskId);
            return;
        }

        log.info("抢占成功，开始执行 taskId={}", taskId);
        Timer.Sample sample = mqMetrics.startTimer();
        try {
            // Step 2: 执行 Agent 编排（内部调用 AgentWritingRunner.run()）
            aiWritingService.executeTask(taskId);
        } catch (RetryableAgentException e) {
            // 可重试异常（如模型限流、网络超时）→ 抛出让 RocketMQ 自动重试
            log.warn("可重试异常，触发 RocketMQ 重试 taskId={}: {}", taskId, e.getMessage());
            throw e;
        } catch (Exception e) {
            // 不可恢复异常 → executeTask 内部已将任务标记为 FAILED 并推送错误事件给前端
            // 此处正常返回（ACK），不触发 MQ 重试
            log.error("Task execution failed taskId={}", taskId, e);
        } finally {
            mqMetrics.stopTimer(sample);
        }
    }

    private String getWorkerId() {
        String host = System.getenv().getOrDefault("HOSTNAME", "unknown");
        return host + "-" + Thread.currentThread().getName();
    }
}
