package com.fme.tinylink.services;

import org.springframework.stereotype.Service;

import com.fme.tinylink.models.UrlData;
import com.fme.tinylink.repository.UrlRepository;
import com.fme.tinylink.snowflake.SnowflakeIdGenerator;

import com.fme.tinylink.base62.Base62Encoder;
import com.fme.tinylink.exception.ResourceNotFoundException;

@Service
public class UrlShortenService {
    private UrlRepository repository;
    private SnowflakeIdGenerator idGenerator;

    public UrlShortenService(UrlRepository repository, SnowflakeIdGenerator idGenerator) {
        this.repository = repository;
        this.idGenerator = idGenerator;
    }

    public UrlData createShortUrl(String longUrl) {
        idGenerator.setDataCenterId(1);
        idGenerator.setWorkerId(1);
        Long nextId = idGenerator.nextId();
        String shortCode = Base62Encoder.encode(nextId);
        UrlData model = new UrlData(shortCode, longUrl);
        return repository.save(model);
    }

    public UrlData getDataByShortCode(String shortCode) {
        return repository.findByShortCode(shortCode)
                .orElseThrow(() -> new ResourceNotFoundException("Short code not found"));
    }
}
