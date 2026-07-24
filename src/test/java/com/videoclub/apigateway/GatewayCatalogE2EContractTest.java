package com.videoclub.apigateway;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.main.allow-bean-definition-overriding=true")
@AutoConfigureWebTestClient
@Tag("e2e")
@Timeout(20)
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
                    .route("test-catalogo", r -> r.order(-100)
                            .path("/api/peliculas/**")
                            .filters(f -> f.stripPrefix(1))
                            .uri(uri))
                    // admin catalog - preserve /api/admin/**
                    .route("test-catalogo-admin", r -> r.order(-100)
                            .path("/api/admin/**")
                            .uri(uri))
                    .build();
        }

        @Bean
        @Primary
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "gateway-test")
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build());
        }
    }

    @AfterAll
    static void afterAll(@Autowired MockWebServer mockWebServer) throws Exception {
        mockWebServer.shutdown();
    }

    @Test
    @DisplayName("publicCatalog_pathRewritten_stripPrefixApplied")
    void publicCatalog_pathRewritten_stripPrefixApplied(@Autowired MockWebServer mockWebServer)
            throws InterruptedException {
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

        RecordedRequest req = takeForwardedRequest(server);
        assertTrue(req.getPath().startsWith("/peliculas"));
        // ensure query preserved
        assertEquals("0", req.getRequestUrl().queryParameter("page"));
    }

    @Test
    @DisplayName("adminCatalog_pathPreserved_noStripPrefix")
    void adminCatalog_pathPreserved_noStripPrefix(@Autowired MockWebServer mockWebServer) throws InterruptedException {
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

        RecordedRequest req = takeForwardedRequest(server);
        assertEquals("/api/admin/peliculas?page=0&size=12&sort=titulo&asc=true", req.getPath());
        assertEquals(auth, req.getHeader("Authorization"));
    }

    @Test
    @DisplayName("publicCatalog_invalidPaging_returns400WithStableShape")
    void publicCatalog_invalidPaging_returns400WithStableShape(@Autowired MockWebServer mockWebServer)
            throws InterruptedException {
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

        RecordedRequest req = takeForwardedRequest(server);
        assertEquals("/peliculas?page=-1&size=0", req.getPath());
    }

    @Test
    @DisplayName("adminCatalog_requiresAuth_gatewayDoesNotStripHeader")
    void adminCatalog_requiresAuth_gatewayDoesNotStripHeader(@Autowired MockWebServer mockWebServer)
            throws InterruptedException {
        String auth = "Bearer sentinel";
        String okJson = "{\"items\":[],\"total\":0,\"totalPages\":0,\"page\":0,\"size\":12}";

        MockWebServer server = mockWebServer;
        // fallback: if header missing -> 401
        server.enqueue(new MockResponse().setResponseCode(401));
        // when Authorization header present -> 200
        server.enqueue(new MockResponse().setResponseCode(200).setBody(okJson));

        // call without header -> upstream mock rejects it
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

        RecordedRequest req = takeForwardedRequest(server);
        RecordedRequest req2 = takeForwardedRequest(server);
        // req corresponds to first call (no header) -> path check
        assertTrue(req.getPath().startsWith("/api/admin/peliculas"));
        // req2 corresponds to second call with header
        assertEquals(auth, req2.getHeader("Authorization"));
    }

    private RecordedRequest takeForwardedRequest(MockWebServer server) throws InterruptedException {
        RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
        assertNotNull(request, "Expected gateway to forward the request to MockWebServer");
        return request;
    }
}
