package com.lh.gateway.admin.controller;

import com.lh.gateway.model.CallLog;
import com.lh.gateway.admin.service.CallLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 调用日志查询接口
 */
@RestController
@RequestMapping("/api/admin/logs")
@RequiredArgsConstructor
public class CallLogController {

    private final CallLogService callLogService;

    /**
     * 查询调用日志（按时间范围 + 筛选条件）
     */
    @GetMapping
    public ResponseEntity<List<CallLog>> queryLogs(
            @RequestParam("startTime") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam("endTime") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(value = "provider", required = false) String provider,
            @RequestParam(value = "model", required = false) String model,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {

        List<CallLog> logs = callLogService.queryLogs(startTime, endTime, provider, model, status, page, size);
        return ResponseEntity.ok(logs);
    }

    /**
     * 获取成本统计概览
     */
    @GetMapping("/cost-summary")
    public ResponseEntity<Map<String, Object>> getCostSummary(
            @RequestParam("startTime") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam("endTime") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {

        Map<String, Object> summary = callLogService.getCostSummary(startTime, endTime);
        return ResponseEntity.ok(summary);
    }

    /**
     * 按 Provider 分组统计
     */
    @GetMapping("/by-provider")
    public ResponseEntity<List<Map<String, Object>>> groupByProvider(
            @RequestParam("startTime") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam("endTime") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {

        List<Map<String, Object>> stats = callLogService.groupByProvider(startTime, endTime);
        return ResponseEntity.ok(stats);
    }

    /**
     * 按天统计 Token 消耗趋势
     */
    @GetMapping("/daily-tokens")
    public ResponseEntity<List<Map<String, Object>>>

    getDailyTokenUsage(
            @RequestParam("startTime") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam("endTime") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {

        List<Map<String, Object>> stats = callLogService.getDailyTokenUsage(startTime, endTime);
        return ResponseEntity.ok(stats);
    }
}
