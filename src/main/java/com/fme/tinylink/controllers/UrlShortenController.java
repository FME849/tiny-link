package com.fme.tinylink.controllers;

import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.fme.tinylink.dto.ShortenUrlRequest;
import com.fme.tinylink.dto.ShortenUrlResponse;
import com.fme.tinylink.models.UrlData;
import com.fme.tinylink.services.UrlShortenService;

import jakarta.validation.Valid;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
public class UrlShortenController {
    
    private UrlShortenService services;

    public UrlShortenController(UrlShortenService services) {
        this.services = services;
    }

    @PostMapping("/api/v1/url")
    public ResponseEntity<ShortenUrlResponse> createShortUrl(@Valid @RequestBody ShortenUrlRequest request) {
        UrlData entity = services.createShortUrl(request.longUrl());
        URI location = ServletUriComponentsBuilder
            .fromCurrentContextPath()
            .path("/{shortenCode}")
            .buildAndExpand(entity.getShortCode())
            .toUri();
        ShortenUrlResponse response = new ShortenUrlResponse(
            entity.getId(),
            location.toString(),
            entity.getLongURL()
        );
        return ResponseEntity
                .created(location)
                .body(response);
    }
    
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirectToUrl(@PathVariable String shortCode) {
        UrlData entity = services.getDataByShortCode(shortCode);
        return ResponseEntity
                .status(HttpStatus.FOUND)
                .header("Location", entity.getLongURL())
                .build();
    }
}
