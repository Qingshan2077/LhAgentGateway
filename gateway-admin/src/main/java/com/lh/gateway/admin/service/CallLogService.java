package com.lh.gateway.admin.service;

import com.lh.gateway.model.CallLog;
import com.lh.gateway.admin.mapper.CallLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 调用日志服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CallLogService {

    private final CallLogMapper callLogMapper;

    /**
     * 条件查询调用日志
     */
    public List<CallLog> queryLogs(LocalDateTime startTime, LocalDateTime endTime,
                                    String provider, String model, String status,
                                    int page, int size) {
        // 强制时间范围限制（防止全表扫描）
        if (startTime == null) startTime = LocalDateTime.now().minusDays(7);
        if (endTime == null) endTime = LocalDateTime.now();

        int offset = (page - 1) * size;
        return callLogMapper.queryLogs(startTime, endTime, provider, model, status, offset, size);
    }

    /**
     * 成本统计概览
     */
    public Map<String, Object> getCostSummary(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null) startTime = LocalDateTime.now().minusDays(7);
        if (endTime == null) endTime = LocalDateTime.now();

        List<CallLog> logs = callLogMapper.queryLogs(startTime, endTime, null, null, null, 0, Integer.MAX_VALUE);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("totalCalls", logs.size());
        summary.put("totalTokens", logs.stream().mapToInt(CallLog::getTotalTokens).sum());
        summary.put("totalCost", logs.stream()
                .map(l -> l.getCostUsd() != null ? l.getCostUsd() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        summary.put("avgLatencyMs", logs.stream()
                .mapToInt(CallLog::getLatencyMs)
                .average()
                .orElse(0));
        summary.put("successCount", logs.stream().filter(l -> "success".equals(l.getStatus())).count());
        summary.put("failCount", logs.stream().filter(l -> !"success".equals(l.getStatus())).count());

        return summary;
    }

    /**
     * 按 Provider 分组统计
     */
    public List<Map<String, Object>> groupByProvider(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null) startTime = LocalDateTime.now().minusDays(7);
        if (endTime == null) endTime = LocalDateTime.now();

        List<CallLog> logs = callLogMapper.queryLogs(startTime, endTime, null, null, null, 0, Integer.MAX_VALUE);

        return logs.stream()
                .collect(Collectors.groupingBy(CallLog::getProvider))
                .entrySet().stream()
                .map(entry -> {
                    String provider = entry.getKey();
                    List<CallLog> providerLogs = entry.getValue();
                    Map<String, Object> stat = new LinkedHashMap<>();
                    stat.put("provider", provider);
                    stat.put("callCount", providerLogs.size());
                    stat.put("totalTokens", providerLogs.stream().mapToInt(CallLog::getTotalTokens).sum());
                    stat.put("totalCost", providerLogs.stream()
                            .map(l -> l.getCostUsd() != null ? l.getCostUsd() : BigDecimal.ZERO)
                            .reduce(BigDecimal.ZERO, BigDecimal::add));
                    stat.put("avgLatencyMs", providerLogs.stream()
                            .mapToInt(CallLog::getLatencyMs)
                            .average()
                            .orElse(0));
                    return stat;
                })
                .collect(Collectors.toList());
    }

    /**
     * 按天统计 Token 消耗
     */
    public List<Map<String, Object>> getDailyTokenUsage(LocalDateTime startTime, LocalDateTime endTime) {
        if (startTime == null) startTime = LocalDateTime.now().minusDays(30);
        if (endTime == null) endTime = LocalDateTime.now();

        return callLogMapper.getDailyTokenUsage(startTime, endTime);
    }

    /**
     * 插入单条调用日志
     */
    public void insertLog(CallLog callLog) {
        callLogMapper.insert(callLog);
    }
}
