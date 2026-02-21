package com.videoclub.apigateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hardened guardrail: parse YAML via SnakeYAML and discover controller mappings
 * via classpath scanning.
 */
public class CrossServiceContractDriftHardenedTest {

    record RouteContract(String id, List<String> predicates, int stripPrefix) {
    }

    private static final Map<String, ServiceDescriptor> CONTRACT = Map.of(
            "catalogo",
            new ServiceDescriptor("catalogo", "unrn.api",
                    Paths.get("..", "el-almacen-de-peliculas-online", "el-almacen-de-peliculas-online", "src", "main",
                            "resources")),
            "catalogo-admin",
            new ServiceDescriptor("catalogo-admin", "unrn.api",
                    Paths.get("..", "el-almacen-de-peliculas-online", "el-almacen-de-peliculas-online", "src", "main",
                            "resources")),
            "rating",
            new ServiceDescriptor("rating", "unrn.rating.api",
                    Paths.get("..", "el-almacen-de-peliculas-online-rating", "src", "main", "resources")),
            "ventas", new ServiceDescriptor("ventas", "unrn.api",
                    Paths.get("..", "el-almacen-de-peliculas-online-ventas", "src", "main", "resources")));

    private static final class ServiceDescriptor {
        final String name;
        final String basePackage;
        final Path resourcesPath;

        ServiceDescriptor(String n, String p, Path r) {
            this.name = n;
            this.basePackage = p;
            this.resourcesPath = r;
        }
    }

    private List<RouteContract> loadRoutes(Path yaml) throws IOException {
        if (!Files.exists(yaml))
            return List.of();
        try (InputStream in = Files.newInputStream(yaml)) {
            Yaml y = new Yaml();
            Object root = y.load(in);
            if (!(root instanceof Map))
                return List.of();
            Map<?, ?> mroot = (Map<?, ?>) root;
            Map<?, ?> spring = (Map<?, ?>) mroot.get("spring");
            if (spring == null)
                return List.of();
            Map<?, ?> cloud = (Map<?, ?>) spring.get("cloud");
            if (cloud == null)
                return List.of();
            Map<?, ?> gateway = (Map<?, ?>) cloud.get("gateway");
            if (gateway == null)
                return List.of();
            Object routesObj = gateway.get("routes");
            if (!(routesObj instanceof List))
                return List.of();
            List<RouteContract> out = new ArrayList<>();
            for (Object r : (List<?>) routesObj) {
                if (!(r instanceof Map))
                    continue;
                Map<?, ?> rm = (Map<?, ?>) r;
                String id = String.valueOf(rm.get("id"));
                List<String> preds = new ArrayList<>();
                Object po = rm.get("predicates");
                if (po instanceof List)
                    for (Object p : (List<?>) po)
                        preds.add(String.valueOf(p));
                int strip = 0;
                Object fo = rm.get("filters");
                if (fo instanceof List)
                    for (Object f : (List<?>) fo) {
                        String fs = String.valueOf(f);
                        if (fs.startsWith("StripPrefix")) {
                            int eq = fs.indexOf('=');
                            if (eq > 0)
                                try {
                                    strip = Integer.parseInt(fs.substring(eq + 1).trim());
                                } catch (NumberFormatException ignore) {
                                }
                        }
                    }
                out.add(new RouteContract(id, preds, strip));
            }
            return out;
        }
    }

