package com.fme.tinylink;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

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
}
