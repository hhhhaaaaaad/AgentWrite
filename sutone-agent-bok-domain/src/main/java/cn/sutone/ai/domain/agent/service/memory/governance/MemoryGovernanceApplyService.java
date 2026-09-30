package cn.sutone.ai.domain.agent.service.memory.governance;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryGovernanceUndoPort;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.model.valobj.GovernanceDecision;
import cn.sutone.ai.domain.agent.model.valobj.MemoryStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

/**
 * 记忆治理决策<b>落库</b>服务。
 *
 * <p>消费 {@link MemoryGovernanceComputeService} 产出的 {@link GovernanceDecision}，
 * 逐条改 status 并写撤销记录（保持「先软标记、后硬化」的可逆性契约）。</p>
 *
 * <p>与 compute 层严格分离：评测 replay 只调 compute，不会触发本类。</p>
 */
@Slf4j
@Service
public class MemoryGovernanceApplyService {

    @Resource
    private IMemoryRepository memoryRepository;

    @Resource
    private IMemoryGovernanceUndoPort undoPort;

    /**
     * 落库一批治理决策。
     *
     * @param decisions compute 层产出的决策列表
     * @return 被作用记忆条数
     */
    public int apply(List<GovernanceDecision> decisions) {
        if (decisions == null || decisions.isEmpty()) {
            return 0;
        }
        int affected = 0;
        for (GovernanceDecision decision : decisions) {
            if (decision.items() == null || decision.items().isEmpty()) {
                continue;
            }
            List<IMemoryGovernanceUndoPort.UndoItem> undoItems = new ArrayList<>(decision.items().size());
            for (GovernanceDecision.Item item : decision.items()) {
                memoryRepository.updateStatus(item.memoryId(), MemoryStatus.fromCode(item.afterStatus()));
                undoItems.add(new IMemoryGovernanceUndoPort.UndoItem(item.memoryId(), item.beforeStatus()));
                affected++;
            }
            undoPort.recordUndo(decision.action(), undoItems, decision.mergedIntoId());
        }
        log.info("[governance-apply] 已落库决策 {} 批，作用记忆 {} 条", decisions.size(), affected);
        return affected;
    }
}
