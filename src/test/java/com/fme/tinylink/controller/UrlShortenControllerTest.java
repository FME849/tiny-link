package com.fme.tinylink.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fme.tinylink.AbstractIntegrationTest;
import com.fme.tinylink.dto.ShortenUrlRequest;
import com.fme.tinylink.dto.ShortenUrlResponse;
import com.fme.tinylink.repository.UrlRepository;
import com.fme.tinylink.services.UrlShortenService;

import static org.assertj.core.api.Assertions.assertThat;

class UrlShortenControllerTest extends AbstractIntegrationTest {
	
	@Autowired 
	private UrlRepository urlRepository;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@AfterEach
	void cleanup() {
		urlRepository.deleteAllInBatch();
	}

	@Test
	void createAndRetrieveShortenUrlTest() {
		String longUrl = "https://example.com";
		ShortenUrlRequest body = new ShortenUrlRequest(longUrl);
		ResponseEntity<ShortenUrlResponse> postEntity = restTemplate.postForEntity(BASE_API_URL + "/url", body, ShortenUrlResponse.class);

		assertThat(postEntity.getStatusCode()).isEqualTo(HttpStatus.CREATED);
		assertThat(postEntity.getHeaders().getLocation()).isNotNull(); // Make sure to assert correctly instead of throwing null exception
		assertThat(postEntity.getBody()).isNotNull();
		assertThat(postEntity.getHeaders().getLocation().toString()).isEqualTo(postEntity.getBody().shortUrl());
		assertThat(postEntity.getBody().longUrl()).isEqualTo(longUrl);
		
		ResponseEntity<Void> getEntity = restTemplate.getForEntity(postEntity.getBody().shortUrl(), Void.class);
		
		assertThat(getEntity.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(getEntity.getHeaders().getLocation()).isNotNull();
		assertThat(getEntity.getHeaders().getLocation().toString()).isEqualTo(longUrl);
	}

	@ParameterizedTest
	@NullAndEmptySource 
	@ValueSource(strings = {
		" ",
		"example.com",
		"ht://example.com",
		"ftp://example.com",
		"http://",
		"random-string"
	})
	void invalidLongUrlTest(String longUrl) {
		ShortenUrlRequest body = new ShortenUrlRequest(longUrl);
		ResponseEntity<ShortenUrlResponse> postEntity = restTemplate.postForEntity(BASE_API_URL + "/url", body, ShortenUrlResponse.class);

		assertThat(postEntity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(postEntity.getHeaders().getLocation()).isNull();
	}

	@Test
	void shouldCacheInRedisAndIncrementCounterOnRedirect() {
		String longUrl = "https://example.com";
		ShortenUrlRequest body = new ShortenUrlRequest(longUrl);
		ResponseEntity<ShortenUrlResponse> postRes = restTemplate.postForEntity(BASE_API_URL + "/url", body, ShortenUrlResponse.class);
		String shortUrl = postRes.getBody().shortUrl();
		String shortCode = shortUrl.substring(shortUrl.lastIndexOf("/") + 1);

		String cachedUrl = redisTemplate.opsForValue().get(UrlShortenService.PREFIX_URL + shortCode);
		assertThat(cachedUrl).isEqualTo(longUrl);

		restTemplate.getForEntity(shortUrl, Void.class);
		restTemplate.getForEntity(shortUrl, Void.class);

		String clickCount = redisTemplate.opsForValue().get(UrlShortenService.PREFIX_CLICK + shortCode);
		assertThat(clickCount).isEqualTo("2");
	}
}
