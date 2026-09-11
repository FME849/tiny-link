package com.fme.tinylink.dto;

public record ShortenUrlResponse(
    Long id,
    String shortUrl,
    String longUrl
) {}
