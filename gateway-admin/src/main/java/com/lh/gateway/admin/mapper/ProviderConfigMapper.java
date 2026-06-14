package com.lh.gateway.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lh.gateway.model.ProviderConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * Provider 配置 Mapper
 */
@Mapper
public interface ProviderConfigMapper extends BaseMapper<ProviderConfig> {

    @Update("UPDATE provider_config SET enabled = #{enabled} WHERE name = #{name}")
    void updateEnabled(@Param("name") String name, @Param("enabled") boolean enabled);

    @Update("UPDATE provider_config SET weight = #{weight} WHERE name = #{name}")
    void updateWeight(@Param("name") String name, @Param("weight") int weight);
}
