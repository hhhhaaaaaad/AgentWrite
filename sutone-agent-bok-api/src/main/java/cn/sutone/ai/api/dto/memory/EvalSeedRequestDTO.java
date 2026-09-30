package cn.sutone.ai.api.dto.memory;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 评测种子写入请求（/api/v1/eval/seed）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvalSeedRequestDTO {

    /** 评测命名空间（服务端校验落在 [baseUserId, baseUserId+userIdRange) 内） */
    private Long evalUserId;

    /** 待写入的种子记忆 */
    private List<Item> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Item {
        /** 记忆类型：fact / preference / knowledge / event */
        private String type;
        /** 记忆内容 */
        private String content;
    }
}
