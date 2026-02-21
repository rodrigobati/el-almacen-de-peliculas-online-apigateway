package com.videoclub.apigateway;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class GatewayCatalogE2EContractTest {

    @Autowired
    WebTestClient webTestClient;

    @TestConfiguration
    static class TestConfig {

        @Bean
        MockWebServer mockWebServer() {
            MockWebServer server = new MockWebServer();
            try {
                server.start();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return server;
        }

        @Bean
        RouteLocator testRoutes(RouteLocatorBuilder builder, MockWebServer mockWebServer) {
            String uri = mockWebServer.url("").toString();
            return builder.routes()
                    // public catalog - strip /api
                    .route("catalogo", r -> r.path("/api/peliculas/**")
                            .filters(f -> f.stripPrefix(1))
                            .uri(uri))
                    // admin catalog - preserve /api/admin/**
                    .route("catalogo-admin", r -> r.path("/api/admin/**")
                            .uri(uri))
                    .build();
        }

        // Disable security enforcement in tests; we only assert forwarding of
        // Authorization header
        @Bean
        @Primary
        SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
            http.csrf().disable();
            http.authorizeExchange().anyExchange().permitAll();
            return http.build();
        }
    }

    @BeforeAll
    static void beforeAll(@Autowired MockWebServer mockWebServer) {
        // ensure MockWebServer queue is empty
        mockWebServer.enqueue(new MockResponse().setResponseCode(200));
        try {
            mockWebServer.takeRequest();
        } catch (InterruptedException ignored) {
        }
    }

    @AfterAll
    static void afterAll(@Autowired MockWebServer mockWebServer) throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    @DisplayName("publicCatalog_pathRewritten_stripPrefixApplied")
    void publicCatalog_pathRewritten_stripPrefixApplied(@Autowired MockWebServer mockWebServer) {
        String pageResponse = "{\"items\":[],\"total\":0,\"totalPages\":0,\"page\":0,\"size\":12}";

        // stub upstream expecting /peliculas (stripPrefix=1 applied)
        MockWebServer server = mockWebServer;
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(pageResponse));

        webTestClient.get()
                .uri("/api/peliculas?page=0&size=12&sort=titulo&asc=true")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType("application/json")
                .expectBody().jsonPath("$.totalPages").isEqualTo(0);

        try {
            RecordedRequest req = server.takeRequest();
            assert req.getPath().startsWith("/peliculas");
            // ensure query preserved
            assert req.getRequestUrl().queryParameter("page").equals("0");
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("adminCatalog_pathPreserved_noStripPrefix")
    void adminCatalog_pathPreserved_noStripPrefix(@Autowired MockWebServer mockWebServer) {
        String auth = "Bearer token-123";
        String pageResponse = "{\"items\":[],\"total\":0,\"totalPages\":0,\"page\":0,\"size\":12}";

        MockWebServer server = mockWebServer;
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(pageResponse));

        webTestClient.get()
                .uri("/api/admin/peliculas?page=0&size=12&sort=titulo&asc=true")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.total").isEqualTo(0);

        try {
            RecordedRequest req = server.takeRequest();
            assert req.getPath().equals("/api/admin/peliculas?page=0&size=12&sort=titulo&asc=true");
            assert auth.equals(req.getHeader("Authorization"));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    @DisplayName("publicCatalog_invalidPaging_returns400WithStableShape")
    void publicCatalog_invalidPaging_returns400WithStableShape(@Autowired MockWebServer mockWebServer) {
        String errorJson = "{\"code\":\"INVALID_PAGE\",\"message\":\"Page index must not be negative\",\"details\":null}";

        MockWebServer server = mockWebServer;
        server.enqueue(new MockResponse()
                .setResponseCode(400)
                .setHeader("Content-Type", "application/json")
                .setBody(errorJson));

        webTestClient.get()
                .uri("/api/peliculas?page=-1&size=0")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("INVALID_PAGE")
                .jsonPath("$.message").exists();
    }

    @Test
    @DisplayName("adminCatalog_requiresAuth_gatewayDoesNotStripHeader")
    void adminCatalog_requiresAuth_gatewayDoesNotStripHeader(@Autowired MockWebServer mockWebServer) {
        String auth = "Bearer sentinel";
        String okJson = "{\"items\":[],\"total\":0,\"totalPages\":0,\"page\":0,\"size\":12}";

        MockWebServer server = mockWebServer;
        // when Authorization header present -> 200
        server.enqueue(new MockResponse().setResponseCode(200).setBody(okJson));
        // fallback: if header missing -> 401
        server.enqueue(new MockResponse().setResponseCode(401));

        // call without header -> expect 401 (first enqueued corresponds to first
        // request; adapt order)
        webTestClient.get()
                .uri("/api/admin/peliculas")
                .exchange()
                .expectStatus().isUnauthorized();

        // call with header -> expect 200 and header forwarded (we verify recorded
        // request)
        webTestClient.get()
                .uri("/api/admin/peliculas")
                .header(HttpHeaders.AUTHORIZATION, auth)
                .exchange()
                .expectStatus().isOk();

        try {
            // second request recorded
            RecordedRequest req = server.takeRequest();
            RecordedRequest req2 = server.takeRequest();
            // req corresponds to first call (no header) -> path check
            assert req.getPath().startsWith("/api/admin/peliculas");
            // req2 corresponds to second call with header
            assert auth.equals(req2.getHeader("Authorization"));
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

}
