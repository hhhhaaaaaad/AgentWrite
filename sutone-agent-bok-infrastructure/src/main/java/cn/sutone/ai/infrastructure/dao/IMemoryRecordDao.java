package cn.sutone.ai.infrastructure.dao;

import cn.sutone.ai.infrastructure.dao.po.MemoryRecordPO;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IMemoryRecordDao {

    @Select("SELECT IFNULL(MAX(id), 0) + 1 FROM memory_record")
    Long nextId();

    @Insert("""
            INSERT INTO memory_record(id, user_id, type, content, content_hash, content_tokenized, source_session_id, importance, access_count, is_deleted)
            VALUES(#{id}, #{userId}, #{type}, #{content}, #{contentHash}, #{contentTokenized}, #{sourceSessionId}, #{importance}, #{accessCount}, #{isDeleted})
            """)
    int insert(MemoryRecordPO po);

    @Update("""
            UPDATE memory_record
            SET content = #{content},
                content_hash = #{contentHash},
                content_tokenized = #{contentTokenized}
            WHERE id = #{id} AND is_deleted = 0
            """)
    int updateContent(@Param("id") Long id, @Param("content") String content,
                      @Param("contentHash") String contentHash, @Param("contentTokenized") String contentTokenized);

    @Select("""
            SELECT id, user_id, type, content, content_hash, content_tokenized, source_session_id, importance, access_count, last_accessed_at, create_time, update_time, is_deleted
            FROM memory_record
            WHERE id = #{id} AND is_deleted = 0
            """)
    MemoryRecordPO selectById(@Param("id") Long id);

    @Select("""
            SELECT id, user_id, type, content, content_hash, content_tokenized, source_session_id, importance, access_count, last_accessed_at, create_time, update_time, is_deleted
            FROM memory_record
            WHERE user_id = #{userId} AND is_deleted = 0
            ORDER BY create_time DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<MemoryRecordPO> selectByUserId(@Param("userId") Long userId, @Param("offset") int offset, @Param("limit") int limit);

    @Select("""
            SELECT COUNT(1) FROM memory_record
            WHERE user_id = #{userId} AND is_deleted = 0
            """)
    int countByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT id, user_id, type, content, content_hash, content_tokenized, source_session_id, importance, access_count, last_accessed_at, create_time, update_time, is_deleted
            FROM memory_record
            WHERE is_deleted = 0
            """)
    List<MemoryRecordPO> selectAllActive();

    @Select("""
            SELECT id, user_id, type, content, content_hash,
                   MATCH(content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS match_score
            FROM memory_record
            WHERE user_id = #{userId} AND is_deleted = 0
              AND MATCH(content) AGAINST(#{query} IN NATURAL LANGUAGE MODE)
            ORDER BY match_score DESC
            LIMIT #{limit}
            """)
    List<MemoryRecordPO> fulltextSearch(@Param("userId") Long userId, @Param("query") String query, @Param("limit") int limit);

    @Update("UPDATE memory_record SET is_deleted = 1 WHERE id = #{id}")
    int deleteById(@Param("id") Long id);

    @Update("""
            UPDATE memory_record
            SET access_count = access_count + 1,
                last_accessed_at = #{now}
            WHERE id = #{id}
            """)
    int updateAccessInfo(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Update("UPDATE memory_record SET vector_status = #{status} WHERE id = #{id}")
    int updateVectorStatus(@Param("id") Long id, @Param("status") String status);

    @Select("""
            SELECT id, user_id, type, content, content_hash, content_tokenized,
                   source_session_id, importance, access_count, last_accessed_at,
                   create_time, update_time, is_deleted, vector_status,
                   IFNULL(retry_count, 0) AS retry_count
            FROM memory_record
            WHERE vector_status = 'PENDING' AND is_deleted = 0
            ORDER BY create_time ASC
            """)
    List<MemoryRecordPO> selectPendingVectors();

    @Select("""
            SELECT id, user_id, type, content, content_hash, content_tokenized,
                   source_session_id, importance, access_count, last_accessed_at,
                   create_time, update_time, is_deleted
            FROM memory_record
            WHERE user_id = #{userId} AND is_deleted = 0
              AND importance >= #{minImportance}
            ORDER BY importance DESC, access_count DESC, last_accessed_at DESC, create_time DESC
            LIMIT #{limit}
            """)
    List<MemoryRecordPO> selectTopProfiles(@Param("userId") Long userId,
                                           @Param("minImportance") double minImportance,
                                           @Param("limit") int limit);

    @Update("UPDATE memory_record SET vector_status = #{status}, retry_count = IFNULL(retry_count, 0) + 1 WHERE id = #{id}")
    int updateVectorStatusWithRetry(@Param("id") Long id, @Param("status") String status);

    @Update("UPDATE memory_record SET importance = #{importance} WHERE id = #{id}")
    int updateImportance(@Param("id") Long id, @Param("importance") double importance);
}
