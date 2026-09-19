package com.fme.tinylink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.fme.tinylink.dto.ShortenUrlRequest;
import com.fme.tinylink.dto.ShortenUrlResponse;
import com.fme.tinylink.repository.UrlRepository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.HttpURLConnection;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Testcontainers
class UrlShortenControllerTest {
	
	@Container 
	@ServiceConnection 
	static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0");

	@Autowired 
	private UrlRepository urlRepository;

	@LocalServerPort 
	private int port;

	@Autowired
	private TestRestTemplate restTemplate;
	
	private String BASE_URL;

	private String BASE_API_URL;

	@BeforeEach
	void setup() {
		this.BASE_URL = "http://localhost:" + port;
		this.BASE_API_URL  = BASE_URL + "/api/v1";

		// Disable auto-following redirects so you can capture and assert 302 FOUND
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
			@Override
			protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
				super.prepareConnection(connection, httpMethod);
				connection.setInstanceFollowRedirects(false);
			}
		};
		
		restTemplate.getRestTemplate().setRequestFactory(requestFactory);
	}

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

}
