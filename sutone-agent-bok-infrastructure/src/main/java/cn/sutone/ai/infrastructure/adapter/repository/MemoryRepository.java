package cn.sutone.ai.infrastructure.adapter.repository;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.MemoryTypeVO;
import cn.sutone.ai.infrastructure.dao.IChatMessageDao;
import cn.sutone.ai.infrastructure.dao.IMemoryHistoryDao;
import cn.sutone.ai.infrastructure.dao.IMemoryRecordDao;
import cn.sutone.ai.infrastructure.dao.po.ChatMessagePO;
import cn.sutone.ai.infrastructure.dao.po.MemoryHistoryPO;
import cn.sutone.ai.infrastructure.dao.po.MemoryRecordPO;
import org.springframework.stereotype.Repository;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Repository
public class MemoryRepository implements IMemoryRepository {

    @Resource
    private IMemoryRecordDao memoryRecordDao;

    @Resource
    private IMemoryHistoryDao memoryHistoryDao;

    @Resource
    private IChatMessageDao chatMessageDao;

    @Override
    public Long nextId() {
        return memoryRecordDao.nextId();
    }

    @Override
    public void insert(MemoryRecordEntity record) {
        memoryRecordDao.insert(toPO(record));
    }

    @Override
    public void updateContent(Long id, String newContent, String newHash, String newTokenized) {
        memoryRecordDao.updateContent(id, newContent, newHash, newTokenized);
    }

    @Override
    public MemoryRecordEntity queryById(Long id) {
        return toEntity(memoryRecordDao.selectById(id));
    }

    @Override
    public List<MemoryRecordEntity> queryByUserId(Long userId, int offset, int limit) {
        return memoryRecordDao.selectByUserId(userId, offset, limit).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public int countByUserId(Long userId) {
        return memoryRecordDao.countByUserId(userId);
    }

    @Override
    public void deleteById(Long id) {
        memoryRecordDao.deleteById(id);
    }

    @Override
    public List<MemoryRecordEntity> selectAllActive() {
        return memoryRecordDao.selectAllActive().stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryRecordEntity> fulltextSearch(Long userId, String query, int limit) {
        return memoryRecordDao.fulltextSearch(userId, query, limit).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public void batchUpdateAccessInfo(List<Long> ids) {
        LocalDateTime now = LocalDateTime.now();
        for (Long id : ids) {
            memoryRecordDao.updateAccessInfo(id, now);
        }
    }

    @Override
    public void insertHistory(Long memoryId, String oldContent, String newContent, String event, String sessionId) {
        MemoryHistoryPO po = MemoryHistoryPO.builder()
                .memoryId(memoryId)
                .sessionId(sessionId)
                .oldContent(oldContent)
                .newContent(newContent)
                .event(event)
                .build();
        memoryHistoryDao.insert(po);
    }

    @Override
    public List<String> getLastMessages(String sessionId, int limit) {
        List<ChatMessagePO> messages = chatMessageDao.selectLastN(sessionId, limit);
        if (messages == null || messages.isEmpty()) {
            return Collections.emptyList();
        }
        Collections.reverse(messages);
        return messages.stream()
                .map(m -> "[" + m.getRole() + "]: " + m.getContent())
                .collect(Collectors.toList());
    }

    @Override
    public void updateVectorStatus(Long id, String status) {
        memoryRecordDao.updateVectorStatus(id, status);
    }

    @Override
    public List<MemoryRecordEntity> selectPendingVectors() {
        return memoryRecordDao.selectPendingVectors().stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryRecordEntity> queryTopProfiles(Long userId, double minImportance, int limit) {
        return memoryRecordDao.selectTopProfiles(userId, minImportance, limit).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public void updateImportance(Long id, double importance) {
        memoryRecordDao.updateImportance(id, importance);
    }

    private MemoryRecordPO toPO(MemoryRecordEntity entity) {
        return MemoryRecordPO.builder()
                .id(entity.getId())
                .userId(entity.getUserId())
                .type(entity.getType().getCode())
                .content(entity.getContent())
                .contentHash(entity.getContentHash())
                .contentTokenized(entity.getContentTokenized())
                .sourceSessionId(entity.getSourceSessionId())
                .importance(entity.getImportance())
                .accessCount(entity.getAccessCount())
                .isDeleted(0)
                .build();
    }

    private MemoryRecordEntity toEntity(MemoryRecordPO po) {
        if (null == po) {
            return null;
        }
        return MemoryRecordEntity.builder()
                .id(po.getId())
                .userId(po.getUserId())
                .type(MemoryTypeVO.fromCode(po.getType()))
                .content(po.getContent())
                .contentHash(po.getContentHash())
                .contentTokenized(po.getContentTokenized())
                .sourceSessionId(po.getSourceSessionId())
                .importance(po.getImportance())
                .accessCount(po.getAccessCount())
                .lastAccessedAt(po.getLastAccessedAt())
                .createTime(po.getCreateTime())
                .updateTime(po.getUpdateTime())
                .matchScore(po.getMatchScore())
                .build();
    }
}
