package com.fme.tinylink.services;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import com.fme.tinylink.models.UrlMapping;
import com.fme.tinylink.repository.UrlRepository;
import com.fme.tinylink.snowflake.SnowflakeIdGenerator;

import com.fme.tinylink.base62.Base62Encoder;
import com.fme.tinylink.config.CacheProperties;
import com.fme.tinylink.exception.ResourceNotFoundException;

@Service
public class UrlShortenService {
    private UrlRepository repository;
    private SnowflakeIdGenerator idGenerator;
    private StringRedisTemplate redisTemplate;
    private CacheProperties cacheProperties;

    public static final String PREFIX_URL = "url:";
    public static final String PREFIX_CLICK = "clicks:";
    public static final String NOT_FOUND_SENTINEL = "$$NOT_FOUND$$";

    public UrlShortenService(
        UrlRepository repository,
        SnowflakeIdGenerator idGenerator,
        StringRedisTemplate redisTemplate,
        CacheProperties cacheProperties
    ) {
        this.repository = repository;
        this.idGenerator = idGenerator;
        this.redisTemplate = redisTemplate;
        this.cacheProperties = cacheProperties;
    }

    public UrlMapping createShortUrl(String longUrl) {
        idGenerator.setDataCenterId(1);
        idGenerator.setWorkerId(1);
        Long nextId = idGenerator.nextId();
        String shortCode = Base62Encoder.encode(nextId);
        UrlMapping model = new UrlMapping(shortCode, longUrl);
        return repository.save(model);
    }

    public String getLongUrlByShortCode(String shortCode) {
        String redisKey = PREFIX_URL + shortCode;
        String cacheLongUrl = redisTemplate.opsForValue().get(redisKey);

        if (cacheLongUrl != null) {
            if (NOT_FOUND_SENTINEL.equals(cacheLongUrl)) {
                throw new ResourceNotFoundException("Short code not found");
            }

            redisTemplate.opsForValue().increment(PREFIX_CLICK + shortCode);
            return cacheLongUrl;
        }

        UrlMapping mapping = repository.findByShortCode(shortCode).orElse(null);
        
        if (mapping == null) {
            redisTemplate.opsForValue().set(redisKey, NOT_FOUND_SENTINEL, cacheProperties.nullCacheTtl());
            throw new ResourceNotFoundException("Short code not found");
        }

        redisTemplate.opsForValue().set(redisKey, mapping.getLongURL(), cacheProperties.urlTtl());
        redisTemplate.opsForValue().increment(PREFIX_CLICK + shortCode);
        return mapping.getLongURL();
    }
}
