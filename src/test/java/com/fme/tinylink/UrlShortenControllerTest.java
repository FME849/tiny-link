package com.fme.tinylink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import com.fme.tinylink.dto.ShortenUrlRequest;
import com.fme.tinylink.dto.ShortenUrlResponse;
import com.fme.tinylink.repository.UrlRepository;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class UrlShortenControllerTest {
	
	@Autowired 
	private UrlRepository urlRepository;

	@LocalServerPort 
	private int port;

	private RestTemplate restTemplate = new RestTemplate();
	
	private String BASE_URL;

	private String BASE_API_URL;

	@BeforeEach
	void setup() {
		this.BASE_URL = "http://localhost:" + port;
		this.BASE_API_URL  = BASE_URL + "/api/v1";
	}

	@AfterEach
	void cleanup() {
		urlRepository.deleteAll();
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

	@Test
	void missingOrIncorrectFormatLongUrlTest() {
		String longUrl = "";
		ShortenUrlRequest body = new ShortenUrlRequest(longUrl);
		ResponseEntity<ShortenUrlResponse> postEntity = restTemplate.postForEntity(BASE_API_URL + "/url", body, ShortenUrlResponse.class);

		assertThat(postEntity.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(postEntity.getHeaders().getLocation()).isNull();
		assertThat(postEntity.getBody()).isNull();
	}

}
