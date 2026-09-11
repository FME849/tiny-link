package com.fme.tinylink.controllers;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.fme.tinylink.dto.ShortenUrlRequest;
import com.fme.tinylink.dto.ShortenUrlResponse;
import com.fme.tinylink.models.UrlData;
import com.fme.tinylink.services.UrlShortenService;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
public class UrlShortenController {
    
    private UrlShortenService services;

    public UrlShortenController(UrlShortenService services) {
        this.services = services;
    }

    @PostMapping("/api/v1/shorten")
    public ResponseEntity<ShortenUrlResponse> createShortUrl(@RequestBody ShortenUrlRequest request) {
        UrlData entity = services.createShortUrl(request.longUrl());
        ShortenUrlResponse response = new ShortenUrlResponse(
            entity.getId(),
            entity.getShortCode(),
            entity.getLongURL()
        );
        URI location = ServletUriComponentsBuilder
                            .fromCurrentContextPath()
                            .path("/{shortenCode}")
                            .buildAndExpand(entity.getShortCode())
                            .toUri();
        return ResponseEntity
                .created(location)
                .body(response);
    }
    

}
