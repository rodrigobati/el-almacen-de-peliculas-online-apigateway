package com.videoclub.apigateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteDefinition;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class GatewayRoutingContractTest {

        @Autowired
        private RouteDefinitionLocator routeDefinitionLocator;

        @Test
        void requiredRoutesExistAndHaveExpectedPredicatesAndFilters() {
                List<RouteDefinition> defs = routeDefinitionLocator.getRouteDefinitions().collectList().block();
                assertNotNull(defs, "Route definitions must be available");

                List<String> ids = defs.stream().map(RouteDefinition::getId).collect(Collectors.toList());

                // Required route ids (as declared in application.yml)
                assertTrue(ids.contains("catalogo"), "Expected route 'catalogo' to be defined");
                assertTrue(ids.contains("catalogo-admin"), "Expected route 'catalogo-admin' to be defined");

                // Validate catalogo route predicates and filters
                RouteDefinition catalogo = defs.stream().filter(d -> "catalogo".equals(d.getId())).findFirst()
                                .orElse(null);
                assertNotNull(catalogo, "catalogo route must be present");

                boolean hasPeliculasPath = catalogo.getPredicates().stream()
                                .filter(p -> "Path".equalsIgnoreCase(p.getName()))
                                .flatMap(p -> p.getArgs().values().stream())
                                .anyMatch(v -> v.contains("/api/peliculas"));

                assertTrue(hasPeliculasPath, "catalogo route should have a Path predicate for /api/peliculas/**");

                // Verify StripPrefix exists and its argument equals 1 (remove leading /api)
                var stripFilter = catalogo.getFilters().stream()
                                .filter(f -> "StripPrefix".equalsIgnoreCase(f.getName()))
                                .findFirst().orElse(null);
                assertNotNull(stripFilter, "catalogo route is expected to include StripPrefix filter to remove /api");
                boolean stripHasOne = stripFilter.getArgs().values().stream()
                                .anyMatch(v -> "1".equals(v) || v.contains("1"));
                assertTrue(stripHasOne, "StripPrefix filter for 'catalogo' must have argument '1'");

                // Validate admin route
                RouteDefinition admin = defs.stream().filter(d -> "catalogo-admin".equals(d.getId())).findFirst()
                                .orElse(null);
                assertNotNull(admin, "catalogo-admin route must be present");

                boolean hasAdminPath = admin.getPredicates().stream()
                                .filter(p -> "Path".equalsIgnoreCase(p.getName()))
                                .flatMap(p -> p.getArgs().values().stream())
                                .anyMatch(v -> v.contains("/api/admin"));

                assertTrue(hasAdminPath, "catalogo-admin route should have a Path predicate for /api/admin/**");

                // Admin route must preserve the /api prefix because backend controller mapping
                // is declared under '/api/admin' (see PeliculaAdminController). Assert no
                // StripPrefix.
                boolean adminHasStrip = admin.getFilters().stream()
                                .anyMatch(f -> "StripPrefix".equalsIgnoreCase(f.getName()));
                assertFalse(adminHasStrip,
                                "catalogo-admin route must not apply StripPrefix because backend expects '/api/admin' prefix");

                // Validate keycloak rewrite path filter (if present in active config)
                RouteDefinition keycloak = defs.stream().filter(d -> "keycloak".equals(d.getId())).findFirst()
                                .orElse(null);
                if (keycloak != null) {
                        var rewrite = keycloak.getFilters().stream()
                                        .filter(f -> "RewritePath".equalsIgnoreCase(f.getName()))
                                        .findFirst().orElse(null);
                        if (rewrite != null) {
                                // Expect rewrite rule to contain the /auth -> / mapping pieces
                                boolean hasAuthRegex = rewrite.getArgs().values().stream()
                                                .anyMatch(v -> v.contains("/auth/") || v.contains("(?<segment>"));
                                boolean hasReplacement = rewrite.getArgs().values().stream()
                                                .filter(Objects::nonNull)
                                                .map(Object::toString)
                                                .map(String::trim)
                                                .anyMatch(v -> v.contains("${") || v.contains("segment") || "/".equals(v)
                                                                || v.startsWith("/$"));
                                assertTrue(hasAuthRegex, "RewritePath rule must reference /auth/ regex when present");
                                assertTrue(hasReplacement,
                                                "RewritePath rule must include a valid replacement when present");
                        }
                }
        }
}
