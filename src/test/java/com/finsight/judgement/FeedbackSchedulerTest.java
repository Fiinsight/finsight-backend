package com.finsight.judgement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import com.finsight.auth.User;
import com.finsight.external.AiFeedbackResponse;
import com.finsight.external.AiServiceClient;
import com.finsight.external.kis.KisStockQuote;
import com.finsight.external.kis.KisStockQuoteClient;
import com.finsight.news.News;
import java.time.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FeedbackSchedulerTest {
    private final JudgementRepository records = mock(JudgementRepository.class);
    private final KisStockQuoteClient quotes = mock(KisStockQuoteClient.class);
    private final AiServiceClient ai = mock(AiServiceClient.class);
    private final FeedbackScheduler scheduler = new FeedbackScheduler(records, quotes, ai);

    private Judgement pending(String symbol) {
        News news = spy(new News("QA 학습용 기사", "https://example.invalid/qa", "SYNTHETIC_QA", Instant.now(), "QA"));
        when(news.getId()).thenReturn(115L);
        news.setRelatedSymbol(symbol);
        Judgement item = new Judgement(mock(User.class), news, JudgementChoice.UP, "QA 테스트 근거");
        when(records.findByFeedbackGeneratedAtIsNull()).thenReturn(List.of(item));
        return item;
    }
    @Test void savesQuoteFieldsAndFreeAiFeedback() {
        Judgement item = pending("005930");
        when(quotes.getStockQuote("005930")).thenReturn(new KisStockQuote("005930", 100, 1.25, false));
        when(ai.generateFeedback(any())).thenReturn(Optional.of(new AiFeedbackResponse("예상 UP과 실제 UP을 비교해보세요.")));
        assertThat(scheduler.runNow()).isEqualTo(1);
        assertThat(item.getActualDirection()).isEqualTo("UP");
        assertThat(item.getActualChangePercent()).isEqualTo(1.25);
        assertThat(item.getFeedbackText()).contains("상승").doesNotContain("UP");
        assertThat(item.getFeedbackGeneratedAt()).isNotNull();
        verify(records).save(item);
    }
    @Test void missingAiStillSavesDirectionsAndKoreanFeedback() {
        for (double change : new double[]{1.0, 0.5, -1.0}) {
            Judgement item = pending("005930");
            when(quotes.getStockQuote("005930")).thenReturn(new KisStockQuote("005930", 100, change, false));
            when(ai.generateFeedback(any())).thenReturn(Optional.empty());
            assertThat(scheduler.runNow()).isEqualTo(1);
            assertThat(item.getActualDirection()).isEqualTo(change > 0.5 ? "UP" : change < -0.5 ? "DOWN" : "NEUTRAL");
            assertThat(item.getActualChangePercent()).isEqualTo(change);
            assertThat(item.getFeedbackText()).isNotBlank().doesNotContain("UP", "DOWN", "NEUTRAL");
        }
    }
    @Test void unavailableQuotesNeverPersistAnOutcome() {
        for (KisStockQuote quote : List.of(new KisStockQuote("005930", 0, 0, true), new KisStockQuote("005930", 100, Double.NaN, false))) {
            Judgement item = pending("005930");
            when(quotes.getStockQuote("005930")).thenReturn(quote);
            assertThat(scheduler.runNow()).isZero();
            assertThat(item.getActualDirection()).isNull();
            assertThat(item.getActualChangePercent()).isNull();
            assertThat(item.getFeedbackGeneratedAt()).isNull();
        }
        verify(records, never()).save(any());
        verifyNoInteractions(ai);
    }
    @Test void missingSymbolIsUnknownRatherThanIncorrect() {
        Judgement item = pending(null);
        assertThat(scheduler.runNow()).isEqualTo(1);
        assertThat(item.getActualDirection()).isEqualTo("UNKNOWN");
        assertThat(item.getActualChangePercent()).isNull();
        assertThat(item.getFeedbackText()).contains("비교하지 않습니다").doesNotContain("달랐습니다");
        verifyNoInteractions(quotes, ai);
    }
    @Test void scheduledRunExcludesTodaysJudgements() {
        when(records.findByFeedbackGeneratedAtIsNullAndCreatedAtBefore(any())).thenReturn(List.of());
        scheduler.generatePendingFeedback();
        var cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(records).findByFeedbackGeneratedAtIsNullAndCreatedAtBefore(cutoff.capture());
        assertThat(cutoff.getValue()).isEqualTo(LocalDate.now(ZoneId.of("Asia/Seoul")).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant());
        verify(records, never()).findByFeedbackGeneratedAtIsNull();
        verifyNoInteractions(quotes, ai);
    }
}
