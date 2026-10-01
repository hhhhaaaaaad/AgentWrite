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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 *
 * <h3>为什么评测实例必须关掉它</h3>
 *
 * <p>评测实例（{@code memory.eval.enabled=true}）跑在专用库 {@code sutone_agent_bok_eval} 上，
 * 与业务库物理隔离。但 RocketMQ 的消费组**没有按 profile 区分**：
 * {@code ai-writing.mq.consumer-group} 默认是 {@code ai-writing-worker-group}，
 * 评测 profile 并未覆盖它。于是业务实例与评测实例若同时启动，两者会**加入同一个消费组**，
 * RocketMQ 会把业务写作消息**负载均衡**地投给其中之一。</p>
 *
 * <p>后果不是「重复执行」，而更隐蔽——消息被投到评测实例时：</p>
 * <ol>
 *   <li>评测实例调 {@link IAiTaskRepository#claimTask} **抢占成功**（CAS 在评测库里改状态）；</li>
 *   <li>评测实例执行完整写作编排，产物写进**评测库**，配图/模型调用也都真实发生；</li>
 *   <li>业务实例再收到同一条消息时 {@code affectedRows=0}，按上面的幂等约定
 *       **判定为「已被其他 Consumer 实例抢占，直接跳过」**。</li>
 * </ol>
 *
 * <p>净效果：这条业务任务**在错误的数据库里被执行**，而业务实例认为它已被处理；
 * 且日志里两边的说法都「正常」。这与评测平台本身在别处反复警惕的
 * **静默降级**是同一形态——最危险的地方在于它看起来什么都没坏。</p>
 *
 * <p>关掉它的方式沿用本仓库既有的「评测实例标记」约定
 * （{@code MemoryVectorSyncJob}、{@code MemoryGovernanceJob} 用的是同一个注解与属性）：
 * {@code matchIfMissing=true} 保证**未显式配置时默认开启**——即业务环境不受影响，
 * 只有显式声明 {@code memory.eval.enabled=true} 的评测实例才会关闭它。</p>
 *
 * <p>注意属性名 {@code memory.eval.*} 名义上属于「记忆」子系统，被用来关一个「写作」
 * 消费者是**语义上的拉伸**。之所以仍这么写，是因为该属性在本仓库里实际承担的角色是
 * 「本实例是不是评测实例」，已有三处这么用；另起一个新属性会让两套约定并存，
 * 反而更容易漏掉某一处。若将来要正名，应当三处一起改成一个中性的名字。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "memory.eval.enabled", havingValue = "false", matchIfMissing = true)
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
        // 将状态设置为 running 进行抢占
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
