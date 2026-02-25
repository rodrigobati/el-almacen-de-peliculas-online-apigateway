package com.videoclub.apigateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.security.Principal;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Repo-only guardrail: compares declared query-param contracts (YAML) against
 * controller method signatures.
 */
public class QueryParamContractDriftTest {

    enum ParamType {
        STRING, INT, LONG, BOOLEAN, DECIMAL, LOCAL_DATE, UNKNOWN
    }

    record ParamSpec(String name, boolean required, String defaultValue, ParamType type) {
    }

    record EndpointSpec(String path, String method, List<ParamSpec> params) {
    }

    private Map<String, List<EndpointSpec>> loadContracts(Path file) throws IOException {
        if (!Files.exists(file))
            return Map.of();
        try (InputStream in = Files.newInputStream(file)) {
            Yaml yaml = new Yaml();
            Object root = yaml.load(in);
            if (!(root instanceof Map))
                return Map.of();
            Map<String, Object> m = (Map<String, Object>) root;
            Object endpointsObj = m.get("endpoints");
            if (!(endpointsObj instanceof List))
                return Map.of();
            Map<String, List<EndpointSpec>> out = new HashMap<>();
            List<EndpointSpec> eps = new ArrayList<>();
            for (Object eo : (List<?>) endpointsObj) {
                if (!(eo instanceof Map))
                    continue;
                Map<String, Object> em = (Map<String, Object>) eo;
                String path = String.valueOf(em.get("path"));
                String method = String.valueOf(em.get("method"));
                List<ParamSpec> ps = new ArrayList<>();
                Object pob = em.get("params");
                if (pob instanceof List)
                    for (Object po : (List<?>) pob) {
                        if (!(po instanceof Map))
                            continue;
                        Map<String, Object> pm = (Map<String, Object>) po;
                        String name = String.valueOf(pm.get("name"));
                        boolean required = Boolean.parseBoolean(String.valueOf(pm.getOrDefault("required", "false")));
                        String def = pm.containsKey("default") ? String.valueOf(pm.get("default")) : null;
                        ParamType type = ParamType.valueOf(String.valueOf(pm.getOrDefault("type", "STRING")));
                        ps.add(new ParamSpec(name, required, def, type));
                    }
                eps.add(new EndpointSpec(path, method, ps));
            }
            out.put(file.getFileName().toString().replaceAll("\\.yml$", ""), eps);
            return out;
        }
    }

    // mapType moved to QueryParamContractTestSupport

    private List<Method> discoverGetMethods(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<?> comps = scanner.findCandidateComponents(basePackage);
        List<Method> methods = new ArrayList<>();
        for (Object c : comps) {
            try {
                org.springframework.beans.factory.config.BeanDefinition bd = (org.springframework.beans.factory.config.BeanDefinition) c;
                String cn = bd.getBeanClassName();
                if (cn == null)
                    continue;
                Class<?> cls = Class.forName(cn);
                RequestMapping classRm = cls.getAnnotation(RequestMapping.class);
                String classBase = "";
                if (classRm != null) {
                    String[] paths = classRm.path();
                    if (paths.length > 0)
                        classBase = paths[0];
                    else if (classRm.value().length > 0)
                        classBase = classRm.value()[0];
                }
                for (Method m : cls.getDeclaredMethods()) {
                    GetMapping gm = m.getAnnotation(GetMapping.class);
                    if (gm == null)
                        continue;
                    methods.add(m);
                }
            } catch (ClassNotFoundException e) {
                // ignore
            }
        }
        return methods;
    }

    // Canonicalize class+method mapping into normalized path(s)
    List<String> canonicalizePaths(String[] classBases, GetMapping gm) {
        List<String> out = new ArrayList<>();
        String[] methodPaths = (gm.path().length > 0) ? gm.path() : gm.value();
        if (methodPaths.length == 0)
            methodPaths = new String[] { "" };
        String[] cbs = (classBases == null || classBases.length == 0) ? new String[] { "" } : classBases;
        for (String cb : cbs) {
            String base = cb == null ? "" : cb;
            for (String mp : methodPaths) {
                String combined = (base + "/" + mp).replaceAll("//+", "/");
                if (!combined.startsWith("/"))
                    combined = "/" + combined;
                if (combined.length() > 1 && combined.endsWith("/"))
                    combined = combined.substring(0, combined.length() - 1);
                out.add(combined.equals("/") ? "/" : combined);
            }
        }
        return out;
    }

