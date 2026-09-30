package cn.sutone.ai.domain.agent.adapter.repository;

import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.MemoryStatus;

import java.time.LocalDateTime;
import java.util.List;

public interface IMemoryRepository {

    /** 插入记忆并返回回填的自增主键 */
    Long insert(MemoryRecordEntity record);

    void updateContent(Long id, String newContent, String newHash, String newTokenized);

    MemoryRecordEntity queryById(Long id);

    /** 批量回表加载权威元数据（检索融合后按 id 批量取权威字段，MySQL 为权威） */
    List<MemoryRecordEntity> queryByIds(List<Long> ids);

    /** seed 幂等：按 (userId, contentHash) 查已存在记忆（撞 uk_user_hash 后回查 createdId） */
    MemoryRecordEntity selectByUserIdAndHash(Long userId, String contentHash);

    /** 评测 reset：物理删除某命名空间全部记忆，返回删除行数 */
    int deleteByUserId(Long userId);

    List<MemoryRecordEntity> queryByUserId(Long userId, int offset, int limit);

    int countByUserId(Long userId);

    void deleteById(Long id);

    List<MemoryRecordEntity> selectAllActive();

    List<MemoryRecordEntity> fulltextSearch(Long userId, String query, int limit);

    void batchUpdateAccessInfo(List<Long> ids);

    void insertHistory(Long memoryId, String oldContent, String newContent, String event, String sessionId);

    List<String> getLastMessages(String sessionId, int limit);

    /** 更新向量同步状态 */
    void updateVectorStatus(Long id, String status);

    /** 向量同步失败时自增 retry_count 并返回自增后的值（唯一自增路径，避免双重自增） */
    int incrementVectorRetry(Long id);

    /** 查询 PENDING 状态的记录（补偿任务用） */
    List<MemoryRecordEntity> selectPendingVectors();

    /** 按 importance/access/recency 查询高价值画像记忆 */
    List<MemoryRecordEntity> queryTopProfiles(Long userId, double minImportance, int limit);

    /** 更新重要性 */
    void updateImportance(Long id, double importance);

    /** P1-4: 关闭旧版本（SUPERSEDED + valid_to），保留原始行供追溯 */
    void closeVersion(Long id, LocalDateTime validTo);

    /** P1-4: 插入新版本（version/status/valid_from 等字段已由上层构造，SQL 与 insert 一致） */
    Long insertVersioned(MemoryRecordEntity record);

    /** P1-4: 标记向量待同步 */
    void markVectorPending(Long id);

    /** P1-4: 标记旧向量待删（版本化 UPDATE 后由 MemoryVectorSyncJob 删除） */
    void markVectorDeletePending(Long id);

    /** P1-4: 标记旧向量已删（终态） */
    void markVectorDeleted(Long id);

    /** P1-4: 按 (user_id, subject, predicate, status='ACTIVE') 精确查唯一 ACTIVE 版本 */
    MemoryRecordEntity selectActiveByUserSubjectPredicate(Long userId, String subject, String predicate);

    /** P1-4: CAS 抢占向量同步权，返回受影响行数（0 = 已被抢占 / 未到重试时间） */
    int claimVectorSync(Long id);

    /** P1-4: 失败重试，返回自增后的 retry_count */
    int scheduleVectorRetry(Long id, String status, LocalDateTime nextRetryAt, String lastError);

    /** P1-4: 标记向量同步成功（清空退避与错误） */
    void markVectorSynced(Long id);

    /** P1-4: 查询待删旧向量 */
    List<MemoryRecordEntity> selectVectorDeletePending();

    /** P3 治理: 软标记生命周期状态（先软标记后硬化，只改 status 不物理删除） */
    void updateStatus(Long id, MemoryStatus status);

    /** P3 治理: 查询全部 ACTIVE 记忆（重复聚类按 user_id+type 分组） */
    List<MemoryRecordEntity> selectActiveForDuplicateScan();

    /** P3 治理: 查询含 subject+predicate 的 ACTIVE 记忆（事实一致性巡检聚合） */
    List<MemoryRecordEntity> selectActiveForConsistencyScan();

    /** P3 治理: 扫描过期且长期未激活的 ACTIVE 记忆（软归档） */
    List<MemoryRecordEntity> selectExpiredForArchive(LocalDateTime before);

    /** P3 治理: 抽样 confidence 灰色地带的 ACTIVE 记忆（幻觉抽检） */
    List<MemoryRecordEntity> selectSampleForHallucinationCheck(double minConfidence, double maxConfidence, int limit);
}
