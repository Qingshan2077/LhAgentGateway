package com.lh.gateway.mq;

/**
 * 队列名称常量
 */
public final class QueueNames {

    private QueueNames() {}

    /** 调用日志队列 */
    public static final String LLM_CALL_LOG = "llm.call.log";

    /** 调用日志延迟队列（用于重试处理） */
    public static final String LLM_CALL_LOG_DLQ = "llm.call.log.dlq";

    /** 成本统计队列 */
    public static final String LLM_COST_STATS = "llm.cost.stats";

    /** Topic Exchange 名称 */
    public static final String LLM_EXCHANGE = "llm.exchange";

    /** Routing Key 前缀 */
    public static final String ROUTING_KEY_LOG = "log.";
    public static final String ROUTING_KEY_COST = "cost.";
}