    // Build param map keyed by name for order-insensitive comparison
    Map<String, ParamSpec> toParamMap(List<ParamSpec> params) {
        Map<String, ParamSpec> m = new HashMap<>();
        for (ParamSpec p : params)
            m.put(p.name(), p);
        return m;
    }

    // Compare expected vs actual sets and report actionable failures
    void compareParams(String svc, String controller, String methodName, String path, Map<String, ParamSpec> expected,
            Map<String, ParamSpec> actual) {
        // missing
        for (String en : expected.keySet()) {
            if (!actual.containsKey(en))
                fail(String.format("Service=%s controller=%s method=%s endpoint=%s missing expected param '%s'", svc,
                        controller, methodName, path, en));
        }
        // extra
        for (String an : actual.keySet()) {
            if (!expected.containsKey(an))
                fail(String.format("Service=%s controller=%s method=%s endpoint=%s unexpected extra param '%s'", svc,
                        controller, methodName, path, an));
        }
        // compare details
        for (String n : expected.keySet()) {
            ParamSpec exp = expected.get(n);
            ParamSpec act = actual.get(n);
            if (act == null)
                continue; // already failed above
            if (exp.type() != act.type())
                fail(String.format(
                        "Service=%s controller=%s method=%s endpoint=%s param=%s type changed: expected=%s actual=%s",
                        svc, controller, methodName, path, n, exp.type(), act.type()));
            if (exp.required() != act.required())
                fail(String.format(
                        "Service=%s controller=%s method=%s endpoint=%s param=%s required changed: expected=%s actual=%s",
                        svc, controller, methodName, path, n, exp.required(), act.required()));
            String ed = exp.defaultValue() == null ? "" : exp.defaultValue();
            String ad = act.defaultValue() == null ? "" : act.defaultValue();
            if (!ed.equals(ad))
                fail(String.format(
                        "Service=%s controller=%s method=%s endpoint=%s param=%s default changed: expected='%s' actual='%s'",
                        svc, controller, methodName, path, n, ed, ad));
        }
    }

