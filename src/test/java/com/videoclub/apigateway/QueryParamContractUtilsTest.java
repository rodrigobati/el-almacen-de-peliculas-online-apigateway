package com.videoclub.apigateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.HashMap;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.server.reactive.ServerHttpRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

public class QueryParamContractUtilsTest {

        @Test
        @DisplayName("mapType recognizes common types")
        void mapTypeRecognizes() {
                assertEquals(QueryParamContractDriftTest.ParamType.STRING,
                                QueryParamContractTestSupport.mapType(String.class));
                assertEquals(QueryParamContractDriftTest.ParamType.INT,
                                QueryParamContractTestSupport.mapType(int.class));
                assertEquals(QueryParamContractDriftTest.ParamType.LONG,
                                QueryParamContractTestSupport.mapType(long.class));
                assertEquals(QueryParamContractDriftTest.ParamType.BOOLEAN,
                                QueryParamContractTestSupport.mapType(boolean.class));
                assertEquals(QueryParamContractDriftTest.ParamType.DECIMAL,
                                QueryParamContractTestSupport.mapType(BigDecimal.class));
                assertEquals(QueryParamContractDriftTest.ParamType.LOCAL_DATE,
                                QueryParamContractTestSupport.mapType(LocalDate.class));
        }

        @Test
        @DisplayName("diff engine detects missing/extra/changed params")
        void diffEngineDetectsChanges() {
                QueryParamContractDriftTest q = new QueryParamContractDriftTest();

                var Exp = new QueryParamContractDriftTest.ParamSpec("q", false, null,
                                QueryParamContractDriftTest.ParamType.STRING);
                var ExpPage = new QueryParamContractDriftTest.ParamSpec("page", false, "0",
                                QueryParamContractDriftTest.ParamType.INT);
                var ActSame = new QueryParamContractDriftTest.ParamSpec("q", false, null,
                                QueryParamContractDriftTest.ParamType.STRING);
                var ActPageChangedDefault = new QueryParamContractDriftTest.ParamSpec("page", false, "1",
                                QueryParamContractDriftTest.ParamType.INT);

                // expected map
                var expected = Map.of(Exp.name(), Exp, ExpPage.name(), ExpPage);

                // 1) default changed
                var actual1 = Map.of(ActSame.name(), ActSame, ActPageChangedDefault.name(), ActPageChangedDefault);
                assertThrows(AssertionError.class, () -> q.compareParams("svc", "C", "m", "/p", expected, actual1));

                // 2) missing param
                var actual2 = Map.of(ActSame.name(), ActSame);
                assertThrows(AssertionError.class, () -> q.compareParams("svc", "C", "m", "/p", expected, actual2));

                // 3) extra param
                var ActExtra = new QueryParamContractDriftTest.ParamSpec("extra", false, null,
                                QueryParamContractDriftTest.ParamType.STRING);
                var actual3 = new HashMap<>(expected);
                actual3.put(ActExtra.name(), ActExtra);
                assertThrows(AssertionError.class, () -> q.compareParams("svc", "C", "m", "/p", expected, actual3));

                // 4) type changed
                var ActPageWrongType = new QueryParamContractDriftTest.ParamSpec("page", false, "0",
                                QueryParamContractDriftTest.ParamType.LONG);
                var actual4 = Map.of(ActSame.name(), ActSame, ActPageWrongType.name(), ActPageWrongType);
                assertThrows(AssertionError.class, () -> q.compareParams("svc", "C", "m", "/p", expected, actual4));

                // 5) required changed
                var ActQRequired = new QueryParamContractDriftTest.ParamSpec("q", true, null,
                                QueryParamContractDriftTest.ParamType.STRING);
                var actual5 = Map.of(ActQRequired.name(), ActQRequired, ActPageChangedDefault.name(),
                                ActPageChangedDefault);
                assertThrows(AssertionError.class, () -> q.compareParams("svc", "C", "m", "/p", expected, actual5));

                // 6) matching -> no exception
                var actualOk = Map.of(Exp.name(), Exp, ExpPage.name(), ExpPage);
                assertDoesNotThrow(() -> q.compareParams("svc", "C", "m", "/p", expected, actualOk));
        }

        @Test
        @DisplayName("canonicalizePaths handles multi class-level x multi method-level mappings")
        void canonicalizeMultiMappings() throws NoSuchMethodException {
                QueryParamContractDriftTest q = new QueryParamContractDriftTest();

                class T {
                        @GetMapping({ "/m1", "/m2" })
                        public void m() {
                        }
                }

                GetMapping gm = T.class.getMethod("m").getAnnotation(GetMapping.class);
                List<String> paths = q.canonicalizePaths(new String[] { "/c1", "/c2" }, gm);
                // Expect 4 combinations
                assertEquals(4, paths.size());
                assertEquals(true, paths.contains("/c1/m1"));
                assertEquals(true, paths.contains("/c1/m2"));
                assertEquals(true, paths.contains("/c2/m1"));
                assertEquals(true, paths.contains("/c2/m2"));
        }

        @Test
        @DisplayName("parameter filtering ignores non-query params")
        void parameterFilteringIgnoresNonQuery() throws NoSuchMethodException {
                class T {
                        public void m(@RequestParam(name = "q") String q, java.security.Principal principal,
                                        ServerHttpRequest req, @RequestHeader("h") String header) {
                        }
                }

                java.lang.reflect.Parameter[] params = T.class
                                .getMethod("m", String.class, java.security.Principal.class,
                                                ServerHttpRequest.class, String.class)
                                .getParameters();
                // First parameter has RequestParam
                assertEquals(true, params[0].isAnnotationPresent(RequestParam.class));
                // Second parameter should not be treated as RequestParam
                assertEquals(false, params[1].isAnnotationPresent(RequestParam.class));
                // Third parameter is HttpServletRequest - not a request param
                assertEquals(false, params[2].isAnnotationPresent(RequestParam.class));
                // Fourth parameter has RequestHeader - not a request param
                assertEquals(false, params[3].isAnnotationPresent(RequestParam.class));
        }
}
