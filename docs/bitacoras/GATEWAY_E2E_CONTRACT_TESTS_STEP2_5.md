# GATEWAY E2E CONTRACT TESTS — STEP 2.5

## Objetivo

Añadir una suite determinística de tests que validen las invariantes del contrato entre el Spring Cloud Gateway y los controladores del servicio de catálogo sin ejecutar Docker ni depender de servicios externos.

Valida:

- Reescritura/forwarding de paths (StripPrefix vs preservación de `/api/admin`).
- Paso/forwarding del header `Authorization` para endpoints admin.
- Passthrough de respuestas y shape de errores (estatus y JSON `{code,message,details}`).

## Cómo funciona (diseño)

- Tests arrancan un stub HTTP in-memory (`WireMockServer`) que actúa como upstream.
- En la configuración de test se registra programáticamente un `RouteLocator` que apunta las rutas `catalogo` y `catalogo-admin` al stub (puerto dinámico).
- Se utiliza `WebTestClient` contra la aplicación Gateway en `RANDOM_PORT`.
- Los assertions verifican:
  - El upstream recibió la ruta esperada (p.ej. `/peliculas` o `/api/admin/peliculas`).
  - La query string y parámetros son preservados.
  - `Authorization` es reenviado por el gateway a los upstreams (no se elimina).
  - El gateway devuelve el status y el body tal cual el upstream (incluyendo shape de error).

## Por qué sin Docker

- Los tests deben ser determinísticos y rápidos en CI/local. Evitar arranques de contenedores mejora tiempo y aislación.
- La suite valida únicamente la lógica de routing y passthrough del gateway, por lo que no requiere el servicio de catálogo real ni Keycloak.

## Cómo correr los tests localmente

Desde el módulo `apigateway` ejecutar:

```bash
cd el-almacen-de-peliculas-online-apigateway
mvn -DskipTests=false test -q
```

## Archivos añadidos/modificados

- Modificado: `el-almacen-de-peliculas-online-apigateway/pom.xml` — se añadió dependencia de test `wiremock-jre8`.
- Añadido: `el-almacen-de-peliculas-online-apigateway/src/test/java/com/videoclub/apigateway/GatewayCatalogE2EContractTest.java` — tests E2E determinísticos.
- Añadido: `el-almacen-de-peliculas-online-apigateway/docs/bitacoras/GATEWAY_E2E_CONTRACT_TESTS_STEP2_5.md` — esta bitácora.

## Notas de mantenimiento

- Si se modifica la lógica de filtros del gateway (p.ej. cambiar `StripPrefix`), actualizar estas pruebas.
- Mantener las pruebas rápidas: evite añadir llamadas de red reales.
