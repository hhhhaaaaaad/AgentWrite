package cn.sutone.ai.domain.agent.service.memory;

import cn.sutone.ai.domain.agent.adapter.repository.IEvalFencingRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryEmbeddingClient;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.adapter.repository.IMemoryVectorStore;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评测破坏性写守卫服务（reset / seed / finalize 清理）。
 *
 * <p><b>核心不变量</b>：fencing 校验与 MySQL 破坏性写必须在<b>同一事务</b>内，
 * 且该校验对 {@code eval_fencing} 行加 {@code SELECT ... FOR UPDATE} 锁并持有到提交。
 * 这样「校验通过 → 继任者接管 → 僵尸写入」的 TOCTOU 窗口被数据库行锁封死：
 * 继任者的 acquire 会阻塞，直到本次破坏性写提交或回滚。</p>
 *
 * <p><b>向量写入/清理放在事务提交之后</b>（非事务性 HTTP）：
 * <ul>
 *   <li>不做网络 IO 占用 fencing 行锁，缩短临界区；</li>
 *   <li>事务回滚时不会误写/误删向量（否则会留下无向量的存活记录，而 eval 模式已禁用向量补偿任务）。</li>
 * </ul></p>
 */
@Slf4j
@Service
public class MemoryEvalGuardService {

    @Resource
    private IEvalFencingRepository fencingRepository;

    @Resource
    private IMemoryRepository memoryRepository;

    @Resource
    private IMemoryVectorStore vectorStore;

    @Resource
    private IMemoryEmbeddingClient embeddingClient;

    @Resource
    private MemoryManager memoryManager;

    @Resource
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate txTemplate;

    @PostConstruct
    void init() {
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    /** 种子条目（类型 + 内容） */
    public record SeedItem(MemoryTypeVO type, String content) {
    }

    /** 种子结果：新增数 / 已存在数 / content → id 映射（诊断） */
    public record SeedOutcome(long inserted, long existed, Map<String, Long> contentToId) {
    }

    /**
     * 带 fencing 守卫的重置：同事务内校验 + 物理删除，提交后清向量。
     *
     * @return MySQL 物理删除行数
     * @throws cn.sutone.ai.domain.agent.model.exception.MemoryEvalFencingException 校验失败（映射为 403）
     */
    public int resetGuarded(Long evalUserId, String runId, Long expectedVersion) {
        Integer deleted = txTemplate.execute(status -> {
            fencingRepository.lockAndValidate(evalUserId, runId, expectedVersion);
            return memoryRepository.deleteByUserId(evalUserId);
        });
        // 事务已提交：清理向量。失败抛出（不吞），保证「向量已清空」的响应真实可信。
        vectorStore.removeByUserId(evalUserId);
        memoryManager.bumpMemoryVersion(evalUserId);
        return deleted != null ? deleted : 0;
    }

    /**
     * 带 fencing 守卫的种子写入：同事务内校验 + 幂等落库，提交后写向量。
     *
     * <p>幂等：fencing 行锁已将同命名空间的 seed 串行化，故「先查后插」安全；
     * 唯一索引 {@code uk_user_hash} 作为并发兜底。</p>
     */
    public SeedOutcome seedGuarded(Long evalUserId, String runId, Long expectedVersion, List<SeedItem> items) {
        List<Object[]> pendingVectors = new ArrayList<>();
        SeedOutcome outcome = txTemplate.execute(status -> {
            fencingRepository.lockAndValidate(evalUserId, runId, expectedVersion);
            long inserted = 0;
            long existed = 0;
            Map<String, Long> contentToId = new LinkedHashMap<>();
            for (SeedItem item : items) {
                String hash = DigestUtils.md5Hex(item.content());
                MemoryRecordEntity existing = memoryRepository.selectByUserIdAndHash(evalUserId, hash);
                if (existing != null) {
                    existed++;
                    contentToId.putIfAbsent(item.content(), existing.getId());
                    continue;
                }
                MemoryRecordEntity record = MemoryRecordEntity.create(
                        null, evalUserId, item.type().getCode(), item.content(), hash, "eval-seed");
                record.setContentTokenized(item.content());
                Long id = memoryRepository.insert(record);
                inserted++;
                contentToId.putIfAbsent(item.content(), id);
                pendingVectors.add(new Object[]{id, item.content(), hash});
            }
            return new SeedOutcome(inserted, existed, contentToId);
        });

        // 事务已提交：写向量（不占用 fencing 行锁做网络 IO）
        for (Object[] row : pendingVectors) {
            upsertVector(evalUserId, (Long) row[0], (String) row[1], (String) row[2]);
        }
        if (!pendingVectors.isEmpty()) {
            memoryManager.bumpMemoryVersion(evalUserId);
        }
        return outcome != null ? outcome : new SeedOutcome(0, 0, Map.of());
    }

    /** 嵌入并写向量；embedding 不可用时标记 PENDING（不阻断 seed 落库） */
    private void upsertVector(Long evalUserId, Long id, String content, String hash) {
        try {
            float[] emb = embeddingClient.embed(content);
            if (emb.length > 0) {
                vectorStore.upsert(id, evalUserId, emb, content, hash);
                memoryRepository.updateVectorStatus(id, "SYNCED");
                return;
            }
        } catch (Exception e) {
            log.warn("eval seed 向量写入失败 id={}: {}", id, e.getMessage());
        }
        memoryRepository.updateVectorStatus(id, "PENDING");
    }
}
