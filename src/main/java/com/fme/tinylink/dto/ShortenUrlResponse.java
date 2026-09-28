package com.fme.tinylink.dto;

public record ShortenUrlResponse(
    String shortUrl,
    String longUrl
) {}
