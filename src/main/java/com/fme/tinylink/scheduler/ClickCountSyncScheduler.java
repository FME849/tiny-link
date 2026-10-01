package com.fme.tinylink.scheduler;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.fme.tinylink.repository.UrlRepository;
import com.fme.tinylink.services.UrlShortenService;

@Component
public class ClickCountSyncScheduler {
    
    private static final Logger log = LoggerFactory.getLogger(ClickCountSyncScheduler.class);

    private StringRedisTemplate redisTemplate;
    private UrlRepository repository;

    public ClickCountSyncScheduler(StringRedisTemplate redisTemplate, UrlRepository repository) {
        this.redisTemplate = redisTemplate;
        this.repository = repository;
    }

    @Scheduled(fixedRate = 30000)
    @Transactional
    public void syncClicksToDatabase() {
        Set<String> keys = redisTemplate.keys(UrlShortenService.PREFIX_CLICK + "*");
        if (keys == null || keys.isEmpty()) return;

        for (String key : keys) {
            String shortCode = key.replace(UrlShortenService.PREFIX_CLICK, "");
            String countStr = redisTemplate.opsForValue().getAndDelete(key);
            if (countStr != null) {
                int delta = Integer.parseInt(countStr);
                if (delta > 0) {
                    repository.increaseCountByDelta(shortCode, delta);
                }
            }
        }
        log.info("Synced {} active click counters from Redis to MySQL", keys.size());
    }
}
