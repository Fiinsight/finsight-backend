package com.finsight.judgement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsight.auth.User;
import com.finsight.external.*;
import com.finsight.external.kis.*;
import com.finsight.news.*;
import java.time.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiFeedbackDeliveryTest {
    @Test void realResponseShapePassesWebClientIsSavedAtomicallyAndAppearsInDetail() throws Exception {
        String body=new String(getClass().getResourceAsStream("/ai_feedback_response.json").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        ExchangeFunction network=request -> {
            assertThat(request.url().getPath()).isEqualTo("/ai/feedback/judgement");
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type","application/json").body(body).build());
        };
        var client=new AiServiceClient(WebClient.builder().exchangeFunction(network),"https://example.invalid");
        var records=mock(JudgementRepository.class);var prices=mock(KisDailyCandleClient.class);var owner=mock(User.class);
        var news=new News("삼성전자 판매 계약 확대","https://example.test/news","fixture",Instant.parse("2026-10-02T01:00:00Z"),"삼성전자의 판매 계약이 늘었다.");
        news.setRelatedSymbol("005930");
        var row=spy(new Judgement(owner,news,JudgementChoice.UP,"계약 확대를 확인했어요"));row.setTargetSymbol("005930");
        when(row.getCreatedAt()).thenReturn(Instant.parse("2026-10-02T01:00:00Z"));when(row.getId()).thenReturn(77L);
        when(records.findPendingByOwner(eq(7L),any())).thenReturn(List.of(row));
        when(records.finalizeOutcome(any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);
        LocalDate baseline=LocalDate.parse("2026-10-02"),comparison=LocalDate.parse("2026-10-06");
        when(prices.getClosingCandles("005930",baseline,comparison)).thenReturn(candles(baseline,comparison,100,101.2));
        when(prices.getClosingCandles("KOSPI",baseline,comparison)).thenReturn(candles(baseline,comparison,1000,1002));
        var scheduler=new FeedbackScheduler(records,prices,client);
        org.springframework.test.util.ReflectionTestUtils.setField(scheduler,"clock",Clock.fixed(Instant.parse("2026-10-06T08:00:00Z"),ZoneId.of("Asia/Seoul")));
        assertThat(scheduler.runNow(7L)).isEqualTo(1);
        var expected=new ObjectMapper().readValue(body,AiFeedbackResponse.class);
        var saved=ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        verify(records).finalizeOutcome(eq(77L),any(),eq("UP"),any(),eq(expected.summary()),any(),eq(comparison),any(),any(),eq(expected.headline()),saved.capture(),eq("005930"));
        assertThat(saved.getValue().path("reasons").get(0).path("detail").asText()).isEqualTo(expected.reasons().getFirst().detail());
        assertThat(row.getFeedbackText()).isEqualTo(expected.summary()).doesNotContain("요약과 코칭을 합친 긴");
        when(records.findByIdAndUser(77L,owner)).thenReturn(Optional.of(row));
        var detail=new JudgementService(records,mock(NewsRepository.class)).getDetail(owner,77L);
        assertThat(detail.headline()).isEqualTo(expected.headline());
        assertThat(detail.reasons()).isEqualTo(expected.reasons());assertThat(detail.nextTip()).isEqualTo(expected.nextTip());
        verify(records,never()).save(any());
    }
    private KisDailyCandleResult candles(LocalDate before,LocalDate after,double first,double second) {
        return new KisDailyCandleResult(List.of(new KisDailyCandle(before,first,first,first,first),new KisDailyCandle(after,second,second,second,second)),false);
    }
}
