package com.lh.gateway.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 管理后台启动入口
 *
 * <p>运行在独立端口（8081），提供 Provider 配置管理、
 * 调用日志查询、成本统计等管理功能。</p>
 */
@SpringBootApplication
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
