package cn.sutone.ai.test.domain.agent.model.entity;

import cn.sutone.ai.domain.agent.model.entity.AiTaskEntity;
import cn.sutone.ai.domain.agent.model.valobj.AiTaskStatusVO;
import cn.sutone.ai.domain.agent.model.valobj.AiWritingTaskTypeVO;
import cn.sutone.ai.types.exception.AppException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AiTaskEntity 单元测试")
class AiTaskEntityTest {

    private static final Long USER_ID = 10001L;
    private static final Long DRAFT_ID = 20001L;
    private static final Long TASK_ID = 30001L;
    private static final String PROMPT = "请生成一篇关于 Java 的文章";

    @Nested
    @DisplayName("initPending")
    class InitPending {

        @Test
        @DisplayName("应正确初始化所有字段")
        void shouldInitAllFields() {
            AiTaskEntity task = AiTaskEntity.initPending(USER_ID, DRAFT_ID, AiWritingTaskTypeVO.GENERATE_OUTLINE, PROMPT, false);

            assertNull(task.getTaskId()); // taskId 由 DB AUTO_INCREMENT 回填，创建时为 null
            assertEquals(USER_ID, task.getUserId());
            assertEquals(DRAFT_ID, task.getDraftId());
            assertEquals(AiWritingTaskTypeVO.GENERATE_OUTLINE, task.getTaskType());
            assertEquals(PROMPT, task.getPromptPayload());
            assertEquals(AiTaskStatusVO.PENDING, task.getStatus());
            assertNull(task.getResponseContent());
            assertNull(task.getErrorMsg());
            assertNotNull(task.getCreateTime());
            assertNotNull(task.getUpdateTime());
            assertEquals(task.getCreateTime(), task.getUpdateTime());
        }

        @Test
        @DisplayName("userId 为 null 时应抛出异常")
        void shouldThrowWhenUserIdNull() {
            assertThrows(AppException.class, () ->
                    AiTaskEntity.initPending(null, DRAFT_ID, AiWritingTaskTypeVO.GENERATE_OUTLINE, PROMPT, false));
        }

        @Test
        @DisplayName("draftId 为 null 时应抛出异常")
        void shouldThrowWhenDraftIdNull() {
            assertThrows(AppException.class, () ->
                    AiTaskEntity.initPending(USER_ID, null, AiWritingTaskTypeVO.GENERATE_OUTLINE, PROMPT, false));
        }
    }

    @Nested
    @DisplayName("startRunning")
    class StartRunning {

        @Test
        @DisplayName("PENDING 状态可转换为 RUNNING")
        void shouldTransitionFromPendingToRunning() {
            AiTaskEntity task = pendingTask();

            task.startRunning();

            assertEquals(AiTaskStatusVO.RUNNING, task.getStatus());
            assertNotNull(task.getUpdateTime());
        }

        @Test
        @DisplayName("非 PENDING 状态应抛出异常")
        void shouldThrowWhenNotPending() {
            AiTaskEntity task = pendingTask();
            task.startRunning();

            assertThrows(AppException.class, task::startRunning);
        }
    }

    @Nested
    @DisplayName("markSuccess")
    class MarkSuccess {

        @Test
        @DisplayName("应正确标记为完成")
        void shouldMarkSuccess() {
            AiTaskEntity task = pendingTask();
            String response = "这是一篇关于 Java 的文章...";

            // markSuccess 的前置状态是 RUNNING（见 AiTaskEntity.markSuccess 的守卫），
            // 因此必须先 startRunning() 走 PENDING -> RUNNING，再标记成功。
            // 此前直接从 PENDING 调 markSuccess 会抛「只有运行中的任务可以标记成功」。
            task.startRunning();
            task.markSuccess(response);

            assertEquals(AiTaskStatusVO.SUCCESS, task.getStatus());
            assertEquals(response, task.getResponseContent());
            assertNull(task.getErrorMsg());
            assertNotNull(task.getUpdateTime());
        }
    }

    @Nested
    @DisplayName("markFailed")
    class MarkFailed {

        @Test
        @DisplayName("应正确标记为失败")
        void shouldMarkFailed() {
            AiTaskEntity task = pendingTask();
            String errorMsg = "模型调用超时";

            task.markFailed(errorMsg);

            assertEquals(AiTaskStatusVO.FAILED, task.getStatus());
            assertEquals(errorMsg, task.getErrorMsg());
            assertNotNull(task.getUpdateTime());
        }
    }

    @Nested
    @DisplayName("validateOwner")
    class ValidateOwner {

        @Test
        @DisplayName("归属人校验通过")
        void shouldPassWhenOwnerMatches() {
            AiTaskEntity task = pendingTask();

            assertDoesNotThrow(() -> task.validateOwner(USER_ID));
        }

        @Test
        @DisplayName("非归属人校验失败")
        void shouldThrowWhenOwnerMismatches() {
            AiTaskEntity task = pendingTask();

            assertThrows(AppException.class, () -> task.validateOwner(99999L));
        }

        @Test
        @DisplayName("userId 为 null 时校验失败")
        void shouldThrowWhenUserIdNull() {
            AiTaskEntity task = pendingTask();

            assertThrows(AppException.class, () -> task.validateOwner(null));
        }
    }

    private static AiTaskEntity pendingTask() {
        return AiTaskEntity.initPending(USER_ID, DRAFT_ID, AiWritingTaskTypeVO.GENERATE_OUTLINE, PROMPT, false);
    }
}
