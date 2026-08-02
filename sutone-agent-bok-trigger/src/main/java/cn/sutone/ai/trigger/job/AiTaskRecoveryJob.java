package cn.sutone.ai.trigger.job;

import cn.sutone.ai.domain.agent.adapter.repository.IAiTaskRepository;
import cn.sutone.ai.domain.agent.model.entity.AiTaskEntity;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 补偿 Job: 扫描心跳超时的 RUNNING 任务，委托 AiTaskRecoveryExecutor 执行事务恢复
 */
@Slf4j
@Component
public class AiTaskRecoveryJob {

    private final IAiTaskRepository aiTaskRepository;
    private final AiTaskRecoveryExecutor executor;

    @Value("${ai-writing.recovery.running-timeout-minutes:5}")
    private int timeoutMinutes;

    public AiTaskRecoveryJob(IAiTaskRepository aiTaskRepository,
                             AiTaskRecoveryExecutor executor) {
        this.aiTaskRepository = aiTaskRepository;
        this.executor = executor;
    }

    @Scheduled(fixedDelay = 30_000)
    public void recoverStaleTasks() {
        // 查找心跳超时的任务
        LocalDateTime timeout = LocalDateTime.now().minusMinutes(timeoutMinutes);
        List<AiTaskEntity> staleTasks = aiTaskRepository.findStaleRunning(timeout, 50);
        log.info("补偿扫描: 发现 {} 个心跳超时 RUNNING 任务 (超时阈值 {} 分钟)", staleTasks.size(), timeoutMinutes);

        for (AiTaskEntity task : staleTasks) {
            executor.recoverSingleTask(task);
        }
    }
}
