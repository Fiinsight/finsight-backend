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
    void samsungDailyBarsUseAdaptiveThresholdAndRecencyAndShortWeeklySeriesWorks() {
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
                .containsExactly("2026-09-28", "2026-08-24", "2026-08-28");
        org.mockito.Mockito.when(repository.collectionStart()).thenReturn(Instant.parse("2026-09-22T14:05:00Z"));
        var prioritized = service.getChart("005930", "D", 5).moveInsights();
        assertThat(prioritized).extracting(i -> i.timestamp().toLocalDate().toString())
                .containsExactly("2026-09-28", "2026-08-24", "2026-08-28");
        assertThat(prioritized.get(1).explanation()).isEqualTo("뉴스 수집(9/22) 이전 구간이에요");
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

    @Test void reportsSavedRealDataMoveCoverage() throws Exception {
        String[] dates = {"2026-08-21", "2026-08-24", "2026-08-25", "2026-08-26", "2026-08-27", "2026-08-28", "2026-08-31", "2026-09-01", "2026-09-02", "2026-09-03", "2026-09-04", "2026-09-07", "2026-09-08", "2026-09-09", "2026-09-10", "2026-09-11", "2026-09-14", "2026-09-15", "2026-09-16", "2026-09-17", "2026-09-18", "2026-09-21", "2026-09-22", "2026-09-23", "2026-09-28", "2026-09-29", "2026-09-30", "2026-10-01", "2026-10-02", "2026-10-06"};
        double[] closes = {281500.0, 257000.0, 257000.0, 261500.0, 266000.0, 257000.0, 260000.0, 261000.0, 250500.0, 250000.0, 255500.0, 270000.0, 269500.0, 269500.0, 269000.0, 259500.0, 249000.0, 248500.0, 253500.0, 252500.0, 261000.0, 274000.0, 276500.0, 285500.0, 270000.0, 272500.0, 268500.0, 276000.0, 276000.0, 272500.0};
        var repository = org.mockito.Mockito.mock(com.finsight.news.NewsRepository.class);
        var daily = org.mockito.Mockito.mock(com.finsight.external.kis.KisDailyCandleClient.class);
        var bars = new java.util.ArrayList<com.finsight.external.kis.KisDailyCandle>();
        for (int i=0;i<dates.length;i++) bars.add(new com.finsight.external.kis.KisDailyCandle(java.time.LocalDate.parse(dates[i]),closes[i],closes[i],closes[i],closes[i]));
        var rows = new com.fasterxml.jackson.databind.ObjectMapper().readTree(getClass().getResourceAsStream("/chart-news-september.json"));
        var articles = new java.util.ArrayList<News>();
        for (var row:rows) {
            var article = new News(row.get("title").asText(),row.get("url").asText(),row.get("source").asText(),java.time.OffsetDateTime.parse(row.get("published_at").asText()).toInstant(),row.get("raw_content").asText());
            org.springframework.test.util.ReflectionTestUtils.setField(article,"id",row.get("id").asLong());
            article.setRelatedSymbol(row.get("related_symbol").asText()); articles.add(article);
        }
        org.mockito.Mockito.when(repository.findByPublishedAtBetween(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> articles.stream().filter(n -> !n.getPublishedAt().isBefore(invocation.getArgument(0)) && n.getPublishedAt().isBefore(invocation.getArgument(1))).toList());
        org.mockito.Mockito.when(repository.collectionStart()).thenReturn(articles.stream().map(News::getPublishedAt).min(java.util.Comparator.naturalOrder()).orElseThrow());
        org.mockito.Mockito.when(daily.getCandlesWithStatus("005930",30,"D")).thenReturn(new com.finsight.external.kis.KisDailyCandleResult(bars,false));
        var service = new ChartService(daily,null,repository,new com.finsight.news.collect.ArticleContentExtractor(),new com.finsight.news.collect.NewsSymbolMatcher());
        var moves=service.getChart("005930","D",5).moveInsights();
        System.out.println("SAVED_REAL_DATA bars="+bars.size()+" news="+articles.size()+" returnedMoves="+moves.size()+" linkedMoves="+moves.stream().filter(m -> m.newsId()!=null).count()+" uniqueNews="+moves.stream().flatMap(m -> m.candidateNews().stream()).map(ChartResponse.NewsMarkerView::newsId).distinct().count()+" dates="+moves.stream().map(m -> m.timestamp().toLocalDate()).toList());
        assertThat(moves).hasSizeLessThanOrEqualTo(5);
    }

    @Test void dailyAndWeeklyWindowsFollowActualTradingCloses() {
        var zone=java.time.ZoneId.of("Asia/Seoul");
        var monday=ChartService.moveWindow(java.time.LocalDate.parse("2026-10-12"),"D");
        assertThat(monday.from().atZone(zone).toLocalDateTime()).isEqualTo(java.time.LocalDateTime.parse("2026-10-08T15:30:00"));
        assertThat(monday.to().atZone(zone).toLocalDateTime()).isEqualTo(java.time.LocalDateTime.parse("2026-10-12T15:30:00"));
        var holidayWeek=ChartService.moveWindow(java.time.LocalDate.parse("2026-09-21"),"W");
        assertThat(holidayWeek.from().atZone(zone).toLocalDateTime()).isEqualTo(java.time.LocalDateTime.parse("2026-09-18T15:30:00"));
        assertThat(holidayWeek.to().atZone(zone).toLocalDateTime()).isEqualTo(java.time.LocalDateTime.parse("2026-09-23T15:30:00"));
    }
    @Test void overnightCauseAndPostCloseReferenceAreSeparated() {
        var repository=org.mockito.Mockito.mock(com.finsight.news.NewsRepository.class);
        var daily=org.mockito.Mockito.mock(com.finsight.external.kis.KisDailyCandleClient.class);
        var articles=new ArrayList<News>();
        for (String time:List.of("2026-10-08T11:00:00Z","2026-10-12T04:00:00Z","2026-10-12T06:31:00Z")) {
            String headline=List.of("삼성전자 실적 발표", "삼성전자 대규모 수출계약 체결", "삼성전자 주가 상승 마감 분석").get(articles.size());
            var article=new News(headline,"https://fixture.test/"+time,"fixture",Instant.parse(time),(headline+". 기업 투자 소식을 전했다. ").repeat(30));
            org.springframework.test.util.ReflectionTestUtils.setField(article,"id",(long)articles.size()+1); article.setRelatedSymbol("005930"); articles.add(article);
        }
        org.mockito.Mockito.when(repository.findByPublishedAtBetween(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any())).thenReturn(articles);
        org.mockito.Mockito.when(daily.getCandlesWithStatus("005930",30,"D")).thenReturn(new com.finsight.external.kis.KisDailyCandleResult(List.of(
            new com.finsight.external.kis.KisDailyCandle(java.time.LocalDate.parse("2026-10-08"),100,100,100,100),
            new com.finsight.external.kis.KisDailyCandle(java.time.LocalDate.parse("2026-10-12"),103,103,103,103)),false));
        var service=new ChartService(daily,null,repository,new com.finsight.news.collect.ArticleContentExtractor(),new com.finsight.news.collect.NewsSymbolMatcher());
        var move=service.getChart("005930").moveInsights().getFirst();
        assertThat(move.candidateNews().stream().filter(n -> !"REFERENCE".equals(n.relationType())).map(ChartResponse.NewsMarkerView::newsId)).containsExactly(1L,2L);
        assertThat(move.candidateNews().stream().filter(n -> "REFERENCE".equals(n.relationType())).map(ChartResponse.NewsMarkerView::newsId)).containsExactly(3L);
        assertThat(move.newsId()).isIn(1L,2L); assertThat(move.docent().newsId()).isIn(1L,2L);
    }
    @Test void minuteDetectionFindsSlowAccumulationAndOpeningGapWithoutInventingPreviousClose() {
        var day=java.time.LocalDate.parse("2026-10-12");
        var bars=new ArrayList<com.finsight.external.kis.KisMinuteCandle>();
        for (int i=0;i<8;i++) { double price=100+i*.4; bars.add(new com.finsight.external.kis.KisMinuteCandle(day.atTime(9,0).plusMinutes(i*5),price,price,price,price)); }
        var moves=ChartService.minuteMoves(bars,java.util.Map.of(java.time.LocalDate.parse("2026-10-08"),100.0));
        assertThat(moves).anyMatch(m -> m.kind().equals("전일 종가 대비 누적"));
        assertThat(moves).anyMatch(m -> m.kind().equals("30분 누적"));
        assertThat(moves).noneMatch(m -> m.kind().equals("분봉 변동"));
        var gap=List.of(new com.finsight.external.kis.KisMinuteCandle(java.time.LocalDate.parse("2026-10-08").atTime(15,30),100,100,100,100),new com.finsight.external.kis.KisMinuteCandle(day.atTime(9,0),103,103,103,103));
        assertThat(ChartService.minuteMoves(gap,java.util.Map.of())).anyMatch(m -> m.kind().equals("개장 갭") && Math.abs(m.change()-3)<.001);
        assertThat(ChartService.minuteMoves(List.of(gap.getLast()),java.util.Map.of())).isEmpty();
    }
    @Test void volatilityFloorAndRecencyKeepMeaningfulRecentMoves() {
        assertThat(ChartService.moveThreshold(List.of(.1,-.1,.1,-.1,.1),2,2)).isEqualTo(2);
        assertThat(ChartService.moveThreshold(List.of(3.0,-3.0,3.0,-3.0,3.0),2,2)).isGreaterThan(5).isLessThanOrEqualTo(6);
        var now=java.time.LocalDateTime.parse("2026-10-12T15:30:00");
        var moves=new ArrayList<ChartResponse.MoveInsightView>();
        moves.add(new ChartResponse.MoveInsightView(now,2.1,null,null,null,"",0,List.of(),null));
        for(int i=15;i<21;i++) moves.add(new ChartResponse.MoveInsightView(now.minusDays(i),5,null,null,null,"",0,List.of(),null));
        assertThat(ChartService.recentMoves(moves,now)).hasSize(5).first().extracting(ChartResponse.MoveInsightView::timestamp).isEqualTo(now);
    }
    @Test void companyLeadSymbolAndEnglishAliasesKeepNeutralDisclosureCandidates() {
        var at=Instant.parse("2026-10-12T01:00:00Z");
        var lead=new News("대규모 공급 계약 공시","https://fixture.test/lead","fixture",at,"samsung electronics가 공급 계약을 공시했다.");
        var tagged=new News("분기 실적 발표","https://fixture.test/tag","fixture",at,"분기 매출 10조원을 발표했다."); tagged.setRelatedSymbol("005930");
        assertThat(ChartService.candidateNews("005930",List.of(lead,tagged),at.minusSeconds(1),at.plusSeconds(1),2)).contains(lead,tagged);
        assertThat(com.finsight.stock.StockCatalog.mentions("000660","SK HYNIX reports earnings")).isTrue();
    }
}
