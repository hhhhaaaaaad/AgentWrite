package cn.sutone.ai.infrastructure.adapter.repository;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryGovernanceUndoPort;
import cn.sutone.ai.infrastructure.dao.po.MemoryGovernanceUndoItemPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.List;

/**
 * {@link IMemoryGovernanceUndoPort} 的适配器实现。
 *
 * <p>domain 层只依赖端口接口，本类负责把 domain 的 {@code UndoItem} 翻译为
 * infrastructure 的 {@link MemoryGovernanceUndoItemPO} 并委托给
 * {@link MemoryGovernanceUndoRepository}。参照 {@code MemoryMetrics} 实现
 * {@code IMemoryMetricsPort} 的端口-适配器模式。</p>
 */
@Slf4j
@Component
public class MemoryGovernanceUndoPortAdapter implements IMemoryGovernanceUndoPort {

    @Resource
    private MemoryGovernanceUndoRepository delegate;

    @Override
    public Long recordUndo(String action, List<UndoItem> items, Long mergedIntoId) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        List<MemoryGovernanceUndoItemPO> pos = items.stream()
                .map(i -> MemoryGovernanceUndoItemPO.builder()
                        .memoryId(i.memoryId())
                        .beforeStatus(i.beforeStatus())
                        .build())
                .toList();
        return delegate.recordUndo(action, pos, mergedIntoId);
    }
}
