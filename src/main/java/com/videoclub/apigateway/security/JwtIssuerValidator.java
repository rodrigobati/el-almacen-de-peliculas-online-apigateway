package com.videoclub.apigateway.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Validador custom de JWT que acepta múltiples issuers válidos.
 * 
 * Implementación idéntica a la del servicio Ventas para garantizar
 * comportamiento consistente en toda la arquitectura.
 * 
 * Permite tokens emitidos desde:
 * - Docker network (keycloak-sso:8080) - producción
 * - Localhost (localhost:9090) - testing local
 */
public class JwtIssuerValidator implements OAuth2TokenValidator<Jwt> {

    private final List<String> validIssuers;

    public JwtIssuerValidator(List<String> validIssuers) {
        this.validIssuers = validIssuers;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        String tokenIssuer = jwt.getIssuer().toString();

        if (validIssuers.contains(tokenIssuer)) {
            return OAuth2TokenValidatorResult.success();
        }

        OAuth2Error error = new OAuth2Error(
                "invalid_token",
                "El issuer '" + tokenIssuer + "' no es válido. Issuers aceptados: " + validIssuers,
                null);

        return OAuth2TokenValidatorResult.failure(error);
    }
}
