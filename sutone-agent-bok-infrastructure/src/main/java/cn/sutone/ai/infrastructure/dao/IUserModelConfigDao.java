package cn.sutone.ai.infrastructure.dao;

import cn.sutone.ai.infrastructure.dao.po.UserModelConfigPO;
import org.apache.ibatis.annotations.*;

import java.util.List;

/**
 * 用户模型配置 DAO
 */
@Mapper
public interface IUserModelConfigDao {

    @Select("SELECT id, user_id, config_name, provider, base_url, api_key_cipher, "
            + "model_name, completions_path, is_default, is_enabled, key_version, create_time, update_time "
            + "FROM user_model_config "
            + "WHERE user_id = #{userId} AND is_default = 1 AND is_enabled = 1 LIMIT 1")
    UserModelConfigPO selectDefaultByUserId(@Param("userId") Long userId);

    @Select("SELECT id, user_id, config_name, provider, base_url, api_key_cipher, "
            + "model_name, completions_path, is_default, is_enabled, key_version, create_time, update_time "
            + "FROM user_model_config WHERE id = #{id}")
    UserModelConfigPO selectById(@Param("id") Long id);

    @Select("SELECT id, user_id, config_name, provider, base_url, api_key_cipher, "
            + "model_name, completions_path, is_default, is_enabled, key_version, create_time, update_time "
            + "FROM user_model_config WHERE user_id = #{userId} ORDER BY create_time DESC")
    List<UserModelConfigPO> selectByUserId(@Param("userId") Long userId);

    @Options(useGeneratedKeys = true, keyProperty = "id")
    @Insert("INSERT INTO user_model_config(user_id, config_name, provider, base_url, api_key_cipher, "
            + "model_name, completions_path, is_default, is_enabled, key_version, create_time, update_time) "
            + "VALUES(#{userId}, #{configName}, #{provider}, #{baseUrl}, #{apiKeyCipher}, "
            + "#{modelName}, #{completionsPath}, #{isDefault}, #{isEnabled}, #{keyVersion}, NOW(), NOW())")
    int insert(UserModelConfigPO po);

    @Update("UPDATE user_model_config SET config_name = #{configName}, base_url = #{baseUrl}, "
            + "api_key_cipher = #{apiKeyCipher}, model_name = #{modelName}, "
            + "completions_path = #{completionsPath}, is_default = #{isDefault}, "
            + "is_enabled = #{isEnabled}, update_time = NOW() WHERE id = #{id} AND user_id = #{userId}")
    int update(UserModelConfigPO po);

    @Delete("DELETE FROM user_model_config WHERE id = #{id} AND user_id = #{userId}")
    int delete(@Param("id") Long id, @Param("userId") Long userId);
}
