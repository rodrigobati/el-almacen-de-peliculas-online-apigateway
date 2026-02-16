package com.videoclub.apigateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Filtro global que propaga el token JWT al backend downstream.
 * 
 * Spring Cloud Gateway valida el JWT pero NO lo reenvía automáticamente.
 * Este filtro extrae el token del SecurityContext y lo agrega al header
 * Authorization de todas las peticiones ruteadas a los microservicios.
 */
@Component
public class TokenRelayGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(auth -> auth instanceof JwtAuthenticationToken)
                .map(auth -> (JwtAuthenticationToken) auth)
                .map(jwtAuth -> {
                    // Extraer token JWT del contexto de seguridad
                    String tokenValue = jwtAuth.getToken().getTokenValue();

                    // Agregar Authorization header al request downstream
                    ServerHttpRequest mutatedRequest = exchange.getRequest().mutate()
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokenValue)
                            .build();

                    // Crear nuevo exchange con el request mutado
                    return exchange.mutate().request(mutatedRequest).build();
                })
                .defaultIfEmpty(exchange)
                .flatMap(chain::filter);
    }

    @Override
    public int getOrder() {
        // Ejecutar DESPUÉS de la autenticación (0) pero ANTES del ruteo (1)
        return 0;
    }
}
