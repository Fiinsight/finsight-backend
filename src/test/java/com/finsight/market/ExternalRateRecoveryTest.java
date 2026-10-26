package com.finsight.market;

import static org.assertj.core.api.Assertions.assertThat;

import com.finsight.external.EcosClient;
import com.finsight.external.NaverFxClient;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class ExternalRateRecoveryTest {
    private WebClient.Builder recoveringClient(AtomicInteger calls, String body) {
        return WebClient.builder().exchangeFunction(request -> {
            if (calls.getAndIncrement() == 0) return Mono.error(new IllegalStateException("temporary outage"));
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json").body(body).build());
        });
    }

    @Test
    void baseRateRetriesAfterFailureAndCachesRecoveredValue() {
        var calls = new AtomicInteger();
        var client = new EcosClient(recoveringClient(calls,
                "{\"StatisticSearch\":{\"row\":[{\"DATA_VALUE\":\"3.0\",\"TIME\":\"202610\"}]}}"),
                "https://example.com", "test-key");
        assertThat(client.getBaseRate().fallback()).isTrue();
        assertThat(client.getBaseRate().fallback()).isFalse();
        assertThat(client.getBaseRate().value()).isEqualTo(3.0);
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void exchangeRateRetriesAfterFailureAndCachesRecoveredValue() {
        var calls = new AtomicInteger();
        var client = new NaverFxClient(recoveringClient(calls,
                "{\"exchangeInfo\":{\"closePrice\":\"1,324.4\",\"fluctuationsRatio\":\"-0.12\",\"localTradedAt\":\"2026-10-26T12:00:00+09:00\"}}"),
                "https://example.com");
        assertThat(client.getUsdKrwRate().fallback()).isTrue();
        assertThat(client.getUsdKrwRate().fallback()).isFalse();
        assertThat(client.getUsdKrwRate().value()).isEqualTo(1324.4);
        assertThat(calls.get()).isEqualTo(2);
    }
}
