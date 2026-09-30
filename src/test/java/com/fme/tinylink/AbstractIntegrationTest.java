package com.fme.tinylink;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.net.HttpURLConnection;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
public abstract class AbstractIntegrationTest {

    @ServiceConnection(name = "mysql")
    protected static final MySQLContainer<?> mysql;

    @ServiceConnection(name = "redis")
    protected static final GenericContainer<?> redis;

    static {
        mysql = new MySQLContainer<>("mysql:8.0");
        mysql.start();

        redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"));
        redis.withExposedPorts(6379);
        redis.start();
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate restTemplate;

    protected String BASE_URL;
    protected String BASE_API_URL;

    @BeforeEach
    void baseSetup() {
        this.BASE_URL = "http://localhost:" + port;
        this.BASE_API_URL = BASE_URL + "/api/v1";

        // Disable auto-following redirects so tests can capture and assert 302 FOUND
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory() {
            @Override
            protected void prepareConnection(HttpURLConnection connection, String httpMethod) throws IOException {
                super.prepareConnection(connection, httpMethod);
                connection.setInstanceFollowRedirects(false);
            }
        };

        restTemplate.getRestTemplate().setRequestFactory(requestFactory);
    }
}
