package com.finsight.chart;

import static org.assertj.core.api.Assertions.assertThat;

import com.finsight.news.News;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChartServiceTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"머스크 테라팹에 TSMC도 참여 인텔주가 하락", "은행들 금리 오르기전에 빨리 채권 찍자", "아산시 삼성발 훈풍 투자유치설명회", "수출길 막힐라 베트남 시장", "전남광주 산업지도", "진코스텍 코스닥 상장", "키움운용 코스닥150 분배금"})
    void unrelatedNewsIsNotDirectOrOtherMarket(String title) {
        var news = new News(title, "https://fixture.test", "fixture", Instant.EPOCH, "삼성 사업을 소개했다.");
        assertThat(ChartService.relevanceRank("005930", news)).isZero();
    }
    @Test void periodHeadersReuseCachedQuoteWithoutAnyQuoteRequest() {
        var manager = new org.springframework.cache.concurrent.ConcurrentMapCacheManager("popularStockQuotes", "kisStockQuotes");
        manager.getCache("popularStockQuotes").put("all", List.of(new com.finsight.stock.PopularStockView("005930", "삼성전자", 257500, -1.72, false)));
        var service = new ChartService(null, null, null, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "cacheManager", manager);
        for (String period : List.of("D", "W", "MINUTE")) {
            var chart = new ChartResponse("005930", 1, 2, List.of(), List.of(), List.of(), null, period, null, List.of(), false, List.of());
            var result = service.withCurrentQuote(chart);
            assertThat(result.price()).isEqualTo(257500); assertThat(result.changePercent()).isEqualTo(-1.72);
        }
    }
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
        org.mockito.Mockito.when(repository.findByPublishedAtBetween(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of(stock,market));
        var service = new ChartService(daily, minute, repository, new com.finsight.news.collect.ArticleContentExtractor(), new com.finsight.news.collect.NewsSymbolMatcher());
        assertThat(service.getChart("064350").relatedNews()).extracting(ChartResponse.NewsMarkerView::title)
                .containsExactly("코스피 시장 동향", "현대로템 실적 전망");
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

    @Test
    void samsungDailyThirtyBarsReturnFiveLargestMovesAndShortWeeklySeriesWorks() {
        // Actual saved Samsung 005930 API response, 2026-08-21..2026-10-06; no external calls.
        String[] dates = {"2026-08-21", "2026-08-24", "2026-08-25", "2026-08-26", "2026-08-27", "2026-08-28", "2026-08-31", "2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04", "2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10", "2026-09-11", "2026-09-14", "2026-09-15", "2026-09-16", "2026-09-17", "2026-09-18", "2026-09-21", "2026-09-22", "2026-09-23", "2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-06"};
        double[] closes = {281500.0, 257000.0, 257000.0, 261500.0, 266000.0, 257000.0, 260000.0, 261000.0, 250500.0, 250000.0, 255500.0, 270000.0, 269500.0, 269500.0, 269000.0, 259500.0, 249000.0, 248500.0, 253500.0, 252500.0, 261000.0, 274000.0, 276500.0, 285500.0, 270000.0, 272500.0, 268500.0, 276000.0, 276000.0, 272500.0};
        var repository = org.mockito.Mockito.mock(com.finsight.news.NewsRepository.class);
        var daily = org.mockito.Mockito.mock(com.finsight.external.kis.KisDailyCandleClient.class);
        var minute = org.mockito.Mockito.mock(com.finsight.external.kis.KisMinuteCandleClient.class);
        var bars = new java.util.ArrayList<com.finsight.external.kis.KisDailyCandle>();
        int absoluteMoves = 0;
        for (int i = 0; i < dates.length; i++) {
            bars.add(new com.finsight.external.kis.KisDailyCandle(java.time.LocalDate.parse(dates[i]), closes[i], closes[i], closes[i], closes[i]));
            if (i > 0 && Math.abs((closes[i] / closes[i - 1] - 1) * 100) >= 3) absoluteMoves++;
        }
        assertThat(absoluteMoves).isEqualTo(10);
        org.mockito.Mockito.when(daily.getCandlesWithStatus("005930", 30, "D"))
                .thenReturn(new com.finsight.external.kis.KisDailyCandleResult(bars, false));
        var service = new ChartService(daily, minute, repository, new com.finsight.news.collect.ArticleContentExtractor(), new com.finsight.news.collect.NewsSymbolMatcher());
        assertThat(service.getChart("005930", "D", 5).moveInsights()).extracting(i -> i.timestamp().toLocalDate().toString())
                .containsExactly("2026-08-24", "2026-09-07", "2026-09-28", "2026-09-21", "2026-09-14");
        org.mockito.Mockito.when(repository.collectionStart()).thenReturn(Instant.parse("2026-09-22T14:05:00Z"));
        var prioritized = service.getChart("005930", "D", 5).moveInsights();
        assertThat(prioritized).extracting(i -> i.timestamp().toLocalDate().toString())
                .containsExactly("2026-09-28", "2026-09-23", "2026-08-24", "2026-09-07", "2026-09-21");
        assertThat(prioritized.get(2).explanation()).isEqualTo("뉴스 수집(9/22) 이전 구간이에요");
        org.mockito.Mockito.when(daily.getCandlesWithStatus("005930", 7, "W"))
                .thenReturn(new com.finsight.external.kis.KisDailyCandleResult(List.of(
                    new com.finsight.external.kis.KisDailyCandle(java.time.LocalDate.of(2026, 9, 14), 100, 100, 100, 100),
                    new com.finsight.external.kis.KisDailyCandle(java.time.LocalDate.of(2026, 9, 21), 106, 106, 106, 106)), false));
        assertThat(service.getChart("005930", "W", 5).moveInsights()).hasSize(1);
    }

    @Test
    void september28CandidatesExcludeForeign59AndIncludeSameDayCompanyNews() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var rows = mapper.readTree(getClass().getResourceAsStream("/chart-news-september.json"));
        var news = new java.util.ArrayList<News>();
        for (var row : rows) {
            var article = new News(row.get("title").asText(), row.get("url").asText(), row.get("source").asText(),
                java.time.OffsetDateTime.parse(row.get("published_at").asText()).toInstant(), row.get("raw_content").asText());
            org.springframework.test.util.ReflectionTestUtils.setField(article,"id",row.get("id").asLong());
            article.setRelatedSymbol(row.get("related_symbol").asText()); news.add(article);
        }
        var from = com.finsight.judgement.KoreanTradingCalendar.previous(java.time.LocalDate.parse("2026-09-28"))
            .atTime(15,30).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant();
        var to = java.time.LocalDate.parse("2026-09-28").atTime(15,30).atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant();
        var candidates = ChartService.candidateNews("005930",news,from,to);
        assertThat(candidates).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        assertThat(candidates.get(0).getId()).isIn(77L,91L,100L);
        assertThat(candidates).extracting(News::getId).doesNotContain(59L);
        var wrongSector = new News("은행들 채권 발행", "https://test", "매체", to.minusSeconds(1),"은행권 채권 발행");
        wrongSector.setRelatedSymbol("KOSPI");
        assertThat(ChartService.relevanceRank("005930",wrongSector)).isZero();
        assertThat(ChartService.candidateNews("005930",news,to.plusSeconds(1),to.plusSeconds(60))).isEmpty();
    }
}
