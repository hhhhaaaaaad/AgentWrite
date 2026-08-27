package cn.sutone.ai.domain.agent.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 标准化后的记忆检索查询
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NormalizedMemoryQueryVO {

    private String queryMode;
    private String semanticQuery;
    private String lexicalQuery;
    private String canonicalText;
    private String cacheKeyDigest;
}
