package cn.sutone.ai.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.properties.MemoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 记忆访问统计服务（P2-6）。
 *
 * <p>独立 Spring bean，把检索命中后的 access_count / last_accessed_at / 动态重要性更新
 * 移出 {@link MemoryRetriever} 主链路并异步化，避免 {@code @Async} 自调用失效（
 * {@code MemoryRetriever} 内部 {@code this.updateAccessAsync()} 会绕过 Spring AOP 代理）。</p>
 *
 * <p>调用方在检索返回后 {@code recordAccessAsync(hitIds)}，本服务在 {@code memoryExecutor}
 * 线程池异步执行，不阻塞检索主链路。</p>
 */
@Slf4j
@Service
public class MemoryAccessService {

    @Resource
    private IMemoryRepository memoryRepository;

    @Resource
    private MemoryProperties memoryProperties;

    /**
     * 异步更新命中记忆的 access_count、last_accessed_at 与动态重要性。
     *
     * @param hitIds 检索命中的记忆 id 列表
     */
    @Async("memoryExecutor")
    public void recordAccessAsync(List<Long> hitIds) {
        try {
            if (hitIds == null || hitIds.isEmpty()) {
                return;
            }
            // 单条 SQL 批量自增 access_count + 刷新 last_accessed_at
            memoryRepository.batchUpdateAccessInfo(hitIds);
            for (Long id : hitIds) {
                updateImportance(id);
            }
        } catch (Exception e) {
            log.warn("更新记忆 access_info 失败: {}", e.getMessage());
        }
    }

    /** 动态重要性评分（非关键路径，忽略异常） */
    private void updateImportance(Long memoryId) {
        try {
            MemoryRecordEntity record = memoryRepository.queryById(memoryId);
            if (record == null) return;
            MemoryProperties.Importance config = memoryProperties.getImportance();
            double freqScore = Math.log(record.getAccessCount() + 1 + 1) / Math.log(100);
            double recencyScore;
            if (record.getLastAccessedAt() != null) {
                long days = ChronoUnit.DAYS.between(record.getLastAccessedAt(), LocalDateTime.now());
                recencyScore = Math.exp(-days / 30.0);
            } else {
                recencyScore = 0;
            }
            double importance = config.getBaseWeight() * 0.5
                    + config.getFrequencyWeight() * freqScore
                    + config.getRecencyWeight() * recencyScore;
            importance = Math.max(config.getMin(), Math.min(config.getMax(), importance));
            memoryRepository.updateImportance(memoryId, importance);
        } catch (Exception e) {
            log.debug("动态重要性更新跳过 id={}: {}", memoryId, e.getMessage());
        }
    }
}
