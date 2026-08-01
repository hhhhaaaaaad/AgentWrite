package cn.sutone.ai.domain.agent.model.entity;

import cn.sutone.ai.domain.agent.model.valobj.AiTaskStatusVO;
import cn.sutone.ai.domain.agent.model.valobj.AiWritingTaskTypeVO;
import cn.sutone.ai.types.enums.ResponseCode;
import cn.sutone.ai.types.exception.AppException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * AI 写作任务实体
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiTaskEntity {

    private Long taskId;
    private Long userId;
    private Long draftId;
    /** 用户自定义模型配置 ID，NULL 表示使用系统默认 */
    private Long modelConfigId;
    private AiWritingTaskTypeVO taskType;
    private String promptPayload;
    private Boolean enableIllustration;
    private String responseContent;
    private AiTaskStatusVO status;
    private String errorMsg;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 本次执行开始时间 */
    private LocalDateTime startedAt;
    /** Worker 执行心跳 */
    private LocalDateTime heartbeatAt;
    /** 已重试次数 */
    private Integer retryCount;
    /** 下次允许重试时间 */
    private LocalDateTime nextRetryAt;
    /** 执行 Worker 标识 */
    private String workerId;

    public static AiTaskEntity initPending(Long userId, Long draftId, AiWritingTaskTypeVO taskType, String promptPayload, Boolean enableIllustration) {
        if (null == userId) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "用户ID不能为空");
        }
        if (null == draftId) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "草稿ID不能为空");
        }
        LocalDateTime now = LocalDateTime.now();
        return AiTaskEntity.builder()
                .userId(userId)
                .draftId(draftId)
                .taskType(taskType)
                .promptPayload(promptPayload)
                .enableIllustration(null != enableIllustration && enableIllustration)
                .status(AiTaskStatusVO.PENDING)
                .retryCount(0)
                .createTime(now)
                .updateTime(now)
                .build();
    }

    /** 创建 PENDING 任务时快照用户的默认模型配置 ID（多租户） */
    public static AiTaskEntity initPending(Long userId, Long draftId, AiWritingTaskTypeVO taskType, String promptPayload, Boolean enableIllustration, Long modelConfigId) {
        AiTaskEntity task = AiTaskEntity.initPending(userId, draftId, taskType, promptPayload, enableIllustration);
        task.setModelConfigId(modelConfigId);
        return task;
    }

    public void startRunning() {
        if (this.status != AiTaskStatusVO.PENDING) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "只有待处理状态的任务可以开始运行");
        }
        this.status = AiTaskStatusVO.RUNNING;
        this.startedAt = LocalDateTime.now();
        this.heartbeatAt = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    public void touchHeartbeat() {
        this.heartbeatAt = LocalDateTime.now();
    }

    public void markSuccess(String responseContent) {
        if (this.status != AiTaskStatusVO.RUNNING) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "只有运行中的任务可以标记成功");
        }
        this.responseContent = responseContent;
        this.status = AiTaskStatusVO.SUCCESS;
        this.errorMsg = null;
        this.updateTime = LocalDateTime.now();
    }

    public void markFailed(String errorMsg) {
        this.status = AiTaskStatusVO.FAILED;
        this.errorMsg = errorMsg;
        this.updateTime = LocalDateTime.now();
    }

    public void markRetrying(String errorMsg) {
        this.status = AiTaskStatusVO.RETRYING;
        this.errorMsg = errorMsg;
        this.retryCount = (null == this.retryCount ? 0 : this.retryCount) + 1;
        this.nextRetryAt = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
    }

    public void validateOwner(Long userId) {
        if (null == userId || !userId.equals(this.userId)) {
            throw new AppException(ResponseCode.ILLEGAL_PARAMETER.getCode(), "无权操作该 AI 任务");
        }
    }
}
