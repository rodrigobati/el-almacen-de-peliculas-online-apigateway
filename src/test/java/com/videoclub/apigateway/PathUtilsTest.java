package com.videoclub.apigateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PathUtilsTest {

    @Test
    @DisplayName("stripPrefix removes first segment when n=1")
    void strip1() {
        assertEquals("/peliculas",
                new com.videoclub.apigateway.CrossServiceContractDriftHardenedTest().stripPrefix("/api/peliculas", 1));
    }

    @Test
    @DisplayName("stripPrefix with zero returns same")
    void strip0() {
        assertEquals("/api/admin",
                new com.videoclub.apigateway.CrossServiceContractDriftHardenedTest().stripPrefix("/api/admin", 0));
    }

    @Test
    @DisplayName("stripPrefix removes N segments")
    void strip2() {
        assertEquals("/y",
                new com.videoclub.apigateway.CrossServiceContractDriftHardenedTest().stripPrefix("/api/x/y", 2));
    }

    @Test
    @DisplayName("derive base from path predicate")
    void deriveBase() {
        assertEquals("/api/peliculas", new com.videoclub.apigateway.CrossServiceContractDriftHardenedTest()
                .baseFromPredicate("Path=/api/peliculas/**"));
    }

}
