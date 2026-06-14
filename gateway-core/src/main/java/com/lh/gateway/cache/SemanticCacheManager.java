package com.lh.gateway.cache;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 语义缓存管理器（进阶功能 — 预留接口）
 *
 * <p>语义相近的 Prompt 复用缓存结果。
 * 第一版只留接口，具体实现需要引入 ONNX Runtime + Embedding 模型。</p>
 *
 * <p>实现方式（选做）：</p>
 * <ol>
 *   <li>引入 ONNX Runtime 依赖</li>
 *   <li>加载 Embedding 模型（如 BAAI/bge-small-zh-v1.5）</li>
 *   <li>将请求文本转为向量</li>
 *   <li>与缓存中的向量做余弦相似度比较（>0.95 命中）</li>
 * </ol>
 */
@Slf4j
@Component
public class SemanticCacheManager {

    /**
     * 语义缓存阈值
     */
    private static final double SIMILARITY_THRESHOLD = 0.95;

    /**
     * 预留：根据语义查找缓存
     */
    public String findSimilar(String text) {
        // TODO: 实现语义缓存
        // 1. 调用 Embedding 服务将 text 转为向量
        // 2. 在 Redis Search 或本地向量索引中查找余弦相似度 > 0.95 的缓存
        // 3. 返回命中的缓存 Key
        return null;
    }

    /**
     * 预留：写入语义缓存索引
     */
    public void index(String text, String cacheKey) {
        // TODO: 将 text 的向量和 cacheKey 写入索引
        log.debug("Semantic cache index: text={}, key={}", text.substring(0, Math.min(50, text.length())), cacheKey);
    }
}
