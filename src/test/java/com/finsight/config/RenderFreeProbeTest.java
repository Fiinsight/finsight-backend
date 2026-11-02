package com.finsight.config;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;

class RenderFreeProbeTest {
    static final CountDownLatch entered = new CountDownLatch(1);
    static final CountDownLatch release = new CountDownLatch(1);

    @Test void slowDatabaseReadinessDoesNotBlockProcessLiveness() throws Exception {
        try (var context = new org.springframework.boot.builder.SpringApplicationBuilder(Application.class)
                .profiles("render-free").properties("server.port=0").run()) {
        int port = ((org.springframework.boot.web.context.WebServerApplicationContext) context).getWebServer().getPort();
        var http = new TestRestTemplate();
        String base = "http://127.0.0.1:" + port;
        var readiness = CompletableFuture.supplyAsync(() -> http.getForEntity(base + "/actuator/health/readiness", String.class));
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var liveness = CompletableFuture.supplyAsync(() -> http.getForEntity(base + "/actuator/health/liveness", String.class));
            assertEquals(200, liveness.get(2, TimeUnit.SECONDS).getStatusCode().value());
            assertFalse(readiness.isDone(), "The database probe is still intentionally blocked");
        } finally { release.countDown(); }
        assertEquals(200, readiness.get(10, TimeUnit.SECONDS).getStatusCode().value());
        }
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(excludeName = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
        "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.security.servlet.ManagementWebSecurityAutoConfiguration"
    })
    static class Application {
        @Bean HealthIndicator dbHealthIndicator() { return () -> {
            entered.countDown();
            try { release.await(15, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return Health.up().build();
        }; }
        @Bean HealthIndicator redisHealthIndicator() { return () -> Health.up().build(); }
    }
}
