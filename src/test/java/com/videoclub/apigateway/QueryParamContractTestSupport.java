package com.videoclub.apigateway;

final class QueryParamContractTestSupport {
    private QueryParamContractTestSupport() {
    }

    static QueryParamContractDriftTest.ParamType mapType(Class<?> cls) {
        if (cls == String.class)
            return QueryParamContractDriftTest.ParamType.STRING;
        if (cls == int.class || cls == Integer.class)
            return QueryParamContractDriftTest.ParamType.INT;
        if (cls == long.class || cls == Long.class)
            return QueryParamContractDriftTest.ParamType.LONG;
        if (cls == boolean.class || cls == Boolean.class)
            return QueryParamContractDriftTest.ParamType.BOOLEAN;
        if (cls == java.math.BigDecimal.class)
            return QueryParamContractDriftTest.ParamType.DECIMAL;
        if (cls == java.time.LocalDate.class)
            return QueryParamContractDriftTest.ParamType.LOCAL_DATE;
        return QueryParamContractDriftTest.ParamType.UNKNOWN;
    }
}
