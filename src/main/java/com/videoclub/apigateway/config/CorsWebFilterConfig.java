package com.videoclub.apigateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.Collections;

/**
 * CORS Configuration for API Gateway
 * 
 * This is the SINGLE source of CORS configuration for all requests/responses.
 * It applies at the WebFlux filter chain level (before routing and security),
 * ensuring that ALL responses (including 401/403 from security layer) have
 * consistent, non-duplicated CORS headers.
 * 
 * Configuration:
 * - Allowed Origin: http://localhost:5173 (React frontend)
 * - Credentials: true (for cookies and auth headers)
 * - Methods: GET, POST, PUT, PATCH, DELETE, OPTIONS
 * - Headers: * (accept all)
 * - Max Age: 3600 seconds (1 hour)
 * 
 * No other CORS configuration should exist in this gateway.
 * globalcors config in YAML is disabled.
 */
@Configuration
public class CorsWebFilterConfig {

    @Bean
    public CorsWebFilter corsWebFilter() {
        // Create CORS configuration
        CorsConfiguration corsConfiguration = new CorsConfiguration();

        // CRITICAL: Single origin, NO wildcards (required for credentials=true)
        corsConfiguration.setAllowedOrigins(Collections.singletonList("http://localhost:5173"));

        // Allow standard HTTP methods
        corsConfiguration.setAllowedMethods(Arrays.asList(
                HttpMethod.GET.name(),
                HttpMethod.POST.name(),
                HttpMethod.PUT.name(),
                HttpMethod.PATCH.name(),
                HttpMethod.DELETE.name(),
                HttpMethod.OPTIONS.name()));

        // Allow all headers in requests
        corsConfiguration.setAllowedHeaders(Collections.singletonList("*"));

        // Expose Authorization header for JWT tokens
        corsConfiguration.setExposedHeaders(Collections.singletonList("Authorization"));

        // CRITICAL: Allow credentials (cookies, auth headers)
        // This MUST be true for frontend to send/receive credentials
        corsConfiguration.setAllowCredentials(true);

        // Cache preflight for 1 hour
        corsConfiguration.setMaxAge(3600L);

        // Apply to all paths
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", corsConfiguration);

        return new CorsWebFilter(source);
    }
}
