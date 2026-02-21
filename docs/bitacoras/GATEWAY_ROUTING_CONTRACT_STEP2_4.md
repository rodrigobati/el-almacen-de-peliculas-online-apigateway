# GATEWAY ROUTING CONTRACT - STEP 2.4

## Overview

This document audits and documents the Spring Cloud Gateway routing contract for the project "El Almacén de Películas Online" (local gateway `http://localhost:9500`). It records assumptions, route mappings, verification steps and guardrails added as part of Step 2.4.

> No Docker/Compose changes were made as part of this work.

## 1) External base URL and canonical prefix

- Gateway public base: `http://localhost:9500`
- Canonical API prefix: `/api`
- Frontend should call `VITE_API_BASE_URL` which defaults to `http://localhost:9500/api`.

## 2) Route table (from gateway `application.yml`)

| Route ID                   |    External path predicate(s) | Target URI              | Filters                                         | Notes                                                                                                                                                                                                                                           |
| -------------------------- | ----------------------------: | ----------------------- | ----------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `catalogo`                 |      `Path=/api/peliculas/**` | `http://localhost:8081` | `StripPrefix=1`                                 | Forwards `/api/peliculas/...` -> backend `/peliculas/...` (removes `/api`). Backend expected to expose controller under `/peliculas`.                                                                                                           |
| `catalogo-admin`           |          `Path=/api/admin/**` | `http://localhost:8081` | (none)                                          | Admin endpoints forwarded preserving `/api/admin/...` path. Backend must accept `/api/admin` or controllers must be mounted accordingly. This difference is intentional; review backend controller mappings to avoid double `/api` duplication. |
| `rating`                   |        `Path=/api/ratings/**` | `http://localhost:8082` | `StripPrefix=1`                                 | Forwards `/api/ratings/...` -> backend `/ratings/...`.                                                                                                                                                                                          |
| `ventas-compras`           |        `Path=/api/compras/**` | `http://localhost:8083` | (none)                                          | Forwards purchases endpoints.                                                                                                                                                                                                                   |
| `ventas-carrito-confirmar` | `Path=/api/carrito/confirmar` | `http://localhost:8083` | (none)                                          | Single path mapping.                                                                                                                                                                                                                            |
| `ventas-carrito`           |        `Path=/api/carrito/**` | `http://localhost:8083` | `StripPrefix=1`                                 | Forwards cart endpoints after removing `/api`.                                                                                                                                                                                                  |
| `keycloak`                 |    `Path=/auth/**,/realms/**` | `http://localhost:9090` | `RewritePath=/auth/(?<segment>.*), /${segment}` | Rewrites `/auth/*` to Keycloak root path. Keycloak remains a first-class auth server; frontend may still interact with Keycloak (openid endpoints), but business APIs must go through gateway.                                                  |

Notes:

- Some routes intentionally preserve `/api` (no StripPrefix). Ensure backend controllers are mapped consistently to avoid duplicated `/api` segments.
- Actuator gateway endpoint is enabled (`/actuator/gateway`) for runtime introspection.

## 3) Do not bypass gateway rule

- Frontend MUST call only the configured `VITE_API_BASE_URL` (default `http://localhost:9500/api`).
- Do NOT hardcode direct backend URIs (e.g. `http://localhost:8081`) in frontend sources.
- Keycloak (`http://localhost:9090`) is an exception for authentication flows (OIDC), but normal API traffic must go through the gateway.

## 4) Verification steps (manual)

1. Ensure gateway config contains the above routes: open `apigateway/src/main/resources/application.yml`.
2. Start the gateway and confirm actuator exposes route definitions (if running):
   - `GET http://localhost:9500/actuator/gateway/routes` (requires actuator enabled and possibly auth)
3. From frontend environment ensure `VITE_API_BASE_URL` points to gateway base: `http://localhost:9500/api`.

## 5) Automated guardrails added

- A Spring Boot test `GatewayRoutingContractTest` was added to the gateway module that loads the gateway `RouteDefinitionLocator` and asserts the presence of required route IDs and that path predicates include expected `/api/...` patterns. The test helps catch configuration drift during CI or local builds.

- A frontend Vitest test `src/no-bypass.test.js` scans frontend sources for forbidden direct-backend URLs (`http://localhost:8081`, `http://localhost:8082`, `http://localhost:8083`, etc.). This test fails in CI if code introduces direct backend calls.

## 6) How to run the checks locally

- Run gateway unit tests (maven):

```bash
# from repo root or apigateway module
cd el-almacen-de-peliculas-online-apigateway
mvn -DskipTests=false test -q
```

- Run frontend bypass scan (Vitest):

```bash
cd el-almacen-de-peliculas-online-front-end
npm install # if not already
npm test --silent
```

The frontend test suite includes additional front-end checks (including the no-bypass rule).

## 7) Next steps / recommendations

