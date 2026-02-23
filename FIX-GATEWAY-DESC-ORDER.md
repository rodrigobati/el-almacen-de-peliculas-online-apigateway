# Fix — Gateway Route Precedence for Admin Descuentos

## Resumen

Se corrigió la colisión de rutas en el API Gateway para que `/api/admin/descuentos/**` coincida siempre con la ruta `descuentos-admin` y `/api/admin/**` quede como fallback. No se modificó lógica de negocio, front-end ni archivos Docker Compose. Solo se reconstruyó y reinició `api-gateway` para aplicar la configuración.

---

## 1) Observación inicial

- Endpoint problemático: `GET /api/admin/descuentos/listar` era enrutado a `catalogo-backend` → 404.
- Causa: rutas solapadas en Gateway:
  - `descuentos-admin` → `Path=/api/admin/descuentos/**`
  - `catalogo-admin` → `Path=/api/admin/**`
- Objetivo: forzar precedencia determinística con `order` en la configuración del Gateway.

---

## 2) Archivos modificados

- `el-almacen-de-peliculas-online-apigateway/src/main/resources/application.yml`
- `el-almacen-de-peliculas-online-apigateway/src/main/resources/application-docker.yml`

No se tocaron:

- controladores backend
- frontend
- `docker-compose*.yml` ni otros artefactos Docker

---

## 3) Cambios aplicados (resumen)

- `descuentos-admin` → `order: 0`
- `catalogo-admin` → `order: 100`

---

## 4) Snippets tipo patch (solo bloques modificados)

application.yml (extracto)

```diff
-        - id: catalogo-admin
-          uri: http://localhost:8081
-          predicates:
-            - Path=/api/admin/**
+        - id: catalogo-admin
+          uri: http://localhost:8081
+          order: 100
+          predicates:
+            - Path=/api/admin/**

...
-        - id: descuentos-admin
-          uri: http://localhost:8085
-          predicates:
-            - Path=/api/descuentos/**
+        - id: descuentos-admin
+          uri: http://localhost:8085
+          order: 0
+          predicates:
+            - Path=/api/admin/descuentos/**
```

application-docker.yml (extracto)

```diff
-        - id: catalogo-admin
-          uri: http://catalogo-backend:8080
-          predicates:
-            - Path=/api/admin/**
+        - id: catalogo-admin
+          uri: http://catalogo-backend:8080
+          order: 100
+          predicates:
+            - Path=/api/admin/**

...
-        - id: descuentos-admin
-          uri: http://descuentos-service:8085
-          predicates:
-            - Path=/api/admin/descuentos/**
+        - id: descuentos-admin
+          uri: http://descuentos-service:8085
+          order: 0
+          predicates:
+            - Path=/api/admin/descuentos/**
```

---

## 5) Comandos ejecutados (exactos, reproducibles)

Mostrar estado Git y diff (apigateway):

```bash
cd "el-almacen-de-peliculas-online-apigateway"
git rev-parse --abbrev-ref HEAD
git status --porcelain
git --no-pager diff -- src/main/resources/application.yml src/main/resources/application-docker.yml
```

Listar contenedores (compose full):

```bash
docker compose -f "el-almacen-de-peliculas-online/docker-compose-full.yml" ps
```

Reconstruir y reiniciar solo api-gateway:

```bash
docker compose -f "el-almacen-de-peliculas-online/docker-compose-full.yml" up -d --build api-gateway
```

Ver rutas efectivas (actuator):

```bash
curl.exe http://localhost:9500/actuator/gateway/routes
```

Probar endpoint vía gateway:

```bash
curl.exe -i http://localhost:9500/api/admin/descuentos/listar
```

Logs del gateway:

```bash
docker logs --tail 250 api-gateway
```

Probar servicio directo (aislado):

```bash
curl.exe -i http://localhost:8085/api/admin/descuentos/listar
```

Logs del servicio descuentos:

```bash
docker logs --tail 400 descuentos-service
```

---

## 6) Evidencia (extractos relevantes)

- Actuator routes (muestra `order`):

```json
... "route_id":"descuentos-admin","uri":"http://descuentos-service:8085","order":0 ...
... "route_id":"catalogo-admin","uri":"http://catalogo-backend:8080","order":100 ...
```

- Resultado curl vía gateway (sin token):

```
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer resource_metadata="http://descuentos-service:8085/.well-known/oauth-protected-resource"
```

- Gateway logs (fragmentos):

```
Route matched: descuentos-admin
Mapping [Exchange: GET http://localhost:9500/api/admin/descuentos/listar] to Route{id='descuentos-admin', uri=http://descuentos-service:8085, order=0, ...}
Handler is being applied: {uri=http://descuentos-service:8085/api/admin/descuentos/listar, method=GET}
Received response ... HTTP/1.1 401
```

- Directo al servicio (sin token):

```
HTTP/1.1 401
WWW-Authenticate: Bearer resource_metadata="http://localhost:8085/.well-known/oauth-protected-resource"
```

- Logs `descuentos-service`:
  - Inicio con perfil `docker` y sin trazas de stack JWT en las últimas 400 líneas; servicio responde 401 cuando no hay token.

---

## 7) Interpretación y conclusión

- El enrutamiento ahora es correcto: `/api/admin/descuentos/**` coincide con `descuentos-admin` (evidencia: actuator + logs).
- El `401` observado es generado por `descuentos-service` (el gateway lo reenvía y devuelve el 401 que recibe).
- Resultado esperado sin token: `descuentos-service` protege el endpoint y devuelve `401` con `WWW-Authenticate`.
- No hay cambios en Docker Compose; solo se reconstruyó `api-gateway` para cargar la configuración modificada.

---

## 8) Siguientes pasos recomendados (opcional)

- Para diagnosticar por qué el cliente real obtiene 401 (si el front indica estar autenticado):
  - Proveer captura de DevTools (Network) del request `listar` (Headers completos). Confirmar:
    - ¿Existe `Authorization: Bearer <TOKEN>`?
    - ¿Token no vacío?
  - O pegar aquí el token JWT (string). Con el token puedo:
    - Probar envío del token contra gateway y contra el servicio directo:
      - `curl -i -H "Authorization: Bearer <TOKEN>" http://localhost:9500/api/admin/descuentos/listar`
      - `curl -i -H "Authorization: Bearer <TOKEN>" http://localhost:8085/api/admin/descuentos/listar`
    - Decodificar token y verificar `iss`, `aud`, `realm_access.roles`, `exp` y `azp`.

---

## 9) Commit propuesto (necesita su aprobación)

- Mensaje propuesto:

```
Fix: set gateway route order to prioritize descuentos-admin over catalogo-admin
```

Decime si querés que haga el commit y push (yo mostraré `git status` y `git diff` antes de confirmar).
