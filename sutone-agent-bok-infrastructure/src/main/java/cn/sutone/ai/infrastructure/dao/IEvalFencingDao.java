package cn.sutone.ai.infrastructure.dao;

import cn.sutone.ai.infrastructure.dao.po.EvalFencingPO;
import org.apache.ibatis.annotations.*;

/**
 * 评测 fencing 权威态 DAO。
 *
 * <p>{@link #selectForUpdate} 为事务内锁定读（acquire 读改写用），其余为普通读写。</p>
 */
@Mapper
public interface IEvalFencingDao {

    @Select("""
            SELECT eval_user_id, fencing_version, active_run_id, updated_at
            FROM eval_fencing
            WHERE eval_user_id = #{evalUserId}
            FOR UPDATE
            """)
    EvalFencingPO selectForUpdate(@Param("evalUserId") Long evalUserId);

    @Select("""
            SELECT eval_user_id, fencing_version, active_run_id, updated_at
            FROM eval_fencing
            WHERE eval_user_id = #{evalUserId}
            """)
    EvalFencingPO select(@Param("evalUserId") Long evalUserId);

    @Insert("""
            INSERT INTO eval_fencing(eval_user_id, fencing_version, active_run_id)
            VALUES(#{evalUserId}, #{fencingVersion}, #{activeRunId})
            """)
    int insert(@Param("evalUserId") Long evalUserId,
               @Param("fencingVersion") long fencingVersion,
               @Param("activeRunId") String activeRunId);

    /** 版本 +1 并写入新 active_run_id（调用方须已持 FOR UPDATE 锁） */
    @Update("""
            UPDATE eval_fencing
            SET fencing_version = fencing_version + 1, active_run_id = #{activeRunId}
            WHERE eval_user_id = #{evalUserId}
            """)
    int incrementAndSetRun(@Param("evalUserId") Long evalUserId, @Param("activeRunId") String activeRunId);

    /** release：仅当 active_run_id 匹配时才清空（CAS），返回受影响行数 */
    @Update("""
            UPDATE eval_fencing
            SET active_run_id = NULL
            WHERE eval_user_id = #{evalUserId} AND active_run_id = #{activeRunId}
            """)
    int release(@Param("evalUserId") Long evalUserId, @Param("activeRunId") String activeRunId);
}
