package cn.sutone.ai.infrastructure.dao;

import cn.sutone.ai.domain.agent.model.valobj.OutboxEventVO;
import cn.sutone.ai.infrastructure.dao.po.OutboxEventPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IOutboxEventDao {

    @Options(useGeneratedKeys = true, keyProperty = "eventId")
    @Insert("INSERT INTO outbox_event(event_type, aggregate_id, topic, payload, status, retry_count, "
            + "next_retry_at, published_at, last_error, publisher_id, create_time, update_time) "
            + "VALUES(#{eventType}, #{aggregateId}, #{topic}, #{payload}, #{status}, #{retryCount}, "
            + "#{nextRetryAt}, #{publishedAt}, #{lastError}, #{publisherId}, #{createTime}, #{updateTime})")
    int insert(OutboxEventPO po);

    @Update("UPDATE outbox_event SET payload = #{payload}, update_time = NOW() WHERE event_id = #{eventId}")
    int updatePayload(@Param("eventId") Long eventId, @Param("payload") String payload);

    // ==================== 原子抢占 (Issue 1 修复) ====================

    /**
     * 原子抢占：UPDATE 批量标记 SENDING + publisher_id，利用行锁保证多实例安全
     */
    @Update("UPDATE outbox_event "
            + "SET status = '" + OutboxEventVO.CODE_SENDING + "', publisher_id = #{publisherId}, update_time = NOW() "
            + "WHERE status IN (" + OutboxEventVO.PUBLISHABLE_STATUSES + ") "
            + "AND next_retry_at <= NOW() "
            + "ORDER BY create_time ASC LIMIT #{limit}")
    int claimPublishableBatch(@Param("publisherId") String publisherId, @Param("limit") int limit);

    /**
     * 回查当前 publisher 已抢占的事件
     */
    @Select("SELECT event_id, event_type, aggregate_id, topic, payload, status, retry_count, "
            + "next_retry_at, published_at, last_error, publisher_id, create_time, update_time "
            + "FROM outbox_event "
            + "WHERE status = '" + OutboxEventVO.CODE_SENDING + "' "
            + "AND publisher_id = #{publisherId} "
            + "ORDER BY create_time ASC")
    List<OutboxEventPO> findClaimedByPublisherId(@Param("publisherId") String publisherId);

    /**
     * 恢复超时 SENDING 事件（被崩溃的 Publisher 遗留）
     */
    @Update("UPDATE outbox_event "
            + "SET status = '" + OutboxEventVO.CODE_RETRYING + "', publisher_id = NULL, update_time = NOW() "
            + "WHERE status = '" + OutboxEventVO.CODE_SENDING + "' "
            + "AND update_time < #{timeout}")
    int recoverStaleSending(@Param("timeout") LocalDateTime timeout);

    // ==================== 状态变更 ====================

    @Update("UPDATE outbox_event "
            + "SET status = '" + OutboxEventVO.CODE_PUBLISHED + "', published_at = NOW(), publisher_id = NULL, update_time = NOW() "
            + "WHERE event_id = #{eventId}")
    int markPublished(@Param("eventId") Long eventId);

    @Update("UPDATE outbox_event "
            + "SET status = '" + OutboxEventVO.CODE_RETRYING + "', retry_count = IFNULL(retry_count, 0) + 1, "
            + "next_retry_at = #{nextRetryAt}, last_error = #{lastError}, update_time = NOW() "
            + "WHERE event_id = #{eventId}")
    int scheduleRetry(@Param("eventId") Long eventId, @Param("nextRetryAt") LocalDateTime nextRetryAt, @Param("lastError") String lastError);

    @Update("UPDATE outbox_event "
            + "SET status = '" + OutboxEventVO.CODE_FAILED + "', last_error = #{lastError}, update_time = NOW() "
            + "WHERE event_id = #{eventId}")
    int markFailed(@Param("eventId") Long eventId, @Param("lastError") String lastError);

    // ==================== 查询 ====================

    @Select("SELECT COUNT(1) FROM outbox_event "
            + "WHERE aggregate_id = #{aggregateId} "
            + "AND status IN (" + OutboxEventVO.PENDING_OR_SENDING_STATUSES + ")")
    int countPendingByAggregateId(@Param("aggregateId") String aggregateId);

    @Select("SELECT COUNT(1) FROM outbox_event "
            + "WHERE status IN (" + OutboxEventVO.PUBLISHABLE_STATUSES + ")")
    int countPublishableEvents();

    @Select("SELECT event_id, event_type, aggregate_id, topic, payload, status, retry_count, "
            + "next_retry_at, published_at, last_error, publisher_id, create_time, update_time "
            + "FROM outbox_event "
            + "WHERE aggregate_id = #{aggregateId} "
            + "ORDER BY create_time DESC LIMIT 1")
    OutboxEventPO findLatestByAggregateId(@Param("aggregateId") Long aggregateId);
}
