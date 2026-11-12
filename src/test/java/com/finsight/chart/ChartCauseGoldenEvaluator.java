package com.finsight.chart;

import com.fasterxml.jackson.databind.*;
import com.finsight.external.kis.*;
import com.finsight.news.*;
import com.finsight.news.collect.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Offline evaluator: only local JSON, mocked KIS/news, and no Spring/DB/AI startup. */
public final class ChartCauseGoldenEvaluator {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: input-private.json output-private.json");
        var mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var input = mapper.readTree(Path.of(args[0]).toFile());
        if (input.has("parityCases")) {
            var cases=new ArrayList<Map<String,Object>>();
            for (var item:input.path("parityCases")) {
                var request=mapper.treeToValue(item.path("request"),com.finsight.external.AiDocentRequest.class);
                cases.add(Map.of("id",item.path("id").asText(),"request",request,"expected",ChartDocentService.rule(request)));
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(),Map.of("cases",cases));
            return;
        }
        var news = new ArrayList<News>();
        for (var row : input.path("news")) {
            var n = new News(row.path("title").asText(), row.path("url").asText(), row.path("source").asText(),
                    Instant.parse(row.path("published_at").asText()), row.path("raw_content").asText());
            org.springframework.test.util.ReflectionTestUtils.setField(n, "id", row.path("id").asLong());
            n.setRelatedSymbol(row.path("related_symbol").isNull() ? null : row.path("related_symbol").asText());
            if (!row.path("sentiment_hint").isNull() && row.has("sentiment_hint")) n.setSentimentHint(com.finsight.briefing.SentimentHint.valueOf(row.path("sentiment_hint").asText()));
            if (!row.path("sentiment_confidence").isNull() && row.has("sentiment_confidence")) n.setSentimentConfidence(row.path("sentiment_confidence").asDouble());
            news.add(n);
        }
        var repository = mock(NewsRepository.class);
        ChartNewsRows.stub(repository, news);
        when(repository.findByPublishedAtBetween(any(), any())).thenAnswer(c -> news.stream()
                .filter(n -> !n.getPublishedAt().isBefore(c.getArgument(0)) && !n.getPublishedAt().isAfter(c.getArgument(1))).toList());
        when(repository.collectionStart()).thenReturn(news.stream().map(News::getPublishedAt).min(Comparator.naturalOrder()).orElse(null));
        var daily = mock(KisDailyCandleClient.class);
        var minute = mock(KisMinuteCandleClient.class);
        var series = new LinkedHashMap<String, List<KisDailyCandle>>();
        for (var row : input.path("series")) {
            if ("MINUTE".equals(row.path("period").asText())) {
                var minutes = new ArrayList<KisMinuteCandle>();
                for (var c : row.path("candles")) minutes.add(new KisMinuteCandle(LocalDateTime.parse(c.path("timestamp").asText()),
                        c.path("open").asDouble(), c.path("high").asDouble(), c.path("low").asDouble(), c.path("close").asDouble()));
                when(minute.getCandlesWithStatus(eq(row.path("symbol").asText()), anyInt(), anyInt()))
                        .thenReturn(new KisMinuteCandleResult(minutes, minutes.isEmpty()));
                continue;
            }
            var candles = new ArrayList<KisDailyCandle>();
            for (var c : row.path("candles")) candles.add(new KisDailyCandle(LocalDate.parse(c.path("date").asText()),
                    c.path("open").asDouble(), c.path("high").asDouble(), c.path("low").asDouble(), c.path("close").asDouble()));
            series.put(row.path("symbol").asText() + ":" + row.path("period").asText(), candles);
        }
        when(daily.getCandlesWithStatus(anyString(), anyInt(), anyString())).thenAnswer(c ->
                new KisDailyCandleResult(series.getOrDefault(c.getArgument(0) + ":" + c.getArgument(2), List.of()),
                        !series.containsKey(c.getArgument(0) + ":" + c.getArgument(2))));
        var service = new ChartService(daily, minute, repository,
                new ArticleContentExtractor(), new NewsSymbolMatcher());
        var charts = new ArrayList<Map<String, Object>>();
        for (var row : input.path("series")) {
            String symbol = row.path("symbol").asText(), period = row.path("period").asText();
            if (symbol.startsWith("KOS")) continue;
            ChartResponse chart=service.withDocents(service.getChart(symbol,period,5),"normal");
            charts.add(Map.of("symbol", symbol, "period", period, "response", chart));
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[1]).toFile(), Map.of("labelStatus", input.path("labelStatus").asText("DRAFT"), "charts", charts));
        System.out.println("Offline charts=" + charts.size() + ", sourceNews=" + news.size() + "; no DB, KIS, or AI requests.");
    }
}
