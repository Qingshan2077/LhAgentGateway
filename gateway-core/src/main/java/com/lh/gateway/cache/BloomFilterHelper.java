package com.lh.gateway.cache;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class BloomFilterHelper {

    private static final Logger log = LoggerFactory.getLogger(BloomFilterHelper.class);
    private BloomFilter<CharSequence> bloomFilter;

    @PostConstruct
    public void init() {
        this.bloomFilter = BloomFilter.create(
                Funnels.stringFunnel(StandardCharsets.UTF_8), 100_000, 0.01);
        log.info("BloomFilter initialized");
    }

    public boolean mightContain(String key) { return bloomFilter.mightContain(key); }
    public void put(String key) { bloomFilter.put(key); }
}
