package cn.sutone.ai.infrastructure.adapter.repository;

import cn.sutone.ai.domain.agent.adapter.repository.IMemoryRepository;
import cn.sutone.ai.domain.agent.model.entity.MemoryRecordEntity;
import cn.sutone.ai.domain.agent.model.valobj.MemoryStatus;
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
    public Long insert(MemoryRecordEntity record) {
        MemoryRecordPO po = toPO(record);
        memoryRecordDao.insert(po);
        return po.getId();
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
    public List<MemoryRecordEntity> queryByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        return memoryRecordDao.selectByIds(ids).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
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
        if (ids == null || ids.isEmpty()) {
            return;
        }
        memoryRecordDao.batchUpdateAccessInfo(ids, LocalDateTime.now());
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
    public int incrementVectorRetry(Long id) {
        memoryRecordDao.updateVectorStatusWithRetry(id, "PENDING");
        Integer retry = memoryRecordDao.selectRetryCount(id);
        return retry != null ? retry : 0;
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

    @Override
    public void closeVersion(Long id, LocalDateTime validTo) {
        memoryRecordDao.closeVersion(id, validTo);
    }

    @Override
    public Long insertVersioned(MemoryRecordEntity record) {
        // 版本字段（version=旧+1、status=ACTIVE、valid_from=now）已由上层构造好，
        // SQL 与 insert 完全一致（均显式写 vector_status='PENDING'）
        return insert(record);
    }

    @Override
    public void markVectorPending(Long id) {
        memoryRecordDao.markVectorPending(id);
    }

    @Override
    public void markVectorDeletePending(Long id) {
        memoryRecordDao.markVectorDeletePending(id);
    }

    @Override
    public void markVectorDeleted(Long id) {
        memoryRecordDao.markVectorDeleted(id);
    }

    @Override
    public MemoryRecordEntity selectActiveByUserSubjectPredicate(Long userId, String subject, String predicate) {
        return toEntity(memoryRecordDao.selectActiveByUserSubjectPredicate(userId, subject, predicate));
    }

    @Override
    public int claimVectorSync(Long id) {
        return memoryRecordDao.claimVectorSync(id);
    }

    @Override
    public int scheduleVectorRetry(Long id, String status, LocalDateTime nextRetryAt, String lastError) {
        memoryRecordDao.scheduleVectorRetry(id, status, nextRetryAt, lastError);
        Integer retry = memoryRecordDao.selectRetryCount(id);
        return retry != null ? retry : 0;
    }

    @Override
    public void markVectorSynced(Long id) {
        memoryRecordDao.markVectorSynced(id);
    }

    @Override
    public List<MemoryRecordEntity> selectVectorDeletePending() {
        return memoryRecordDao.selectVectorDeletePending().stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public void updateStatus(Long id, MemoryStatus status) {
        memoryRecordDao.updateStatus(id, status.getCode());
    }

    @Override
    public List<MemoryRecordEntity> selectActiveForDuplicateScan() {
        return memoryRecordDao.selectActiveForDuplicateScan().stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryRecordEntity> selectActiveForConsistencyScan() {
        return memoryRecordDao.selectActiveForConsistencyScan().stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryRecordEntity> selectExpiredForArchive(LocalDateTime before) {
        return memoryRecordDao.selectExpiredForArchive(before).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
    }

    @Override
    public List<MemoryRecordEntity> selectSampleForHallucinationCheck(double minConfidence, double maxConfidence, int limit) {
        return memoryRecordDao.selectSampleForHallucinationCheck(minConfidence, maxConfidence, limit).stream()
                .map(this::toEntity)
                .collect(Collectors.toList());
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
                .attributedTo(entity.getAttributedTo())
                .confidence(entity.getConfidence())
                .expireTime(entity.getExpireTime())
                .subject(entity.getSubject())
                .predicate(entity.getPredicate())
                .value(entity.getValue())
                .evidence(entity.getEvidence())
                .traceId(entity.getTraceId())
                .operation(entity.getOperation())
                .version(entity.getVersion())
                .status(entity.getStatus() != null ? entity.getStatus().getCode() : null)
                .validFrom(entity.getValidFrom())
                .validTo(entity.getValidTo())
                .nextRetryAt(entity.getNextRetryAt())
                .lastError(entity.getLastError())
                .sourceArticleId(entity.getSourceArticleId())
                .sourceArticleTitle(entity.getSourceArticleTitle())
                .sourceArticleSummary(entity.getSourceArticleSummary())
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
                .vectorStatus(po.getVectorStatus())
                .retryCount(po.getRetryCount())
                .attributedTo(po.getAttributedTo())
                .confidence(po.getConfidence())
                .expireTime(po.getExpireTime())
                .subject(po.getSubject())
                .predicate(po.getPredicate())
                .value(po.getValue())
                .evidence(po.getEvidence())
                .traceId(po.getTraceId())
                .operation(po.getOperation())
                .version(po.getVersion())
                .status(MemoryStatus.fromCode(po.getStatus()))
                .validFrom(po.getValidFrom())
                .validTo(po.getValidTo())
                .nextRetryAt(po.getNextRetryAt())
                .lastError(po.getLastError())
                .sourceArticleId(po.getSourceArticleId())
                .sourceArticleTitle(po.getSourceArticleTitle())
                .sourceArticleSummary(po.getSourceArticleSummary())
                .build();
    }
}
