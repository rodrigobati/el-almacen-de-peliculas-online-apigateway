package com.videoclub.apigateway.config;

import com.videoclub.apigateway.security.JwtIssuerValidator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;

import java.util.List;

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

        @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
        private String issuerUri;

        @Bean
        public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
                http
                                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                                .authorizeExchange(exchanges -> exchanges
                                                // CORS preflight: OPTIONS siempre permitido (RFC 7231)
                                                .pathMatchers(org.springframework.http.HttpMethod.OPTIONS, "/**")
                                                .permitAll()

                                                // Rutas públicas (sin autenticación)
                                                .pathMatchers("/auth/**", "/realms/**").permitAll()
                                                .pathMatchers("/actuator/health", "/actuator/gateway/**").permitAll()

                                                // Catálogo: solo lectura pública (GET), escritura protegida (POST, PUT,
                                                // DELETE)
                                                .pathMatchers(org.springframework.http.HttpMethod.GET,
                                                                "/api/peliculas/**")
                                                .permitAll()
                                                .pathMatchers("/api/peliculas/**").authenticated()

                                                // Categorías: GET público
                                                .pathMatchers(org.springframework.http.HttpMethod.GET,
                                                                "/api/categorias/**")
                                                .permitAll()

                                                // Ratings: GET público, POST/PUT/DELETE requieren autenticación
                                                .pathMatchers(org.springframework.http.HttpMethod.GET,
                                                                "/api/ratings/**")
                                                .permitAll()
                                                .pathMatchers("/api/ratings/**").authenticated()

                                                // Carrito: requiere autenticación
                                                .pathMatchers("/api/carrito/**").authenticated()

                                                // Cualquier otra ruta por defecto es pública
                                                .anyExchange().permitAll())
                                .oauth2ResourceServer(oauth2 -> oauth2
                                                .jwt(jwt -> jwt.jwtDecoder(reactiveJwtDecoder())));

                return http.build();
        }

        /**
         * Configuración custom del JwtDecoder para aceptar múltiples issuers.
         * 
         * Usa issuer-uri para resolver el JWK Set y validar tokens.
         * La validación de issuer se realiza explícitamente mediante
         * JwtIssuerValidator.
         * 
         * Esta configuración permite:
         * - Tokens de localhost:9090 (testing local desde fuera de Docker)
         * - Tokens de keycloak-sso:8080 (producción dentro de Docker)
         * 
         * Comportamiento idéntico al servicio Ventas.
         */
        @Bean
        public ReactiveJwtDecoder reactiveJwtDecoder() {
                NimbusReactiveJwtDecoder jwtDecoder = NimbusReactiveJwtDecoder.withIssuerLocation(issuerUri).build();

                // Validador de timestamps (exp, nbf, iat)
                OAuth2TokenValidator<Jwt> withTimestamp = new JwtTimestampValidator();

                // Validador custom de issuer (acepta múltiples)
                OAuth2TokenValidator<Jwt> withIssuers = new JwtIssuerValidator(
                                List.of(
                                                "http://keycloak-sso:8080/realms/videoclub", // Docker network
                                                "http://localhost:9090/realms/videoclub" // Testing local
                                ));

                // Combinar validadores
                OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                                withTimestamp,
                                withIssuers);

                jwtDecoder.setJwtValidator(validator);
                return jwtDecoder;
        }
}