    @Test
    @DisplayName("Query param contract drift guardrail v1 — compare YAML contracts to controller signatures")
    void queryParamContractDrift() throws Exception {
        Path contractsRoot = Paths.get("..").resolve("docs").resolve("contracts").resolve("query-params");
        // services: catalogo, rating, ventas
        String[] services = new String[] { "catalogo", "rating", "ventas" };
        for (String svc : services) {
            Path yaml = contractsRoot.resolve(svc + ".yml");
            Map<String, List<EndpointSpec>> expected = loadContracts(yaml);
            List<Method> methods = discoverGetMethods(
                    svc.equals("catalogo") ? "unrn.api" : (svc.equals("rating") ? "unrn.rating.api" : "unrn.api"));

            // If no controller methods are discoverable it likely means the downstream
            // service classes are not on the test classpath (multi-module build not
            // active).
            // In that case skip the drift comparison with a clear message so the suite
            // remains useful when running the gateway module standalone.
            org.junit.jupiter.api.Assumptions.assumeTrue(!methods.isEmpty(),
                    "No controller methods discovered for service='" + svc
                            + "'. Activate internal test dependencies (e.g. -DinternalTests=true) or run multi-module build.");

            // Build actual endpoint map: path -> list of ParamSpec discovered
            Map<String, List<ParamSpec>> actualMap = new HashMap<>();
            for (Method m : methods) {
                RequestMapping classRm = m.getDeclaringClass().getAnnotation(RequestMapping.class);
                String[] classBases = new String[] { "" };
                if (classRm != null) {
                    if (classRm.path().length > 0)
                        classBases = classRm.path();
                    else if (classRm.value().length > 0)
                        classBases = classRm.value();
                }
                GetMapping gm = m.getAnnotation(GetMapping.class);
                List<String> effectivePaths = canonicalizePaths(classBases, gm);

                // determine if this method is in-scope: returns PageResponse or has query
                // params of interest
                boolean inScope = false;
                Class<?> ret = m.getReturnType();
                if (ret.getSimpleName().contains("Page") || ret.getSimpleName().contains("PageResponse"))
                    inScope = true;

                List<ParamSpec> ps = new ArrayList<>();
                for (Parameter p : m.getParameters()) {
                    // ignore non-query parameters explicitly
                    if (p.isAnnotationPresent(PathVariable.class) || p.isAnnotationPresent(RequestHeader.class)
                            || p.isAnnotationPresent(AuthenticationPrincipal.class))
                        continue;
                    if (Principal.class.isAssignableFrom(p.getType())
                            || ServerHttpRequest.class.isAssignableFrom(p.getType())
                            || ServerHttpResponse.class.isAssignableFrom(p.getType()))
                        continue;

                    RequestParam rp = p.getAnnotation(RequestParam.class);
                    if (rp == null) {
                        // not a request param -> ignore
                        continue;
                    }
                    // Enforce explicit name: do not rely on parameter names from reflection
                    if ((rp.name() == null || rp.name().isEmpty()) && (rp.value() == null || rp.value().isEmpty())) {
                        fail(String.format(
                                "Public API parameters must declare explicit @RequestParam(\"name\") in %s#%s",
                                m.getDeclaringClass().getName(), m.getName()));
                    }
                    String paramName = !rp.name().isEmpty() ? rp.name() : rp.value();
                    inScope = inScope || List.of("page", "size", "sort", "asc", "q").contains(paramName);

                    // default handling: treat ValueConstants.DEFAULT_NONE as no default
                    String def = rp.defaultValue();
                    if (ValueConstants.DEFAULT_NONE.equals(def))
                        def = null;
                    if (def != null) {
                        def = String.valueOf(def);
                    }

                    boolean required = rp.required();
                    if (def != null)
                        required = false; // default implies not required

                    ParamType type = QueryParamContractTestSupport.mapType(p.getType());
                    if (type == ParamType.UNKNOWN) {
                        fail(String.format(
                                "Unsupported parameter type for public API in %s#%s param=%s javaType=%s - add an explicit supported type mapping",
                                m.getDeclaringClass().getName(), m.getName(), paramName, p.getType().getName()));
                    }

                    ps.add(new ParamSpec(paramName, required, def, type));
                }
                if (!inScope)
                    continue;

                for (String full : effectivePaths) {
                    actualMap.put(full, ps);
                }
            }

            // Compare expected (from YAML) to actualMap
            List<EndpointSpec> eps = expected.getOrDefault(svc + ".yml", expected.getOrDefault(svc, List.of()));
            for (EndpointSpec es : eps) {
                String path = canonicalizeSinglePath(es.path());
                String pathWithoutApi = path.startsWith("/api/") ? path.substring(4) : path;
                String pathWithApi = path.startsWith("/api/") ? path
                        : "/api" + (path.startsWith("/") ? path : "/" + path);

                List<ParamSpec> expectedParams = es.params();
                Map<String, ParamSpec> expectedMap = toParamMap(expectedParams);

                List<ParamSpec> actual = actualMap.get(path);
                String matchedPath = path;

                if (actual == null) {
                    actual = actualMap.get(pathWithoutApi);
                    if (actual != null) {
                        matchedPath = pathWithoutApi;
                    }
                }

                if (actual == null) {
                    actual = actualMap.get(pathWithApi);
                    if (actual != null) {
                        matchedPath = pathWithApi;
                    }
                }

                if (actual == null) {
                    fail(String.format(
                            "Service=%s endpoint=%s not found in controllers (expected by contract). Tried: [%s, %s, %s]",
                            svc,
                            path, path, pathWithoutApi, pathWithApi));
                }

                Map<String, ParamSpec> actualMapByName = toParamMap(actual);
                compareParams(svc, "<discovered>", "<method>", matchedPath, expectedMap, actualMapByName);
            }
        }
    }

    private String canonicalizeSinglePath(String p) {
        if (p == null || p.isBlank())
            return "/";
        String s = p.replaceAll("//+", "/");
        if (!s.startsWith("/"))
            s = "/" + s;
        if (s.length() > 1 && s.endsWith("/"))
            s = s.substring(0, s.length() - 1);
        return s.equals("") ? "/" : s;
    }
}
