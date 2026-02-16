package com.videoclub.apigateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Filtro global para interceptar peticiones OPTIONS (CORS preflight)
 * y responder directamente sin rutear al backend.
 * 
 * Debe ejecutarse antes del ruteo (orden -1) para que Spring Security
 * no bloquee la petición antes de llegar aquí.
 */
@Component
public class CorsPreflightGlobalFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
            // OPTIONS siempre retorna 200 OK sin rutear
            var response = exchange.getResponse();
            var headers = response.getHeaders();

            response.setStatusCode(HttpStatus.OK);

            // Agregar headers CORS necesarios para el preflight
            // (los valores vienen de la configuración globalcors)
            headers.add("Access-Control-Allow-Methods", "GET,POST,PUT,DELETE,OPTIONS");
            headers.add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            headers.add("Access-Control-Max-Age", "3600");

            return Mono.empty();
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // Ejecutar ANTES del ruteo y la seguridad (-1 es antes que 0)
        return -1;
    }
}
