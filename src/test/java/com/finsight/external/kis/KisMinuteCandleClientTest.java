package com.finsight.external.kis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.regex.Pattern;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class KisMinuteCandleClientTest {

    private MockWebServer server;
    private KisTokenProvider tokenProvider;
    private KisMinuteCandleClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        tokenProvider = mock(KisTokenProvider.class);
        when(tokenProvider.getAccessToken()).thenReturn(Optional.of("test-token"));
        client = new KisMinuteCandleClient(
                WebClient.builder(), tokenProvider, server.url("/").toString(), "test-key", "test-secret");
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void sendsRequiredKisCursorAndParsesLiveCandleShape() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("""
                {"rt_cd":"0","output2":[
                  {"stck_bsop_date":"20260925","stck_cntg_hour":"152900","stck_oprc":"100","stck_hgpr":"110","stck_lwpr":"90","stck_prpr":"105"},
                  {"stck_bsop_date":"20260925","stck_cntg_hour":"152959","stck_oprc":"105","stck_hgpr":"120","stck_lwpr":"100","stck_prpr":"115"}
                ]}
                """));

        KisMinuteCandleResult result = client.getCandlesWithStatus("005930", 5, 10);

        assertFalse(result.fallback());
        assertEquals(1, result.candles().size());
        assertEquals(100.0, result.candles().getFirst().open());
        assertEquals(120.0, result.candles().getFirst().high());
        assertEquals(90.0, result.candles().getFirst().low());
        assertEquals(115.0, result.candles().getFirst().close());

        var request = server.takeRequest();
        assertEquals("FHKST03010200", request.getHeader("tr_id"));
        assertTrue(request.getPath().contains("FID_INPUT_HOUR_1="));
        String cursor = request.getPath().replaceAll(".*FID_INPUT_HOUR_1=([^&]+).*", "$1");
        assertTrue(Pattern.matches("\\d{6}", cursor));
        assertFalse(request.getPath().contains("FID_INPUT_HOUR_1=&"));
        assertTrue(request.getPath().contains("FID_PW_DATA_INCU_YN=Y"));
    }

    @Test
    void marksKisBusinessErrorsAsFallbackInsteadOfTreatingThemAsLive() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"rt_cd\":\"1\",\"msg_cd\":\"OPSQ0003\",\"msg1\":\"Service routing error\"}"));

        KisMinuteCandleResult result = client.getCandlesWithStatus("005930", 1, 5);

        assertTrue(result.fallback());
        assertEquals(5, result.candles().size());
    }

    @Test
    void marksKisTimeoutAsFallback() {
        server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));

        KisMinuteCandleResult result = client.getCandlesWithStatus("005930", 1, 5);

        assertTrue(result.fallback());
        assertEquals(5, result.candles().size());
    }
}
