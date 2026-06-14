package com.lh.gateway.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 定时任务配置
 *
 * <p>启用 @Scheduled 注解支持，用于：
 * <ul>
 *   <li>Provider 健康检查（30s 一次）</li>
 *   <li>熔断器状态检查</li>
 * </ul>
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
