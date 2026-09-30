package cn.sutone.ai.test.integration;

import cn.sutone.ai.domain.agent.service.memory.MemoryVectorSyncJob;
import cn.sutone.ai.infrastructure.job.MemoryGovernanceJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import javax.annotation.Resource;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * eval profile cron 条件装配集成测试。
 *
 * <p>{@code memory.eval.enabled=true} 时，会改写语料 / 写 Qdrant 的定时任务必须不装配，
 * 避免评测期间被后台任务污染。业务 profile（默认 false）则正常装配。</p>
 */
@SpringBootTest(properties = {"memory.eval.enabled=true", "memory.vector-store=memory"})
@EnabledIf("cn.sutone.ai.test.integration.EvalInfra#mysqlAvailable")
@DisplayName("eval profile cron 禁用集成测试")
class EvalProfileCronDisabledTest {

    @Resource
    private ApplicationContext context;

    @Test
    @DisplayName("eval 模式下治理巡检任务不装配")
    void governanceJobAbsentInEvalMode() {
        assertThrows(NoSuchBeanDefinitionException.class,
                () -> context.getBean(MemoryGovernanceJob.class),
                "eval 模式不得装配 MemoryGovernanceJob（会改写语料 status）");
    }

    @Test
    @DisplayName("eval 模式下向量同步任务不装配")
    void vectorSyncJobAbsentInEvalMode() {
        assertThrows(NoSuchBeanDefinitionException.class,
                () -> context.getBean(MemoryVectorSyncJob.class),
                "eval 模式不得装配 MemoryVectorSyncJob（会写 Qdrant）");
    }

    @Test
    @DisplayName("eval 模式下评测所需的内存向量库仍正常装配")
    void vectorStoreStillPresentInEvalMode() {
        assertNotNull(context.getBean(cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore.class),
                "向量库本身不得被 eval 条件误禁");
    }
}
