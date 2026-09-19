package com.fme.tinylink.dto;

import org.hibernate.validator.constraints.URL;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;


public record ShortenUrlRequest(
    
    @NotBlank(message = "URL cannot be blank")
    // 1. Checks protocol prefix specifically
    @Pattern(
        regexp = "^https?://.*", 
        message = "URL must start with http:// or https://"
    )
    // 2. Checks overall RFC URL validity (host, structure)
    @URL(message = "Invalid URL format")
    String longUrl

) {}
