package com.fme.tinylink.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.fme.tinylink.AbstractIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

class RootControllerTest extends AbstractIntegrationTest {

	@Test
	void rootRedirectsToSwaggerTest() {
		ResponseEntity<Void> response = restTemplate.getForEntity(BASE_URL + "/", Void.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
		assertThat(response.getHeaders().getLocation()).isNotNull();
		assertThat(response.getHeaders().getLocation().getPath()).isEqualTo("/swagger-ui.html");
	}

	@Test
	void nonExistentRouteReturnsNotFoundTest() {
		ResponseEntity<String> response = restTemplate.getForEntity(BASE_URL + "/api/v1/non-existent-route", String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void methodNotAllowedOnRootTest() {
		ResponseEntity<String> response = restTemplate.postForEntity(BASE_URL + "/", null, String.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
		assertThat(response.getBody()).contains("Method Not Allowed");
	}
}
