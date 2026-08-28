package cn.sutone.ai.domain.agent.adapter.repository;

import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;

import java.util.List;

public interface IMemoryRepository {

    Long nextId();

    void insert(MemoryRecordEntity record);

    void updateContent(Long id, String newContent, String newHash, String newTokenized);

    MemoryRecordEntity queryById(Long id);

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

    /** 查询 PENDING 状态的记录（补偿任务用） */
    List<MemoryRecordEntity> selectPendingVectors();

    /** 按 importance/access/recency 查询高价值画像记忆 */
    List<MemoryRecordEntity> queryTopProfiles(Long userId, double minImportance, int limit);

    /** 更新重要性 */
    void updateImportance(Long id, double importance);
}
