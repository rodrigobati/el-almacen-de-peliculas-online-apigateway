package com.videoclub.apigateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GatewayRoutesRegressionTest {

    @Test
    @DisplayName("routeVentasCarritoConfirmar mantienePrefijoApi")
    void routeVentasCarritoConfirmar_mantienePrefijoApi() throws IOException {
        String yaml = leerConfiguracionDocker();

        String bloque = extraerBloqueDeRuta(yaml, "ventas-carrito-confirmar");

        assertTrue(bloque.contains("Path=/api/carrito/confirmar"),
                "La ruta ventas-carrito-confirmar debe enrutar /api/carrito/confirmar");
        assertFalse(bloque.contains("StripPrefix"),
                "La ruta ventas-carrito-confirmar no debe aplicar StripPrefix");
    }

    @Test
    @DisplayName("routeVentasCompras mantienePrefijoApi")
    void routeVentasCompras_mantienePrefijoApi() throws IOException {
        String yaml = leerConfiguracionDocker();

        String bloque = extraerBloqueDeRuta(yaml, "ventas-compras");

        assertTrue(bloque.contains("Path=/api/compras/**"),
                "La ruta ventas-compras debe enrutar /api/compras/**");
        assertFalse(bloque.contains("StripPrefix"),
                "La ruta ventas-compras no debe aplicar StripPrefix");
    }

    private String leerConfiguracionDocker() throws IOException {
        try (var inputStream = getClass().getClassLoader().getResourceAsStream("application-docker.yml")) {
            if (inputStream == null) {
                throw new IOException("No se encontró application-docker.yml en classpath");
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String extraerBloqueDeRuta(String yaml, String idRuta) {
        String inicioMarcador = "- id: " + idRuta;

        int inicio = yaml.indexOf(inicioMarcador);
        int fin = yaml.indexOf("\n        - id:", inicio + inicioMarcador.length());
        if (fin < 0) {
            fin = yaml.length();
        }

        assertTrue(inicio >= 0, "No se encontró la ruta " + idRuta + " en la configuración");
        assertTrue(fin > inicio, "No se pudo delimitar el bloque para la ruta " + idRuta);

        return yaml.substring(inicio, fin);
    }

    @Test
    @DisplayName("docker_config_catalogoAdmin_noStripPrefix")
    void docker_catalogoAdmin_noStripPrefix() throws IOException {
        String yaml = leerConfiguracionDocker();
        String bloque = extraerBloqueDeRuta(yaml, "catalogo-admin");

        assertTrue(bloque.contains("Path=/api/admin/**"), "La ruta catalogo-admin debe enrutar /api/admin/**");
        assertFalse(bloque.contains("StripPrefix"), "En Docker la ruta catalogo-admin no debe aplicar StripPrefix");
    }

    @Test
    @DisplayName("local_config_catalogo_stripAnd_keycloak_rewrite")
    void local_catalogo_stripAndKeycloakRewrite() throws IOException {
        // Read application.yml (local profile) from test resources
        try (var inputStream = getClass().getClassLoader().getResourceAsStream("application.yml")) {
            if (inputStream == null) {
                throw new IOException("No se encontró application.yml en classpath");
            }
            String yaml = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

            String catalogo = extraerBloqueDeRuta(yaml, "catalogo");
            assertTrue(catalogo.contains("Path=/api/peliculas/**"),
                    "La ruta catalogo debe enrutar /api/peliculas/** en local");
            assertTrue(catalogo.contains("StripPrefix=1"), "En local la ruta catalogo debe aplicar StripPrefix=1");

            String keycloak = extraerBloqueDeRuta(yaml, "keycloak");
            assertTrue(keycloak.contains("Path=/auth/**") || keycloak.contains("/realms/**"),
                    "Keycloak predicates deben estar presentes en local");
            assertTrue(keycloak.contains("RewritePath"),
                    "En local la ruta keycloak debe incluir RewritePath cuando corresponde");
        }
    }
}
