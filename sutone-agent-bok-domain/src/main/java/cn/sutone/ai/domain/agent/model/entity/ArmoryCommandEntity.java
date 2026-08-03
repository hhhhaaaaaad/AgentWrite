package cn.sutone.ai.domain.agent.model.entity;

import cn.sutone.ai.domain.agent.model.valobj.AiAgentConfigTableVO;
import cn.sutone.ai.domain.agent.model.valobj.UserModelConfigVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 装配命令实体
 * <p>
 * 携带 AI 智能体配置表和可选的用户自定义模型配置，
 * 在策略树装配链路中传递，AiApiNode/ChatModelNode 据此构建专属 ChatModel。
 * </p>
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class ArmoryCommandEntity {

    private AiAgentConfigTableVO aiAgentConfigTableVO;

    /** 运行时注入的用户模型配置，null = 使用系统默认 */
    private UserModelConfigVO userModelConfig;

}
