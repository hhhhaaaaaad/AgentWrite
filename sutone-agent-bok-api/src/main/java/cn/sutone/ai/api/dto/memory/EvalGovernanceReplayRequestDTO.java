package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 治理重放请求（/api/v1/eval/governance/replay）。
 *
 * <p>只读重放治理判定，<b>不落库</b>。四个开关选择重放哪些治理任务，默认全开。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvalGovernanceReplayRequestDTO {

    /** 评测命名空间（服务端校验落在 [baseUserId, baseUserId+userIdRange) 内） */
    private Long evalUserId;

    /** 重放重复聚类判定（MERGE） */
    private boolean duplicates = true;

    /** 重放事实一致性判定（DISPUTE） */
    private boolean consistency = true;

    /** 重放过期清理判定（ARCHIVE） */
    private boolean expired = true;

    /** 重放幻觉抽检判定（QUARANTINE） */
    private boolean hallucination = true;
}
