package cn.sutone.ai.infrastructure.dao;

import cn.sutone.ai.infrastructure.dao.po.MemoryRecordPO;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IMemoryRecordDao {

    @Insert("""
            INSERT INTO memory_record(user_id, type, content, content_hash, content_tokenized,
                source_session_id, importance, access_count, is_deleted, vector_status,
                attributed_to, confidence, expire_time, subject, predicate, `value`, evidence,
                trace_id, operation, version, status, valid_from, valid_to,
                source_article_id, source_article_title, source_article_summary)
            VALUES(#{userId}, #{type}, #{content}, #{contentHash}, #{contentTokenized},
                #{sourceSessionId}, #{importance}, #{accessCount}, #{isDeleted}, 'PENDING',
                #{attributedTo}, #{confidence}, #{expireTime}, #{subject}, #{predicate}, #{value}, #{evidence},
                #{traceId}, #{operation}, #{version}, #{status}, #{validFrom}, #{validTo},
                #{sourceArticleId}, #{sourceArticleTitle}, #{sourceArticleSummary})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
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
            SELECT id, user_id, type, content, content_hash, content_tokenized, source_session_id, importance, access_count, last_accessed_at, create_time, update_time, is_deleted, attributed_to, confidence, expire_time, subject, predicate, `value`, evidence, trace_id, operation, version, status, valid_from, valid_to, next_retry_at, last_error, source_article_id, source_article_title, source_article_summary
            FROM memory_record
            WHERE id = #{id} AND is_deleted = 0
            """)
    MemoryRecordPO selectById(@Param("id") Long id);

    @Select("""
            <script>
            SELECT id, user_id, type, content, content_hash, content_tokenized, source_session_id, importance, access_count, last_accessed_at, create_time, update_time, is_deleted, attributed_to, confidence, expire_time, subject, predicate, `value`, evidence, trace_id, operation, version, status, valid_from, valid_to, next_retry_at, last_error, source_article_id, source_article_title, source_article_summary
            FROM memory_record
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
              AND is_deleted = 0
            </script>
            """)
    List<MemoryRecordPO> selectByIds(@Param("ids") List<Long> ids);

    @Select("""
            SELECT id, user_id, type, content, content_hash, content_tokenized, source_session_id, importance, access_count, last_accessed_at, create_time, update_time, is_deleted, attributed_to, confidence, expire_time, subject, predicate, `value`, evidence, trace_id, operation, version, status, valid_from, valid_to, next_retry_at, last_error, source_article_id, source_article_title, source_article_summary
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

    /** seed 幂等：按 (user_id, content_hash) 查唯一已存在行（撞 uk_user_hash 后回查 createdId） */
    @Select("""
            SELECT id, user_id, type, content, content_hash, content_tokenized, source_session_id, importance, access_count, last_accessed_at, create_time, update_time, is_deleted, attributed_to, confidence, expire_time, subject, predicate, `value`, evidence, trace_id, operation, version, status, valid_from, valid_to, next_retry_at, last_error, source_article_id, source_article_title, source_article_summary
            FROM memory_record
            WHERE user_id = #{userId} AND content_hash = #{contentHash} AND is_deleted = 0
            LIMIT 1
            """)
    MemoryRecordPO selectByUserIdAndHash(@Param("userId") Long userId, @Param("contentHash") String contentHash);

    /** 评测 reset：物理删除某命名空间全部记忆（uk_user_hash 不区分软删，必须物理删才释放唯一键） */
    @Delete("DELETE FROM memory_record WHERE user_id = #{userId}")
    int deleteByUserId(@Param("userId") Long userId);

    @Select("""
            SELECT id, user_id, type, content, content_hash, content_tokenized, source_session_id, importance, access_count, last_accessed_at, create_time, update_time, is_deleted, attributed_to, confidence, expire_time, subject, predicate, `value`, evidence, trace_id, operation, version, status, valid_from, valid_to, next_retry_at, last_error, source_article_id, source_article_title, source_article_summary
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
            <script>
            UPDATE memory_record
            SET access_count = access_count + 1,
                last_accessed_at = #{now}
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    int batchUpdateAccessInfo(@Param("ids") List<Long> ids, @Param("now") LocalDateTime now);

    @Update("UPDATE memory_record SET vector_status = #{status} WHERE id = #{id}")
    int updateVectorStatus(@Param("id") Long id, @Param("status") String status);

    @Select("""
            SELECT id, user_id, type, content, content_hash, content_tokenized,
                   source_session_id, importance, access_count, last_accessed_at,
                   create_time, update_time, is_deleted, vector_status,
                   IFNULL(retry_count, 0) AS retry_count, attributed_to, confidence,
                   expire_time, subject, predicate, `value`, evidence, trace_id,
                   operation, version, status, valid_from, valid_to, next_retry_at, last_error, source_article_id, source_article_title, source_article_summary
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

    @Select("SELECT IFNULL(retry_count, 0) FROM memory_record WHERE id = #{id}")
    Integer selectRetryCount(@Param("id") Long id);

    @Update("UPDATE memory_record SET importance = #{importance} WHERE id = #{id}")
    int updateImportance(@Param("id") Long id, @Param("importance") double importance);

    /** P1-4: 关闭旧版本（SUPERSEDED + valid_to），保留原始行供追溯 */
    @Update("UPDATE memory_record SET status = 'SUPERSEDED', valid_to = #{validTo} WHERE id = #{id}")
    int closeVersion(@Param("id") Long id, @Param("validTo") LocalDateTime validTo);

    /** P1-4: 标记向量待同步 */
    @Update("UPDATE memory_record SET vector_status = 'PENDING' WHERE id = #{id}")
    int markVectorPending(@Param("id") Long id);

    /** P1-4: 标记旧向量待删（版本化 UPDATE 后，旧版本向量由 MemoryVectorSyncJob 删除） */
    @Update("UPDATE memory_record SET vector_status = 'DELETE_PENDING' WHERE id = #{id}")
    int markVectorDeletePending(@Param("id") Long id);

    /** P1-4: 旧向量已删（终态标记） */
    @Update("UPDATE memory_record SET vector_status = 'DELETED' WHERE id = #{id}")
    int markVectorDeleted(@Param("id") Long id);

    /** P1-4: 按 (user_id, subject, predicate, status='ACTIVE') 精确查唯一 ACTIVE 版本 */
    @Select("""
            SELECT id, user_id, type, content, content_hash, content_tokenized, source_session_id, importance, access_count, last_accessed_at, create_time, update_time, is_deleted, attributed_to, confidence, expire_time, subject, predicate, `value`, evidence, trace_id, operation, version, status, valid_from, valid_to, next_retry_at, last_error, source_article_id, source_article_title, source_article_summary
            FROM memory_record
            WHERE user_id = #{userId} AND subject = #{subject} AND predicate = #{predicate}
              AND status = 'ACTIVE' AND is_deleted = 0
            ORDER BY version DESC
            LIMIT 1
            """)
    MemoryRecordPO selectActiveByUserSubjectPredicate(@Param("userId") Long userId,
                                                      @Param("subject") String subject,
                                                      @Param("predicate") String predicate);

    /** P1-4: CAS 抢占向量同步权（返回 0 = 已被抢占 / 未到重试时间） */
    @Update("""
            UPDATE memory_record
            SET vector_status = 'SYNCING'
            WHERE id = #{id} AND vector_status = 'PENDING'
              AND (next_retry_at IS NULL OR next_retry_at <= NOW())
            """)
    int claimVectorSync(@Param("id") Long id);

    /** P1-4: 失败重试（retry_count+1 + next_retry_at 退避 + last_error） */
    @Update("""
            UPDATE memory_record
            SET vector_status = #{status}, retry_count = IFNULL(retry_count, 0) + 1,
                next_retry_at = #{nextRetryAt}, last_error = #{lastError}
            WHERE id = #{id}
            """)
    int scheduleVectorRetry(@Param("id") Long id, @Param("status") String status,
                            @Param("nextRetryAt") LocalDateTime nextRetryAt,
                            @Param("lastError") String lastError);

    /** P1-4: 标记向量同步成功（清空退避与错误） */
    @Update("UPDATE memory_record SET vector_status = 'SYNCED', next_retry_at = NULL, last_error = NULL WHERE id = #{id}")
    int markVectorSynced(@Param("id") Long id);

    /** P1-4: 查询待删旧向量 */
    @Select("""
            SELECT id, user_id, type, content, content_hash, content_tokenized, source_session_id, importance, access_count, last_accessed_at, create_time, update_time, is_deleted, attributed_to, confidence, expire_time, subject, predicate, `value`, evidence, trace_id, operation, version, status, valid_from, valid_to, next_retry_at, last_error, source_article_id, source_article_title, source_article_summary
            FROM memory_record
            WHERE vector_status = 'DELETE_PENDING'
            """)
    List<MemoryRecordPO> selectVectorDeletePending();

    /** P3 治理: 软标记生命周期状态（先软标记后硬化，只改 status 不物理删除） */
    @Update("UPDATE memory_record SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);

    /**
     * P3 治理: 查询 ACTIVE 记忆（供重复聚类按 user_id+type 分组）。
     *
     * <p><b>{@code userId} 参数是安全边界，不是可选优化。</b>
     * 为 {@code null} 时扫全库（治理 job 的常规用法，它本就要处理所有用户）；
     * 非空时只扫该用户。<b>对外暴露的端点必须传它</b>——评测端点
     * {@code /api/v1/eval/governance/samples} 曾因为调用的是无参版本，
     * 把全库所有用户的记忆内容（含 {@code content}）返回给了调用方，
     * 而它自身那套「校验 evalUserId」的命名空间防护形同虚设。</p>
     */
    @Select("""
            <script>
            SELECT id, user_id, type, content, content_hash, content_tokenized, source_session_id, importance, access_count, last_accessed_at, create_time, update_time, is_deleted, attributed_to, confidence, expire_time, subject, predicate, `value`, evidence, trace_id, operation, version, status, valid_from, valid_to, next_retry_at, last_error, source_article_id, source_article_title, source_article_summary
            FROM memory_record
            WHERE status = 'ACTIVE' AND is_deleted = 0
            <if test="userId != null"> AND user_id = #{userId} </if>
            </script>
            """)
    List<MemoryRecordPO> selectActiveForDuplicateScan(@Param("userId") Long userId);

    /** P3 治理: 查询含 subject+predicate 的 ACTIVE 记忆（供事实一致性巡检聚合）。{@code userId} 语义同上。 */
    @Select("""
            <script>
            SELECT id, user_id, type, content, content_hash, content_tokenized, source_session_id, importance, access_count, last_accessed_at, create_time, update_time, is_deleted, attributed_to, confidence, expire_time, subject, predicate, `value`, evidence, trace_id, operation, version, status, valid_from, valid_to, next_retry_at, last_error, source_article_id, source_article_title, source_article_summary
            FROM memory_record
            WHERE status = 'ACTIVE' AND is_deleted = 0
              AND subject IS NOT NULL AND predicate IS NOT NULL
            <if test="userId != null"> AND user_id = #{userId} </if>
            </script>
            """)
    List<MemoryRecordPO> selectActiveForConsistencyScan(@Param("userId") Long userId);

    /** P3 治理: 扫描过期且 {@code last_accessed_at} 早于阈值时间的 ACTIVE 记忆（供软归档）。{@code userId} 语义同上。 */
    @Select("""
            <script>
            SELECT id, user_id, type, content, content_hash, content_tokenized, source_session_id, importance, access_count, last_accessed_at, create_time, update_time, is_deleted, attributed_to, confidence, expire_time, subject, predicate, `value`, evidence, trace_id, operation, version, status, valid_from, valid_to, next_retry_at, last_error, source_article_id, source_article_title, source_article_summary
            FROM memory_record
            WHERE status = 'ACTIVE' AND is_deleted = 0
              AND expire_time IS NOT NULL AND expire_time &lt; NOW()
              AND (last_accessed_at IS NULL OR last_accessed_at &lt; #{before})
            <if test="userId != null"> AND user_id = #{userId} </if>
            </script>
            """)
    List<MemoryRecordPO> selectExpiredForArchive(
            @Param("before") LocalDateTime before, @Param("userId") Long userId);

    /** P3 治理: 抽样 confidence 落在 [min,max] 灰色地带的 ACTIVE 记忆（供幻觉抽检）。{@code userId} 语义同上。 */
    @Select("""
            <script>
            SELECT id, user_id, type, content, content_hash, content_tokenized, source_session_id, importance, access_count, last_accessed_at, create_time, update_time, is_deleted, attributed_to, confidence, expire_time, subject, predicate, `value`, evidence, trace_id, operation, version, status, valid_from, valid_to, next_retry_at, last_error, source_article_id, source_article_title, source_article_summary
            FROM memory_record
            WHERE status = 'ACTIVE' AND is_deleted = 0
              AND confidence >= #{minConfidence} AND confidence &lt;= #{maxConfidence}
            <if test="userId != null"> AND user_id = #{userId} </if>
            ORDER BY RAND()
            LIMIT #{limit}
            </script>
            """)
    List<MemoryRecordPO> selectSampleForHallucinationCheck(@Param("minConfidence") double minConfidence,
                                                           @Param("maxConfidence") double maxConfidence,
                                                           @Param("limit") int limit,
                                                           @Param("userId") Long userId);
}
