package com.lh.gateway.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.ReactiveRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

/**
 * Redis Bitmap 布隆过滤器。
 *
 * <p>位图保存在 Redis 中，所有网关实例共享，应用重启后仍然存在，不会再出现
 * 本地布隆过滤器为空而错误挡住 Redis 已有缓存的问题。布隆写入先于缓存值写入：
 * 即使缓存值写入失败也只会产生安全的假阳性，不会产生假阴性。</p>
 */
@Component
public class BloomFilterHelper {

    private static final Logger log = LoggerFactory.getLogger(BloomFilterHelper.class);
    private static final String BLOOM_KEY = "cache:llm:bloom:v2";
    private static final long BIT_SIZE = 10_000_000L;
    private static final int HASH_FUNCTIONS = 7;
    private static final RedisScript<Long> CHECK_SCRIPT = new DefaultRedisScript<>("""
            for i = 1, #ARGV do
                if redis.call('GETBIT', KEYS[1], ARGV[i]) == 0 then
                    return 0
                end
            end
            return 1
            """, Long.class);
    private static final RedisScript<Long> PUT_SCRIPT = new DefaultRedisScript<>("""
            for i = 1, #ARGV do
                redis.call('SETBIT', KEYS[1], ARGV[i], 1)
            end
            return 1
            """, Long.class);

    private final ReactiveRedisTemplate<String, String> redisTemplate;

    public BloomFilterHelper(
            @Qualifier("reactiveRedisTemplate") ReactiveRedisTemplate<String, String> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public Mono<Boolean> mightContain(String key) {
        return redisTemplate.execute(CHECK_SCRIPT, List.of(BLOOM_KEY), offsetArguments(key))
                .next()
                .map(result -> result == 1L)
                .onErrorResume(error -> {
                    // 布隆过滤器不可用时必须放行到 Redis，不能制造缓存假阴性。
                    log.warn("Bloom filter read failed, fallback to Redis lookup: {}", error.getMessage());
                    return Mono.just(true);
                });
    }

    public Mono<Void> put(String key) {
        return redisTemplate.execute(PUT_SCRIPT, List.of(BLOOM_KEY), offsetArguments(key))
                .then();
    }

    private List<String> offsetArguments(String key) {
        return offsets(key).stream().map(String::valueOf).toList();
    }

    private List<Long> offsets(String key) {
        byte[] digest = sha256(key);
        ByteBuffer buffer = ByteBuffer.wrap(digest);
        long hash1 = buffer.getLong();
        long hash2 = buffer.getLong();
        if (hash2 == 0) {
            hash2 = 0x9E3779B97F4A7C15L;
        }

        List<Long> offsets = new ArrayList<>(HASH_FUNCTIONS);
        for (int i = 0; i < HASH_FUNCTIONS; i++) {
            offsets.add(Math.floorMod(hash1 + i * hash2, BIT_SIZE));
        }
        return offsets;
    }

    private byte[] sha256(String key) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
