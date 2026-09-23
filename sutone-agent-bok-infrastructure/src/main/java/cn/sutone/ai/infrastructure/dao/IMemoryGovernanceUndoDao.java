package cn.sutone.ai.infrastructure.dao;

import cn.sutone.ai.infrastructure.dao.po.MemoryGovernanceUndoItemPO;
import cn.sutone.ai.infrastructure.dao.po.MemoryGovernanceUndoPO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 记忆治理动作撤销记录 DAO（P3-5），对应 18-sutone-agent-bok-phase18-governance-undo.sql 的两张表：
 * 主表 {@code memory_governance_undo} + 子表 {@code memory_governance_undo_item}（逐行存储，不用 JSON）。
 */
@Mapper
public interface IMemoryGovernanceUndoDao {

    @Insert("""
            INSERT INTO memory_governance_undo(action, merged_into_id, operator)
            VALUES(#{action}, #{mergedIntoId}, #{operator})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    int insert(MemoryGovernanceUndoPO po);

    @Insert("""
            INSERT INTO memory_governance_undo_item(undo_id, memory_id, before_status)
            VALUES(#{undoId}, #{memoryId}, #{beforeStatus})
            """)
    int insertItem(MemoryGovernanceUndoItemPO item);

    @Select("""
            SELECT id, action, merged_into_id, operator, created_at, reverted_at
            FROM memory_governance_undo
            WHERE id = #{id}
            """)
    MemoryGovernanceUndoPO selectById(@Param("id") Long id);

    @Select("""
            SELECT id, undo_id, memory_id, before_status
            FROM memory_governance_undo_item
            WHERE undo_id = #{undoId}
            ORDER BY id ASC
            """)
    List<MemoryGovernanceUndoItemPO> selectItemsByUndoId(@Param("undoId") Long undoId);

    @Update("""
            UPDATE memory_governance_undo
            SET reverted_at = NOW()
            WHERE id = #{id} AND reverted_at IS NULL
            """)
    int markReverted(@Param("id") Long id);
}