- Standardize whether admin routes should preserve `/api` or use `StripPrefix` and harmonize backend controller mappings accordingly to avoid ambiguous path expectations.
- Consider enabling a stricter CI gate that fails if gateway route snapshot differs from an approved contract file.
- Keep `management.endpoints.web.exposure.include` to include `gateway` in non-production profiles only, or protect the actuator endpoints with a management secret in production.

## 8) Confirmation

- No Docker/Compose files were modified.
- The tests added are non-invasive and intended as early-warning checks to prevent gateway or frontend bypass regressions.

## Refinement 2.4.1 — Evidence-based corrections and guardrail hardening

Summary

- We re-extracted the gateway routing configuration from the repository (both local `application.yml` and `application-docker.yml`) and inspected the catalog backend controllers to remove prior assumptions.
- Outcome: the `catalogo-admin` route must preserve the `/api` prefix (do NOT StripPrefix) because `PeliculaAdminController` is mapped at `/api/admin/peliculas`. Public catalog endpoints ARE stripped (`StripPrefix=1`) to match `PeliculaController` mapped at `/peliculas`.

What assumptions were removed

- Previous wording in Step 2.4 noted `catalogo-admin` "may" require `StripPrefix=1`. That was an assumption. We now verify the exact controller mapping and gate configuration from source.

Evidence (exact extracted lines)

- From `apigateway/src/main/resources/application.yml` (local profile):

```
- id: catalogo
   uri: http://localhost:8081
   predicates:
      - Path=/api/peliculas/**
   filters:
      - StripPrefix=1

- id: catalogo-admin
   uri: http://localhost:8081
   predicates:
      - Path=/api/admin/**

- id: keycloak
   uri: http://localhost:9090
   predicates:
      - Path=/auth/**,/realms/**
   filters:
      - RewritePath=/auth/(?<segment>.*), /${segment}
```

- From `apigateway/src/main/resources/application-docker.yml` (docker profile):

```
- id: catalogo
   uri: http://catalogo-backend:8080
   predicates:
      - Path=/api/peliculas/**
   filters:
      - StripPrefix=1

- id: catalogo-admin
   uri: http://catalogo-backend:8080
   predicates:
      - Path=/api/admin/**
```

- From backend controller `PeliculaAdminController.java` (catalog service):

```
@RestController
@RequestMapping("/api/admin/peliculas")
public class PeliculaAdminController { ... }
```

- From backend controller `PeliculaController.java` (public catalog):

```
@RestController
@RequestMapping("/peliculas")
public class PeliculaController { ... }
```

Deterministic alignment decision

- Because `PeliculaAdminController` explicitly declares its base path under `/api/admin/peliculas`, the gateway must NOT apply `StripPrefix` to the `catalogo-admin` route; forwarding must preserve `/api/admin/...` so the controller mappings match exactly.
- Conversely, public catalog endpoints are exposed by `PeliculaController` at `/peliculas` and therefore the `catalogo` route must apply `StripPrefix=1` to translate external `/api/peliculas/**` -> backend `/peliculas/**`.

Guardrail changes made

- `GatewayRoutingContractTest` now asserts:
  - required route IDs exist (`catalogo`, `catalogo-admin`, `rating`, etc.)
  - path predicates include expected `/api/...` patterns
  - filters presence and their arguments are validated (e.g. `StripPrefix` argument equals `1`)
  - `catalogo-admin` must not include `StripPrefix` (explicit assertion)
  - `RewritePath` rules (when present in the active profile) must include both the regex and replacement parts (e.g. `/auth/(?<segment>.*)` and `/ ${segment}`)

- `GatewayRoutesRegressionTest` was extended to validate both `application.yml` (local) and `application-docker.yml` (docker profile) artifacts as plain-file contract checks. This ensures CI can detect profile-specific drift without instantiating the gateway.

- Frontend `no-bypass.test.js` updated to forbid direct docker service hostnames (e.g. `http://catalogo-backend:8080`, `http://rating-service:8082`, `http://ventas-service:8083`, `http://keycloak:8080`, `http://keycloak-sso:8080`) in addition to localhost ports.

Why this is safe

- No runtime network calls, no reliance on traces, and no Docker/Compose changes were made.
- Tests are deterministic: file-based contract checks (for docker/local YAML) and in-memory `RouteDefinitionLocator` checks for the active profile.

Verification steps (repo-only)

1. Run gateway unit tests; they validate both active config (via `RouteDefinitionLocator`) and offline YAML contract checks:

```bash
cd el-almacen-de-peliculas-online-apigateway
mvn -DskipTests=false test
```

2. Run frontend no-bypass check:

```bash
cd el-almacen-de-peliculas-online-front-end
npm test --silent
```

Do not modify Docker/Compose

- No Docker/Compose modifications were made. If future evidence suggests a Docker-level change is required, a separate Docker Change Proposal must be produced and approved before any edits.

Notes for maintainers

- If new admin controllers are added that do not use `/api/admin/...`, update the test expectations accordingly and document the intention in this bitácora.
- Keep `application-docker.yml` and `application.yml` synchronized only by intent; the tests will surface drift between profiles so decisions about differences remain deliberate.
