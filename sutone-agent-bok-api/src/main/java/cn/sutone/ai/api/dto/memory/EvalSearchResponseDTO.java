package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 评测检索结果（/api/v1/eval/search）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvalSearchResponseDTO {

    private List<Item> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        /** 记忆 id（contentHash 匹配不依赖 createdId，此处仅诊断用） */
        private Long id;
        /** 记忆内容，供平台做 contentHash 匹配 */
        private String content;
        private double score;
        private Double importance;
        private String type;
        private Double confidence;
    }
}
