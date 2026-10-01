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
import java.time.LocalDateTime;
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

    /**
     * 种子条目（类型 + 内容 + 治理可选字段）。
     *
     * <p>后五个字段对应治理维度三类任务（consistency / expired / hallucination）可达所需的
     * 结构字段，全部可空。为 null 时行为与旧版逐位一致——由
     * {@link #applyGovernanceFields(MemoryRecordEntity, SeedItem)} 负责「仅覆写非空字段」。</p>
     */
    public record SeedItem(
            MemoryTypeVO type,
            String content,
            String subject,
            String predicate,
            String value,
            Double confidence,
            String expireTime) {

        /** 兼容旧调用：仅类型 + 内容，治理字段一律默认（不传 = 原行为）。 */
        public SeedItem(MemoryTypeVO type, String content) {
            this(type, content, null, null, null, null, null);
        }
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
                // create 不设置 subject/predicate/value/confidence、expireTime 由 defaultExpireTime
                // 推导。这里把调用方显式传入的治理字段覆写上去——只覆写非空字段，空字段保持
                // create 的默认值，因此「不传这些字段」与旧版逐位一致（向后兼容的锚点）。
                applyGovernanceFields(record, item);
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

    /**
     * 把 seed 条目里的治理可选字段覆写到待插入记录上（只覆写非空字段）。
     *
     * <p><b>为什么只覆写非空字段</b>：{@link MemoryRecordEntity#create} 已经把
     * {@code subject/predicate/value/confidence} 留成 {@code null}、{@code expireTime} 用
     * {@code defaultExpireTime(type)} 推导。这里只对显式传入的值覆写，未传入的保持
     * create 的默认——这正是「不传这些字段 = 旧版行为」的向后兼容锚点。</p>
     *
     * <p><b>{@code expireTime} 的语义（显式 / 缺省 / 传过去时刻）</b>：
     * <ul>
     *   <li><b>缺省</b>（{@code null} 或空白字符串）：保留 {@code defaultExpireTime(type)} 的
     *       推导——fact 永久（null）、preference/knowledge/event 为 now+180~365 天。与旧版一致。</li>
     *   <li><b>显式传 null 与不传等价</b>：JSON 反序列化后二者都是 {@code null}，都走默认推导。
     *       当前评测集没有「强制永久覆盖默认」的场景，故不引入 sentinel 去区分（YAGNI）。</li>
     *   <li><b>传过去时刻</b>（如 {@code "2020-01-01T00:00:00"}）：解析后覆写，使
     *       {@code expired} 任务的扫描条件
     *       {@code expire_time IS NOT NULL AND expire_time < NOW()} 首次可满足——这是三类任务
     *       可达的前提。</li>
     *   <li><b>传未来时刻</b>：同样覆写为显式值，但不会被 expired 扫到（预留语义）。</li>
     * </ul></p>
     *
     * <p><b>守卫不被绕过</b>：本方法只改「内存里这条待插入记录」的字段，不触碰 fencing、
     * 命名空间校验、幂等任何环节——它们仍在 {@link #seedGuarded} 的同事务流程内原样执行。</p>
     */
    private void applyGovernanceFields(MemoryRecordEntity record, SeedItem item) {
        if (item.subject() != null) {
            record.setSubject(item.subject());
        }
        if (item.predicate() != null) {
            record.setPredicate(item.predicate());
        }
        if (item.value() != null) {
            record.setValue(item.value());
        }
        if (item.confidence() != null) {
            record.setConfidence(item.confidence());
        }
        if (item.expireTime() != null && !item.expireTime().isBlank()) {
            // ISO_LOCAL_DATE_TIME：调用方必须给 "yyyy-MM-ddTHH:mm:ss" 这种格式。
            // 解析失败会抛 DateTimeParseException，随事务回滚（不会留下半截写入）。
            record.setExpireTime(LocalDateTime.parse(item.expireTime()));
        }
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
