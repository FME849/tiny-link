package com.fme.tinylink.service;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.fme.tinylink.config.CacheProperties;
import com.fme.tinylink.exception.ResourceNotFoundException;
import com.fme.tinylink.models.UrlMapping;
import com.fme.tinylink.repository.UrlRepository;
import com.fme.tinylink.services.UrlShortenService;
import com.fme.tinylink.snowflake.SnowflakeIdGenerator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
public class UrlShortenServiceTest {
    
    @Mock
    private UrlRepository repository;

    @Mock
    private SnowflakeIdGenerator idGenerator;

    @Mock
    private StringRedisTemplate redisTemplate;
    
    @Mock
    private ValueOperations<String, String> valueOperations;
    
    private final CacheProperties cacheProperties = new CacheProperties(
        Duration.ofDays(7),
        Duration.ofMinutes(5)
    );
    
    private UrlShortenService service;
    
    @BeforeEach
    void setup() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        service = new UrlShortenService(repository, idGenerator, redisTemplate, cacheProperties);
    }

    @Test
    void getLongUrl_whenCacheHit_shouldNotQueryDb() {
        String shortCode = "abc123";
        String longUrl = "https://example.com";
        when(valueOperations.get("url:" + shortCode)).thenReturn(longUrl);

        String result = service.getLongUrlByShortCode(shortCode);

        assertThat(result).isEqualTo(longUrl);
        verify(valueOperations).increment("clicks:" + shortCode);
        verify(repository, never()).findByShortCode(anyString());
    }

    @Test
    void getLongUrl_whenCacheMiss_shouldQueryDb_andPopulateRedis() {
        String shortCode = "abc123";
        String longUrl = "https://example.com";
        when(valueOperations.get("url:" + shortCode)).thenReturn(null);
        when(repository.findByShortCode(shortCode)).thenReturn(Optional.of(new UrlMapping(shortCode, longUrl)));

        String result = service.getLongUrlByShortCode(shortCode);

        assertThat(result).isEqualTo(longUrl);
        verify(valueOperations).set(eq("url:" + shortCode), eq(longUrl), eq(cacheProperties.urlTtl()));
        verify(valueOperations).increment("clicks:" + shortCode);
        verify(repository).findByShortCode(shortCode);
    }

    @Test 
    void getLongUrl_whenNotFoundInDb_shouldCacheSentinel() {
        String shortCode = "abc1223";
        when(valueOperations.get(UrlShortenService.PREFIX_URL + shortCode)).thenReturn(null);
        when(repository.findByShortCode(shortCode)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getLongUrlByShortCode(shortCode)).isInstanceOf(ResourceNotFoundException.class);
        verify(valueOperations).set(eq(UrlShortenService.PREFIX_URL + shortCode), eq(UrlShortenService.NOT_FOUND_SENTINEL), eq(cacheProperties.nullCacheTtl()));
    }
}
