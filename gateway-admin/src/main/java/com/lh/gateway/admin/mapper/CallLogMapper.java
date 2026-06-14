package com.lh.gateway.admin.mapper;

import com.lh.gateway.model.CallLog;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 调用日志 Mapper
 */
@Mapper
public interface CallLogMapper {

    /**
     * 条件查询
     */
    List<CallLog> queryLogs(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime,
            @Param("provider") String provider,
            @Param("model") String model,
            @Param("status") String status,
            @Param("offset") int offset,
            @Param("limit") int limit);

    /**
     * 插入日志
     */
    int insert(CallLog callLog);

    /**
     * 按天统计 Token 消耗
     */
    List<Map<String, Object>> getDailyTokenUsage(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);
}
