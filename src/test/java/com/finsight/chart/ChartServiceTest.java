package com.finsight.chart;

import static org.assertj.core.api.Assertions.assertThat;

import com.finsight.news.News;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChartServiceTest {

    @Test
    void directlyRelatedNewsComesBeforeNewerMarketNews() {
        var repository = org.mockito.Mockito.mock(com.finsight.news.NewsRepository.class);
        var daily = org.mockito.Mockito.mock(com.finsight.external.kis.KisDailyCandleClient.class);
        var minute = org.mockito.Mockito.mock(com.finsight.external.kis.KisMinuteCandleClient.class);
        var date = java.time.LocalDate.of(2026, 10, 6);
        var stock = new News("현대로템 실적 전망", "https://stock.example", "매체", Instant.parse("2026-10-06T01:00:00Z"), "실적 전망을 검토했다. ".repeat(30));
        stock.setRelatedSymbol("064350");
        var market = new News("코스피 시장 동향", "https://market.example", "매체", Instant.parse("2026-10-06T02:00:00Z"), "국내 증시의 흐름을 검토했다. ".repeat(30));
        market.setRelatedSymbol("KOSPI");
        org.mockito.Mockito.when(daily.getCandlesWithStatus("064350", 30, "D"))
                .thenReturn(new com.finsight.external.kis.KisDailyCandleResult(List.of(new com.finsight.external.kis.KisDailyCandle(date, 100, 110, 90, 105)), false));
        org.mockito.Mockito.when(repository.findByRelatedSymbolAndPublishedAtBetween(org.mockito.ArgumentMatchers.eq("064350"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of(stock));
        org.mockito.Mockito.when(repository.findByRelatedSymbolAndPublishedAtBetween(org.mockito.ArgumentMatchers.eq("KOSPI"), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of(market));
        org.mockito.Mockito.when(repository.findByPublishedAtBetween(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        var service = new ChartService(daily, minute, repository, new com.finsight.news.collect.ArticleContentExtractor(), new com.finsight.news.collect.NewsSymbolMatcher());
        assertThat(service.getChart("064350").relatedNews()).extracting(ChartResponse.NewsMarkerView::title)
                .containsExactly("현대로템 실적 전망", "코스피 시장 동향");
    }

    @Test
    void removesExactRelatedNewsDuplicates() {
        Instant publishedAt = Instant.parse("2026-09-29T01:00:00Z");
        News first = new News("삼성전자 신규 투자", "https://one.example/news", "매체", publishedAt, "같은 본문");
        News duplicate = new News("삼성전자 신규 투자", "https://two.example/news", "매체", publishedAt, "같은 본문");
        News other = new News("다른 기사", "https://three.example/news", "매체", publishedAt, "다른 본문");

        assertThat(ChartService.deduplicateRelatedNews(List.of(first, duplicate, other)))
                .containsExactly(first, other);
    }

    @Test
    void detectsOnlyMovesThatExceedRecentRelativeVolatility() {
        List<Double> stableChanges = List.of(0.10, -0.10, 0.08, -0.08, 0.10);

        assertThat(ChartService.isSignificantMove(stableChanges, 0.15)).isFalse();
        assertThat(ChartService.isSignificantMove(stableChanges, 0.50)).isTrue();
        assertThat(ChartService.isSignificantMove(new ArrayList<>(stableChanges).subList(0, 4), 0.50))
                .isFalse();
    }

    @Test
    void matchesOnlyTheLatestNewsPublishedBeforeTheMove() {
        Instant moveAt = Instant.parse("2026-09-29T02:00:00Z");
        News before = new News("이전 기사", "https://before.example", "매체", moveAt.minusSeconds(3600), "본문");
        News after = new News("이후 기사", "https://after.example", "매체", moveAt.plusSeconds(60), "본문");

        assertThat(ChartService.latestNewsBefore(List.of(before, after), moveAt))
                .isSameAs(before);
    }
}