    public String baseFromPredicate(String pred) {
        int eq = pred.indexOf('=');
        if (eq < 0)
            return pred;
        String v = pred.substring(eq + 1).trim();
        if ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))
            v = v.substring(1, v.length() - 1);
        if (v.endsWith("/**"))
            v = v.substring(0, v.length() - 3);
        if (v.endsWith("/*"))
            v = v.substring(0, v.length() - 2);
        if (!v.startsWith("/"))
            v = "/" + v;
        if (v.length() > 1 && v.endsWith("/"))
            v = v.substring(0, v.length() - 1);
        return v;
    }

    public String stripPrefix(String base, int n) {
        if (n <= 0)
            return base;
        String[] parts = base.split("/");
        List<String> rem = new ArrayList<>();
        for (int i = 1 + n; i < parts.length; i++)
            rem.add(parts[i]);
        return rem.isEmpty() ? "/" : rem.stream().collect(Collectors.joining("/", "/", ""));
    }

    private List<String> discover(String basePackage) {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        scanner.addIncludeFilter(new AnnotationTypeFilter(Controller.class));
        Set<?> comps = scanner.findCandidateComponents(basePackage);
        List<String> mappings = new ArrayList<>();
        for (Object c : comps) {
            try {
                org.springframework.beans.factory.config.BeanDefinition bd = (org.springframework.beans.factory.config.BeanDefinition) c;
                String cn = bd.getBeanClassName();
                if (cn == null)
                    continue;
                Class<?> cls = Class.forName(cn);
                RequestMapping rm = cls.getAnnotation(RequestMapping.class);
                if (rm != null) {
                    String[] paths = rm.path();
                    if (paths == null || paths.length == 0)
                        paths = rm.value();
                    for (String p : paths)
                        if (p != null && !p.isBlank()) {
                            String norm = p.startsWith("/") ? p : "/" + p;
                            if (norm.length() > 1 && norm.endsWith("/"))
                                norm = norm.substring(0, norm.length() - 1);
                            mappings.add(norm);
                        }
                }
            } catch (ClassNotFoundException ignored) {
            }
        }
        return mappings;
    }

    private String readContext(Path resourcesRoot) throws IOException {
        if (resourcesRoot == null)
            return "";
        Path docker = resourcesRoot.resolve("application-docker.yml");
        Path app = resourcesRoot.resolve("application.yml");
        for (Path p : List.of(docker, app))
            if (Files.exists(p)) {
                try (InputStream in = Files.newInputStream(p)) {
                    Yaml y = new Yaml();
                    Object root = y.load(in);
                    if (!(root instanceof Map))
                        continue;
                    Map<?, ?> m = (Map<?, ?>) root;
                    Object server = m.get("server");
                    if (server instanceof Map) {
                        Object servlet = ((Map<?, ?>) server).get("servlet");
                        if (servlet instanceof Map) {
                            Object cp = ((Map<?, ?>) servlet).get("context-path");
                            if (cp instanceof String)
                                return (String) cp;
                        }
                        Object cp2 = ((Map<?, ?>) server).get("servlet.context-path");
                        if (cp2 instanceof String)
                            return (String) cp2;
                    }
                }
            }
        return "";
    }

    @Test
    @DisplayName("Cross-service contract drift hardened guardrail")
    void runGuardrail() throws IOException {
        Path gatewayResources = Paths.get("").toAbsolutePath().normalize().resolve("src").resolve("main")
                .resolve("resources");
        for (String profile : new String[] { "application.yml", "application-docker.yml" }) {
            Path yaml = gatewayResources.resolve(profile);
            List<RouteContract> routes = loadRoutes(yaml);
            for (RouteContract rc : routes) {
                ServiceDescriptor sd = CONTRACT.get(rc.id());
                if (sd == null)
                    continue;
                String pred = rc.predicates().stream().filter(p -> p.startsWith("Path")).findFirst().orElse(null);
                if (pred == null)
                    continue;
                String base = baseFromPredicate(pred);
                String forwarded = stripPrefix(base, rc.stripPrefix());

                List<String> controllers = discover(sd.basePackage);
                if (controllers.isEmpty())
                    continue;

                Path resRoot = sd.resourcesPath.toAbsolutePath().normalize();
                String ctx = readContext(resRoot);
                String ctxNorm = (ctx == null || ctx.isBlank()) ? "" : (ctx.startsWith("/") ? ctx : "/" + ctx);
                List<String> effective = controllers.stream().map(c -> (ctxNorm + c).replaceAll("//+", "/"))
                        .collect(Collectors.toList());
                boolean ok = effective.stream().anyMatch(e -> e.equals(forwarded) || e.startsWith(forwarded + "/")
                        || (forwarded.equals("/") && !effective.isEmpty()));
                assertTrue(ok,
                        () -> String.format(
                                "Profile=%s routeId=%s predicates=%s strip=%d forwarded=%s service=%s discovered=%s",
                                profile, rc.id(), rc.predicates(), rc.stripPrefix(), forwarded, sd.name,
                                effective.stream().limit(15).collect(Collectors.joining(", "))));
            }
        }
    }
}
