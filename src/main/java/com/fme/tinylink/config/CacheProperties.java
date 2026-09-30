package com.fme.tinylink.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tinylink.cache")
public record CacheProperties(
    Duration urlTtl,
    Duration nullCacheTtl
) {}
