package com.finsight.chart;

import com.finsight.chart.ChartResponse.CandleView;
import com.finsight.chart.ChartResponse.NewsMarkerView;
import com.finsight.external.kis.KisDailyCandle;
import com.finsight.external.kis.KisDailyCandleClient;
import com.finsight.external.kis.KisDailyCandleResult;
import com.finsight.external.kis.KisMinuteCandleClient;
import com.finsight.external.kis.KisMinuteCandleResult;
import com.finsight.news.News;
import com.finsight.news.NewsRepository;
import com.finsight.briefing.SentimentHint;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.news.collect.NewsSymbolMatcher;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.cache.annotation.Cacheable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class ChartService {

    private static final Logger log = LoggerFactory.getLogger(ChartService.class);

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int DAILY_CANDLE_COUNT = 30;
    private static final int WEEKLY_CANDLE_COUNT = 7; // enough bars for a five-return baseline
    private static final int VOLATILITY_WINDOW = 20;
    private static final int MIN_VOLATILITY_OBSERVATIONS = 5;
    private static final double VOLATILITY_MULTIPLIER = 2.0;
    @org.springframework.beans.factory.annotation.Value("${finsight.chart.daily-min-move-percent:2.0}")
    private double dailyMinMovePercent = 2.0;
    @org.springframework.beans.factory.annotation.Value("${finsight.chart.weekly-min-move-percent:4.0}")
    private double weeklyMinMovePercent = 4.0;
    @org.springframework.beans.factory.annotation.Value("${finsight.chart.volatility-multiplier:2.0}")
    private double volatilityMultiplier = 2.0;
    @org.springframework.beans.factory.annotation.Value("${finsight.chart.market-following-excess-percent:1.0}")
    private double marketFollowingExcessPercent = com.finsight.judgement.JudgementFeedbackRules.MARKET_DIFFERENCE_PERCENT;

    private final KisDailyCandleClient kisDailyCandleClient;
    private final KisMinuteCandleClient kisMinuteCandleClient;
    private final NewsRepository newsRepository;
    private final ArticleContentExtractor articleContentExtractor;
    private final NewsSymbolMatcher newsSymbolMatcher;
    private final ChartIndexHistory indexHistory;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.cache.CacheManager cacheManager;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private ChartDocentService docentService;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.finsight.term.TermRepository termRepository;

    private com.finsight.external.kis.KisStockQuote cachedQuote(String symbol) {
        if (cacheManager != null) {
            var popular = cacheManager.getCache("popularStockQuotes");
            var all = popular == null ? null : popular.get("all");
            if (all != null && all.get() instanceof List<?> stocks) {
                for (var item : stocks) if (item instanceof com.finsight.stock.PopularStockView stock && symbol.equals(stock.symbol()))
                    return new com.finsight.external.kis.KisStockQuote(symbol, stock.price(), stock.changePercent(), stock.fallback());
            }
            var quotes = cacheManager.getCache("kisStockQuotes");
            var quote = quotes == null ? null : quotes.get(symbol, com.finsight.external.kis.KisStockQuote.class);
            if (quote != null) return quote;
        }
        return new com.finsight.external.kis.KisStockQuote(symbol, 0, 0, true);
    }

    public ChartService(KisDailyCandleClient kisDailyCandleClient,
                        KisMinuteCandleClient kisMinuteCandleClient,
                        NewsRepository newsRepository,
                        ArticleContentExtractor articleContentExtractor,
                        NewsSymbolMatcher newsSymbolMatcher) {
        this.kisDailyCandleClient = kisDailyCandleClient;
        this.kisMinuteCandleClient = kisMinuteCandleClient;
        this.newsRepository = newsRepository;
        this.articleContentExtractor = articleContentExtractor;
        this.newsSymbolMatcher = newsSymbolMatcher;
        this.indexHistory = new ChartIndexHistory(kisDailyCandleClient);
    }

    public ChartResponse withCurrentQuote(ChartResponse chart) {
        var quote = cachedQuote(chart.symbol());
        return new ChartResponse(chart.symbol(), quote.currentPrice(), quote.changePercent(), chart.candles(), chart.newsMarkers(), chart.relatedNews(), chart.docent(), chart.period(), chart.intervalMinutes(), chart.minuteCandles(), chart.fallback(), chart.moveInsights());
    }

    public ChartResponse getChart(String symbol) {
        return getChart(symbol, "D", 5);
    }

    /**
     * @param period "D"(일봉) or "W"(주봉)
     */
    public ChartResponse getChart(String symbol, String period) {
        return getChart(symbol, period, 5);
    }

    /** Price-only path: never waits for the news database or docent. */
    @Cacheable(cacheNames = "chartCandles", key = "#symbol + ':' + #period + ':' + (#period == 'MINUTE' || #period == 'M' ? #intervalMinutes : 0)", sync = true)
    public ChartResponse getCandles(String symbol, String period, int intervalMinutes) {
        long started = System.nanoTime();
        boolean minute = "MINUTE".equals(period) || "M".equals(period);
        int interval = intervalMinutes == 1 || intervalMinutes == 15 ? intervalMinutes : 5;
        String normalized = minute ? "MINUTE" : "W".equals(period) ? "W" : "D";
        List<CandleView> daily = List.of();
        List<ChartResponse.MinuteCandleView> intraday = List.of();
        boolean fallback;
        if (minute) {
            var result = kisMinuteCandleClient.getCandlesWithStatus(symbol, interval, 120);
            fallback = result.fallback();
            intraday = result.candles().stream().map(c -> new ChartResponse.MinuteCandleView(c.timestamp(), c.open(), c.high(), c.low(), c.close())).toList();
        } else {
            var result = kisDailyCandleClient.getCandlesWithStatus(symbol, "W".equals(normalized) ? WEEKLY_CANDLE_COUNT : DAILY_CANDLE_COUNT, normalized);
            fallback = result.fallback();
            daily = result.candles().stream().sorted(Comparator.comparing(KisDailyCandle::date))
                    .map(c -> new CandleView(c.date(), c.open(), c.high(), c.low(), c.close())).toList();
        }
        log.info("Chart timing stage=candles symbol={} period={} ms={} fallback={}", symbol, normalized, (System.nanoTime() - started) / 1_000_000, fallback);
        return new ChartResponse(symbol, 0, 0, daily, List.of(), List.of(), null, normalized, minute ? interval : null, intraday, fallback, List.of());
    }

    @Cacheable(cacheNames = "chartResponses", key = "#symbol + ' : ' + #period.toUpperCase() + ' : ' + ((#period.equalsIgnoreCase('MINUTE') || #period.equalsIgnoreCase('M')) ? #intervalMinutes : 0)", unless = "#result.fallback()")
    public ChartResponse getChart(String symbol, String period, int intervalMinutes) {
        var quote = cachedQuote(symbol);
        String normalized = "MINUTE".equalsIgnoreCase(period) || "M".equalsIgnoreCase(period) ? "MINUTE" : "W".equalsIgnoreCase(period) ? "W" : "D";
        int normalizedInterval = intervalMinutes == 1 || intervalMinutes == 15 ? intervalMinutes : 5;
        var cache = cacheManager == null ? null : cacheManager.getCache("chartCandles");
        String key = symbol + ":" + normalized + ":" + ("MINUTE".equals(normalized) ? normalizedInterval : 0);
        // Self-invocation bypasses Spring's cache proxy; use the same price-only cache explicitly.
        ChartResponse series = cache == null ? getCandles(symbol, normalized, normalizedInterval)
                : cache.get(key, () -> getCandles(symbol, normalized, normalizedInterval));
        if ("MINUTE".equalsIgnoreCase(period) || "M".equalsIgnoreCase(period)) {
            int interval = intervalMinutes == 1 || intervalMinutes == 5 || intervalMinutes == 15 ? intervalMinutes : 5;
            List<ChartResponse.MinuteCandleView> minuteCandles = series.minuteCandles();
            double price = quote.currentPrice();
            double changePercent = quote.changePercent();
            var moves=series.fallback()?List.<ChartResponse.MoveInsightView>of():findMoveInsights(symbol,minuteCandles.stream().map(c -> new com.finsight.external.kis.KisMinuteCandle(c.timestamp(), c.open(), c.high(), c.low(), c.close())).toList());
            return new ChartResponse(symbol, price, changePercent, List.of(), List.of(), List.of(), largestDocent(moves),
                    "MINUTE", interval, minuteCandles, series.fallback(),
                    moves);
        }

        String periodDivCode = "W".equalsIgnoreCase(period) ? "W" : "D";
        List<CandleView> candleViews = series.candles();

        double price = quote.currentPrice();
        double changePercent = quote.changePercent();

        List<News> matchedNews;
        if (candleViews.isEmpty()) {
            matchedNews = List.of();
        } else {
            LocalDate startDate = candleViews.get(0).date();
            LocalDate endDate = candleViews.get(candleViews.size() - 1).date();
            Instant start = moveWindow(startDate, periodDivCode).from();
            Instant end = referenceEnd(moveWindow(endDate,periodDivCode));

            matchedNews = deduplicateRelatedNews(findRelatedNews(symbol, start, end).stream()
                    .filter(news -> articleContentExtractor.isUsable(news.getTitle(), news.getRawContent()))
                    .filter(news -> relevanceRank(symbol, news) > 0)
                    .sorted(Comparator.comparing(News::getPublishedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                    .toList());
        }

        List<NewsMarkerView> markers = matchedNews.stream().map(news -> toMarker(symbol, news)).toList();
        List<ChartResponse.MoveInsightView> moveInsights = series.fallback()
                ? List.of()
                : findDailyMoveInsights(symbol, candleViews, matchedNews, periodDivCode);
        ChartResponse.DocentView docent = largestDocent(moveInsights);

        return new ChartResponse(symbol, price, changePercent, candleViews, markers, markers, docent,
                periodDivCode, null, List.of(), series.fallback(),
                moveInsights);
    }

    private List<ChartResponse.MoveInsightView> findDailyMoveInsights(
            String symbol, List<CandleView> candles, List<News> relatedNews, String period) {
        if (candles.size() < 2) {
            return List.of();
        }
        Instant collectionStart = newsRepository.collectionStart();
        List<ChartResponse.MoveInsightView> insights = new ArrayList<>();
        for (int i = 1; i < candles.size(); i++) {
            CandleView previous = candles.get(i - 1);
            CandleView current = candles.get(i);
            if (previous.close() == 0.0) {
                continue;
            }
            double change = (current.close() - previous.close()) / previous.close() * 100.0;
            List<Double> previousChanges = new ArrayList<>();
            for (int j = Math.max(1, i - VOLATILITY_WINDOW); j < i; j++) {
                CandleView before = candles.get(j - 1);
                CandleView prior = candles.get(j);
                if (before.close() != 0.0) {
                    previousChanges.add((prior.close() - before.close()) / before.close() * 100.0);
                }
            }
            if (Math.abs(change) < moveThreshold(previousChanges, "W".equals(period) ? weeklyMinMovePercent : dailyMinMovePercent, volatilityMultiplier)) {
                continue;
            }
            var window = moveWindow(current.date(), period);
            Instant from = window.from(), to = window.to();
            String context=marketContext(change,indexChange(symbol,window.to().atZone(KST).toLocalDateTime(),period),marketFollowingExcessPercent);
            List<News> candidates = moveCandidates(symbol, relatedNews, window, change, context);
            Optional<News> news = candidates.stream().findFirst();
            double causeScore = news.map(item -> (double) representativeScore(symbol, item, change)).orElse(0.0);
            var request = docentRequest(symbol, window.to().atZone(KST).toLocalDateTime(), change, period, candidates, "normal");
            var docent = ChartDocentService.rule(request);
            String explanation = news.isEmpty() && collectionStart != null && to.atZone(KST).toLocalDate().isBefore(collectionStart.atZone(KST).toLocalDate())
                    ? "뉴스 수집(" + collectionStart.atZone(KST).format(java.time.format.DateTimeFormatter.ofPattern("M/d")) + ") 이전 구간이에요" : docent.possibleReason();
            insights.add(new ChartResponse.MoveInsightView(
                    current.date().atStartOfDay(),
                    Math.round(change * 100.0) / 100.0,
                    news.map(News::getId).orElse(null),
                    news.map(News::getTitle).orElse(null),
                    news.map(News::getSource).orElse(null),
                    explanation,
                    causeScore,
                    withReferences(symbol, candidates, relatedNews, window),
                    docent,
                    request.indexChangePercent(),
                    request.indexChangePercent()==null?null:change-request.indexChangePercent(),
                    marketContext(change,request.indexChangePercent(),marketFollowingExcessPercent),
                    request
            ));
            log.info("Move cause assessment: date={}, change={}%, newsId={}, score={}",
                    current.date(), Math.round(change * 100.0) / 100.0, news.map(News::getId).orElse(null), causeScore);
        }
        return recentMoves(insights, candles.getLast().date().atTime(15,30));
    }

    record MoveWindow(Instant from, Instant to) {}
    static MoveWindow moveWindow(LocalDate day, String period) {
        LocalDate monday = day.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        LocalDate end = "W".equals(period) ? com.finsight.judgement.KoreanTradingCalendar.onOrBefore(monday.plusDays(4).isAfter(LocalDate.now(KST)) ? LocalDate.now(KST) : monday.plusDays(4)) : day;
        LocalDate start = com.finsight.judgement.KoreanTradingCalendar.previous("W".equals(period) ? monday : day);
        return new MoveWindow(start.atTime(15,30).atZone(KST).toInstant(), end.atTime(15,30).atZone(KST).toInstant());
    }
    private static Instant referenceEnd(MoveWindow window) {
        return com.finsight.judgement.KoreanTradingCalendar.next(window.to().atZone(KST).toLocalDate()).atTime(9,0).atZone(KST).toInstant();
    }
    private List<NewsMarkerView> withReferences(String symbol, List<News> causes, List<News> all, MoveWindow window) {
        Instant until = referenceEnd(window);
        var references = Stream.concat(causes.stream().filter(n -> !n.getPublishedAt().isBefore(window.to())),
                all.stream().filter(n -> n.getPublishedAt()!=null && !n.getPublishedAt().isBefore(window.to()) && n.getPublishedAt().isBefore(until) && relevanceRank(symbol,n)>0)
                    .sorted(Comparator.comparing(News::getPublishedAt).reversed()))
                .distinct().limit(3).map(n -> new NewsMarkerView(n.getPublishedAt().atZone(KST).toLocalDate(),n.getPublishedAt(),n.getId(),n.getTitle(),n.getSource(),"REFERENCE",true));
        return Stream.concat(causes.stream().filter(n -> n.getPublishedAt().isBefore(window.to())).map(n -> toMarker(symbol,n)),references).toList();
    }
    static boolean isAfterCloseExplanation(String symbol, News news, MoveWindow window) {
        if (news.getPublishedAt()==null || news.getPublishedAt().isBefore(window.to()) || !news.getPublishedAt().isBefore(referenceEnd(window))) return false;
        String title=java.util.Objects.toString(news.getTitle(),"");
        String body=com.finsight.localml.ChartCompanyMatcher.cleanBody(news.getRawContent());
        String evidence=title+" "+body.substring(0,Math.min(300,body.length()));
        return com.finsight.localml.ChartCompanyMatcher.reason(symbol,title,body.substring(0,Math.min(300,body.length())))!=null
                && java.util.regex.Pattern.compile("급등|급락|상승|하락|강세|약세").matcher(evidence).find()
                && java.util.regex.Pattern.compile("이날|오늘|마감|종가").matcher(evidence).find()
                && !java.util.regex.Pattern.compile("전망|예상|지난주|지난달|지난해").matcher(title).find();
    }
    static List<News> moveCandidates(String symbol,List<News> news,MoveWindow window,double change,String context) {
        var eligible=news.stream().filter(n -> n.getPublishedAt()!=null && (n.getPublishedAt().isBefore(window.to()) || isAfterCloseExplanation(symbol,n,window))).toList();
        var triggers=candidateNews(symbol,eligible,window.from(),window.to(),change,context);
        boolean explicitTrigger=triggers.stream().anyMatch(n -> relevanceRank(symbol,n)==3 && representativeScore(symbol,n,change)>=7
                && ChartTextEvidence.cause(symbol,window.to().atZone(KST).toLocalDateTime().toString(),"DIRECT",n.getTitle(),n.getRawContent())!=null);
        if (explicitTrigger) {
            var reports=candidateNews(symbol,eligible,window.to(),referenceEnd(window),change,context);
            return Stream.concat(triggers.stream(),reports.stream()).distinct().limit(3).toList();
        }
        return candidateNews(symbol,eligible,window.from(),referenceEnd(window),change,context);
    }
    static double moveThreshold(List<Double> changes, double minimum, double multiplier) {
        if (changes.size() < MIN_VOLATILITY_OBSERVATIONS) return minimum;
        double mean=changes.stream().mapToDouble(Double::doubleValue).average().orElse(0);
        double deviation=Math.sqrt(changes.stream().mapToDouble(v -> Math.pow(v-mean,2)).average().orElse(0));
        return Math.max(minimum, Math.min(minimum*3, multiplier*deviation));
    }
    static String marketContext(double change, Double index, double threshold) {
        if (index==null || !Double.isFinite(index)) return "UNKNOWN";
        if (change*index<0) return "OPPOSITE";
        return Math.abs(change-index)<threshold ? "FOLLOWING" : "INDIVIDUAL";
    }
    static List<ChartResponse.MoveInsightView> recentMoves(List<ChartResponse.MoveInsightView> moves, LocalDateTime latest) {
        return moves.stream().sorted(Comparator.comparingDouble((ChartResponse.MoveInsightView m) ->
            Math.abs(m.changePercent()) / (1 + Math.max(0, java.time.Duration.between(m.timestamp(),latest).toHours()) / 168.0)).reversed()
            .thenComparing(ChartResponse.MoveInsightView::timestamp,Comparator.reverseOrder())).limit(5).toList();
    }

    private List<ChartResponse.MoveInsightView> findMoveInsights(String symbol, List<com.finsight.external.kis.KisMinuteCandle> candles) {
        if (candles.size() < 2) {
            return List.of();
        }
        LocalDate startDate = candles.get(0).timestamp().toLocalDate();
        LocalDate endDate = candles.get(candles.size() - 1).timestamp().toLocalDate();
        List<News> relatedNews = findRelatedNews(
                symbol,
                com.finsight.judgement.KoreanTradingCalendar.previous(startDate).atTime(15,30).atZone(KST).toInstant(),
                endDate.plusDays(1).atStartOfDay(KST).toInstant()).stream()
                .filter(news -> articleContentExtractor.isUsable(news.getTitle(), news.getRawContent()))
                .toList();

        var daily = kisDailyCandleClient == null ? null : kisDailyCandleClient.getCandlesWithStatus(symbol,30,"D");
        var closes = new java.util.HashMap<LocalDate,Double>();
        if (daily != null && !daily.fallback()) daily.candles().forEach(c -> closes.put(c.date(),c.close()));
        List<ChartResponse.MoveInsightView> insights = new ArrayList<>();
        for (var move : minuteMoves(candles,closes)) {
            double change = move.change();
            LocalDateTime start=move.from().isBefore(move.at().toLocalDate().atStartOfDay())?move.from():move.at().toLocalDate().atStartOfDay();
            List<News> candidates = candidateNews(symbol, relatedNews, start.atZone(KST).toInstant(),
                move.at().atZone(KST).toInstant(), change);
            Optional<News> news = candidates.stream().findFirst();
            double causeScore = news.map(item -> (double) representativeScore(symbol, item, change)).orElse(0.0);
            var request=docentRequest(symbol, move.at(), change, "MINUTE", candidates, "normal");
            var docent = ChartDocentService.rule(request);
            String explanation = move.kind()+" · "+docent.possibleReason();
            insights.add(new ChartResponse.MoveInsightView(
                    move.at(),
                    Math.round(change * 100.0) / 100.0,
                    news.map(News::getId).orElse(null),
                    news.map(News::getTitle).orElse(null),
                    news.map(News::getSource).orElse(null),
                    explanation,
                    causeScore,
                    candidates.stream().map(item -> toMarker(symbol, item)).toList(),
                    docent,null,null,"UNKNOWN",request
            ));
            log.info("Move cause assessment: timestamp={}, change={}%, newsId={}, score={}",
                    move.at(), Math.round(change * 100.0) / 100.0, news.map(News::getId).orElse(null), causeScore);
        }
        return recentMoves(insights,candles.getLast().timestamp());
    }

    record MinuteMove(LocalDateTime at, double change, LocalDateTime from, String kind) {}
    static List<MinuteMove> minuteMoves(List<com.finsight.external.kis.KisMinuteCandle> input, java.util.Map<LocalDate,Double> dailyCloses) {
        var candles=input.stream().sorted(Comparator.comparing(com.finsight.external.kis.KisMinuteCandle::timestamp)).toList();
        var result=new ArrayList<MinuteMove>();
        for (int i=0;i<candles.size();i++) {
            var current=candles.get(i); var date=current.timestamp().toLocalDate();
            LocalDate priorDay=com.finsight.judgement.KoreanTradingCalendar.previous(date);
            Double priorClose=dailyCloses.get(priorDay);
            if (priorClose==null && i>0) {
                var prior=candles.get(i-1);
                if (prior.timestamp().toLocalDate().equals(priorDay) && prior.timestamp().toLocalTime().equals(java.time.LocalTime.of(15,30))) priorClose=prior.close();
            }
            boolean opening=i==0 || !candles.get(i-1).timestamp().toLocalDate().equals(date);
            if (opening && priorClose!=null && priorClose>0) {
                double gap=(current.open()/priorClose-1)*100;
                if (Math.abs(gap)>=.5) result.add(new MinuteMove(current.timestamp(),gap,priorDay.atTime(15,30),"개장 갭"));
            }
            if (priorClose!=null && priorClose>0) {
                double cumulative=(current.close()/priorClose-1)*100;
                if (Math.abs(cumulative)>=2) addMinuteMove(result,new MinuteMove(current.timestamp(),cumulative,priorDay.atTime(15,30),"전일 종가 대비 누적"));
            }
            if (opening) continue;
            var previous=candles.get(i-1);
            if (previous.close()<=0) continue;
            double adjacent=(current.close()/previous.close()-1)*100;
            if (Math.abs(adjacent)>=.5) addMinuteMove(result,new MinuteMove(current.timestamp(),adjacent,previous.timestamp(),"분봉 변동"));
            var boundary=current.timestamp().minusMinutes(30);
            int anchor=i-1;
            while (anchor>0 && candles.get(anchor-1).timestamp().toLocalDate().equals(date) && !candles.get(anchor-1).timestamp().isBefore(boundary)) anchor--;
            var first=candles.get(anchor);
            if (first.close()>0 && !first.timestamp().isBefore(boundary)) {
                double rolling=(current.close()/first.close()-1)*100;
                if (Math.abs(rolling)>=1) addMinuteMove(result,new MinuteMove(current.timestamp(),rolling,first.timestamp(),"30분 누적"));
            }
        }
        var unique=new java.util.LinkedHashMap<LocalDateTime,MinuteMove>();
        for (var move:result) unique.merge(move.at(),move,(a,b) -> a.kind().equals("개장 갭") ? a : b.kind().equals("개장 갭") || Math.abs(b.change())>Math.abs(a.change()) ? b : a);
        return new ArrayList<>(unique.values());
    }
    private static void addMinuteMove(List<MinuteMove> moves, MinuteMove move) {
        for (int j=moves.size()-1;j>=0;j--) {
            var previous=moves.get(j);
            if (java.time.Duration.between(previous.at(),move.at()).toMinutes()>30) break;
            if (previous.kind().equals(move.kind()) && Math.signum(previous.change())==Math.signum(move.change())) {
                if (Math.abs(move.change())>Math.abs(previous.change())) moves.set(j,move);
                return;
            }
        }
        moves.add(move);
    }

    static ChartResponse.DocentView largestDocent(List<ChartResponse.MoveInsightView> moves) {
        return moves.stream().max(Comparator.comparingDouble(move -> Math.abs(move.changePercent()))).map(ChartResponse.MoveInsightView::docent).orElse(null);
    }
    private com.finsight.external.AiDocentRequest docentRequest(String symbol, LocalDateTime at, double change, String period, List<News> candidates, String level) {
        double displayedChange=Math.round(change*100.0)/100.0;
        String index="KOSDAQ".equals(com.finsight.stock.StockCatalog.market(symbol))?"코스닥":"코스피";
        Double indexChange=indexChange(symbol,at,period);
        var news=candidates.stream().limit(3).map(item -> {
            String body=articleContentExtractor.clean(item.getTitle(),item.getRawContent());
            String lead=ChartTextEvidence.lead(symbol,item.getTitle(),body,at.toLocalDate(),change,relationType(symbol,item));
            String summary=docentSummary(lead+"\n\n"+body.replace(lead,""));
            boolean afterClose=!"MINUTE".equals(period) && !item.getPublishedAt().isBefore(at.atZone(KST).toInstant());
            String cause=ChartTextEvidence.cause(symbol,at.toString(),relationType(symbol,item),item.getTitle(),body);
            return new com.finsight.external.AiDocentRequest.Article(item.getId(),item.getTitle(),summary,item.getPublishedAt(),relationType(symbol,item),item.getSource(),item.getSentimentHint()==null?null:item.getSentimentHint().name(),item.getSentimentConfidence(),afterClose,cause);
        }).toList();
        var request=new com.finsight.external.AiDocentRequest(symbol,com.finsight.stock.StockCatalog.name(symbol),at.toString().length()==16?at.toString()+":00":at.toString(),displayedChange,period,news,level,index,indexChange,marketContext(change,indexChange,marketFollowingExcessPercent));
        if(news.isEmpty()) return request;
        var rendered=ChartDocentService.rule(request);
        var primary=news.getFirst();
        var dictionary=termRepository==null?List.<com.finsight.term.Term>of():termRepository.findAll();
        String text=primary.summary()+" "+rendered.whatHappened()+" "+rendered.possibleReason()+" "+rendered.marketImpact();
        var glossary=com.finsight.term.KeyTermSelector.select(primary.title(),text,"analyst").stream()
                .map(term -> new com.finsight.external.AiDocentRequest.Term(term,dictionary.stream()
                        .filter(t -> t.getTerm().replaceAll("\\s+","").equalsIgnoreCase(term.replaceAll("\\s+","")))
                        .map(com.finsight.term.Term::getShortDefinition).filter(java.util.Objects::nonNull).findFirst()
                        .or(() -> com.finsight.term.KeyTermSelector.definition(term)).orElse(null)))
                .filter(term -> term.definition()!=null).limit(3).toList();
        return new com.finsight.external.AiDocentRequest(request.symbol(),request.symbolName(),request.date(),displayedChange,period,news,level,index,indexChange,request.marketContext(),glossary);
    }
    private Double indexChange(String symbol, LocalDateTime at, String period) {
        if (!("D".equals(period) || "W".equals(period))) return null;
        String indexSymbol="KOSDAQ".equals(com.finsight.stock.StockCatalog.market(symbol))?"KOSDAQ":"KOSPI";
        LocalDate day="W".equals(period) ? at.toLocalDate().with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)) : at.toLocalDate();
        Double change=ChartIndexHistory.change(indexHistory.get(indexSymbol,period),day,period);
        return change==null && "D".equals(period) && cacheManager!=null
                ? cachedIndexChange(cacheManager.getCache("kisDailyCandles"),indexSymbol,day) : change;
    }
    public static Double cachedIndexChange(org.springframework.cache.Cache cache, String symbol, LocalDate day) {
        if (cache == null) return null;
        List<KisDailyCandleResult> results = new ArrayList<>();
        var recent = cache.get(symbol + ":30:D");
        if (recent != null && recent.get() instanceof KisDailyCandleResult data) results.add(data);
        Object nativeCache = cache.getNativeCache();
        java.util.Map<?, ?> entries = nativeCache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeine
                ? caffeine.asMap() : nativeCache instanceof java.util.Map<?, ?> map ? map : java.util.Map.of();
        entries.forEach((key, value) -> {
            if (key instanceof String text && text.startsWith("close:" + symbol + ":") && value instanceof KisDailyCandleResult data) results.add(data);
        });
        for (var data : results) {
            if (data.fallback()) continue;
            var sorted = data.candles().stream().sorted(Comparator.comparing(KisDailyCandle::date)).toList();
            for (int i = 1; i < sorted.size(); i++) {
                if (sorted.get(i).date().equals(day) && sorted.get(i - 1).date().equals(com.finsight.judgement.KoreanTradingCalendar.previous(day)) && sorted.get(i - 1).close() > 0)
                    return com.finsight.judgement.ClosingPrices.from(data,sorted.get(i-1).date(),day)
                            .map(pair -> Math.round(pair.percent()*100)/100.0).orElse(null);
            }
        }
        return null;
    }
    static String docentSummary(String body) {
        if (body.length() <= 400) return body;
        StringBuilder summary = new StringBuilder();
        for (String sentence : body.split("(?<=[.!?])\\s+|\\n+")) {
            if (summary.length() + sentence.length() + 1 > 400) break;
            if (!summary.isEmpty()) summary.append(' ');
            summary.append(sentence);
        }
        return summary.toString();
    }
    public ChartResponse withDocents(ChartResponse chart,String level) {
        if (chart.fallback() || chart.moveInsights().isEmpty()) return chart;
        var sourceMoves=chart.moveInsights();
        java.util.Map<Long,News> byId=new java.util.HashMap<>();
        var ids=sourceMoves.stream().filter(move -> move.evidence()==null).flatMap(move -> move.candidateNews().stream())
                .map(ChartResponse.NewsMarkerView::newsId).filter(java.util.Objects::nonNull).distinct().toList();
        if (!ids.isEmpty()) newsRepository.findAllById(ids).stream()
                .filter(n -> articleContentExtractor.isUsable(n.getTitle(),n.getRawContent()))
                .filter(n -> relevanceRank(chart.symbol(),n)>0).forEach(n -> byId.put(n.getId(),n));
        var order=new java.util.ArrayList<Integer>();
        for(int i=0;i<sourceMoves.size();i++) order.add(i);
        order.sort(Comparator.comparingDouble((Integer i) -> Math.abs(sourceMoves.get(i).changePercent())).reversed());
        var rendered=new ChartResponse.MoveInsightView[sourceMoves.size()];
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(3).toNanos();
        for (int i:order) {
            var move=sourceMoves.get(i);
            var request=move.evidence();
            if (request==null) {
                var articles=deduplicateRelatedNews(move.candidateNews().stream().map(marker -> byId.get(marker.newsId())).filter(java.util.Objects::nonNull).toList());
                var at="MINUTE".equals(chart.period()) ? move.timestamp() : moveWindow(move.timestamp().toLocalDate(),chart.period()).to().atZone(KST).toLocalDateTime();
                request=docentRequest(chart.symbol(),at,move.changePercent(),chart.period(),articles,level);
            } else request=request.withLevel(level);
            long remaining=deadline-System.nanoTime();
            var docent=remaining<=0 || docentService==null ? ChartDocentService.rule(request)
                    : docentService.getWithin(request,java.time.Duration.ofNanos(remaining));
            String prefix="MINUTE".equals(chart.period()) && move.explanation().contains(" · ") ? move.explanation().substring(0,move.explanation().indexOf(" · ")+3) : "";
            String explanation=move.newsId()==null && move.explanation().startsWith("뉴스 수집(")?move.explanation():prefix+docent.possibleReason();
            rendered[i]=new ChartResponse.MoveInsightView(move.timestamp(),move.changePercent(),docent.newsId(),docent.newsTitle(),docent.source(),explanation,move.causeScore(),move.candidateNews(),docent,
                    move.indexChangePercent(),move.excessChangePercent(),move.marketContext(),request);
        }
        var moves=List.of(rendered);
        return new ChartResponse(chart.symbol(),chart.price(),chart.changePercent(),chart.candles(),chart.newsMarkers(),chart.relatedNews(),largestDocent(moves),chart.period(),chart.intervalMinutes(),chart.minuteCandles(),chart.fallback(),moves);
    }

    static List<News> deduplicateRelatedNews(List<News> news) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<News> result = new ArrayList<>();
        for (News item : news) {
            String title = normalize(item.getTitle());
            String content = normalize(item.getRawContent());
            String url = normalize(item.getUrl());
            List<String> keys = new ArrayList<>();
            if (!url.isBlank()) keys.add("url:" + url);
            if (!content.isBlank()) keys.add("content:" + content);
            if (!title.isBlank() && item.getPublishedAt() != null) {
                keys.add("meta:" + title + "|" + normalize(item.getSource()) + "|" + item.getPublishedAt());
            }
            if (keys.stream().noneMatch(seen::contains)) {
                seen.addAll(keys);
                result.add(item);
            }
        }
        return com.finsight.news.collect.NewsDeduplicator.distinctArticles(result);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").toLowerCase();
    }

    static News latestNewsBefore(List<News> news, Instant moveAt) {
        return news.stream()
                .filter(item -> item.getPublishedAt() != null && item.getPublishedAt().isBefore(moveAt))
                .max(Comparator.comparing(News::getPublishedAt))
                .orElse(null);
    }

    static boolean isSignificantMove(List<Double> previousChanges, double changePercent) {
        if (previousChanges.size() < MIN_VOLATILITY_OBSERVATIONS) return false;
        double mean = previousChanges.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double variance = previousChanges.stream()
                .mapToDouble(value -> Math.pow(value - mean, 2))
                .average().orElse(0.0);
        double standardDeviation = Math.sqrt(variance);
        return standardDeviation > 0.0 && Math.abs(changePercent) >= VOLATILITY_MULTIPLIER * standardDeviation;
    }

    static SentimentHint effectiveSentiment(News news) {
        var title=ChartDocentService.titleSentiment(news.getTitle());
        return news.getSentimentHint()==null || news.getSentimentHint()==SentimentHint.NEUTRAL ? (title==null?SentimentHint.NEUTRAL:title) : news.getSentimentHint();
    }
    private record ScoreKey(String symbol, String title, String body, String relatedSymbol) {}
    private record ScoreFeatures(int base, SentimentHint direction) {}
    private static final com.github.benmanes.caffeine.cache.Cache<ScoreKey,ScoreFeatures> SCORE_FEATURES =
            com.github.benmanes.caffeine.cache.Caffeine.newBuilder()
                    .maximumWeight(8 * 1024 * 1024)
                    .weigher((ScoreKey key, ScoreFeatures value) -> 128 + 2 * (key.title().length() + key.body().length()))
                    .expireAfterAccess(java.time.Duration.ofMinutes(10)).build();
    private static final java.util.regex.Pattern CORPORATE_EVENT = java.util.regex.Pattern.compile("(?s).*(공시|실적|매출|수주|계약|영업이익).*");
    private static final java.util.regex.Pattern INDEX_EVENT = java.util.regex.Pattern.compile("(?s).*(코스피|코스닥).*(%|포인트).*");
    private static final java.util.regex.Pattern LOW_SIGNAL_EVENT = java.util.regex.Pattern.compile("채용|면접|구직|관심기업|선호기업|생중계|월드 챔피언십|전시회|박람회|설명회|시상|기부|MOU|인사|산업대전|반도체 대전", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern EXHIBITION = java.util.regex.Pattern.compile("(?is).*(전시회|박람회|SEDEX|반도체\\s*대전).*");
    private static final java.util.regex.Pattern CORPORATE_TITLE = java.util.regex.Pattern.compile(".*(공시|실적|수주|계약|매출|영업이익).*");
    private static final java.util.regex.Pattern PUBLIC_AGENCY = java.util.regex.Pattern.compile("^(?:[가-힣]{1,8}(?:시|도|군)(?=[ ,·])|(?:교육|고용노동|기획재정|재정경제|농식품|농림축산식품|산업통상자원|국토교통|환경|보건복지|과학기술정보통신)부|금융위|한은).*");
    private static final java.util.regex.Pattern MACRO_EVENT = java.util.regex.Pattern.compile("금리|FOMC|환율|원[ /·]?달러|달러 강세|미국 증시|나스닥|필라델피아 반도체|외국인.{0,10}순(?:매도|매수)|기관.{0,10}순(?:매도|매수)|프로그램 매매|관세",java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern MARKET_CONTEXT = java.util.regex.Pattern.compile("증시|코스피|코스닥|주식시장|주식 시장|주가지수");
    static int representativeScore(String symbol, News news, double change) {
        String title=java.util.Objects.toString(news.getTitle(),"");
        var key = new ScoreKey(symbol,title,java.util.Objects.toString(news.getRawContent(),""),news.getRelatedSymbol());
        var features = SCORE_FEATURES.get(key, ignored -> scoreFeatures(symbol,news));
        var direction = news.getSentimentConfidence()==null ? features.direction()
                : com.finsight.news.SentimentEvidence.direction(news.getSentimentHint()==null?null:news.getSentimentHint().name(),news.getSentimentConfidence(),title);
        return features.base() + (direction == null || change == 0 ? 0 : (change>0)==(direction==SentimentHint.POSITIVE) ? 3 : -2);
    }
    private static ScoreFeatures scoreFeatures(String symbol, News news) {
        String title=java.util.Objects.toString(news.getTitle(),"");
        String lead=new ArticleContentExtractor().clean(title,news.getRawContent());
        lead=lead.substring(0,Math.min(400,lead.length()));
        var direction = com.finsight.news.SentimentEvidence.titleDirection(title);
        int score = 0;
        if (com.finsight.stock.StockCatalog.mentions(symbol,title)) score+=2;
        if (symbol.equals(news.getRelatedSymbol())) score+=3;
        if (com.finsight.stock.StockCatalog.mentions(symbol,lead)) score+=2;
        if (relevanceRank(symbol,news)>1 && CORPORATE_EVENT.matcher(title+" "+lead).matches()) score+=2;
        if (INDEX_EVENT.matcher(title+" "+lead).matches()) score++;
        if (LOW_SIGNAL_EVENT.matcher(title).find()) score-=3;
        if (EXHIBITION.matcher(title+" "+lead).matches() && !CORPORATE_TITLE.matcher(title).matches()) score-=5;
        if (PUBLIC_AGENCY.matcher(title).matches() && !(MARKET_CONTEXT.matcher(title+" "+lead).find() && MACRO_EVENT.matcher(title+" "+lead).find())) score-=2;
        return new ScoreFeatures(score,direction);
    }


    // % change vs the previous trading day's close — this was previously
    // missing from the response entirely, so the frontend always defaulted
    // it to a hardcoded 0.00%.
    private double computeChangePercent(List<CandleView> candleViews) {
        if (candleViews.size() < 2) {
            return 0.0;
        }
        double latestClose = candleViews.get(candleViews.size() - 1).close();
        double previousClose = candleViews.get(candleViews.size() - 2).close();
        if (previousClose == 0.0) {
            return 0.0;
        }
        return Math.round((latestClose - previousClose) / previousClose * 1000.0) / 10.0;
    }

    private double computeMinuteChangePercent(List<ChartResponse.MinuteCandleView> candles) {
        if (candles.size() < 2) {
            return 0.0;
        }
        double previous = candles.get(candles.size() - 2).close();
        if (previous == 0.0) {
            return 0.0;
        }
        double latest = candles.get(candles.size() - 1).close();
        return Math.round((latest - previous) / previous * 1000.0) / 10.0;
    }

    private NewsMarkerView toMarker(String symbol, News news) {
        LocalDate date = news.getPublishedAt() == null
                ? null
                : news.getPublishedAt().atZone(KST).toLocalDate();
        return new NewsMarkerView(date, news.getPublishedAt(), news.getId(), news.getTitle(), news.getSource(), relationType(symbol, news));
    }

    private static String relationType(String symbol, News news) {
        return switch (relevanceRank(symbol, news)) { case 3 -> "DIRECT"; case 2 -> "SECTOR"; default -> "MARKET"; };
    }

    private List<News> findRelatedNews(String symbol, Instant start, Instant end) {
        var ids=new java.util.ArrayList<Long>();
        long cursor=0;
        while(true) {
            var page=newsRepository.findChartCandidatesAfterId(start,end,cursor,org.springframework.data.domain.PageRequest.of(0,100));
            // Dense windows would hydrate almost every body anyway; avoid metadata pages after the first.
            if (cursor == 0 && page.size() == 100 && page.stream().allMatch(article -> relevanceRank(symbol,article)>0)) {
                return newsRepository.findByPublishedAtBetween(start,end).stream().filter(article -> relevanceRank(symbol,article)>0).toList();
            }
            page.stream().filter(article -> relevanceRank(symbol,article)>0).map(News::getId).forEach(ids::add);
            if(page.size()<100) break;
            cursor=page.getLast().getId();
        }
        return ids.isEmpty()?List.of():newsRepository.findAllById(ids);
    }

    static int relevanceRank(String symbol, News news) {
        String title = news.getTitle() == null ? "" : news.getTitle();
        if (symbol.equals(news.getRelatedSymbol()) || title.contains(symbol)) return 3;
        String raw = com.finsight.localml.ChartCompanyMatcher.cleanBody(news.getRawContent());
        String lead = raw.substring(0, Math.min(300, raw.length()));
        String name = com.finsight.stock.StockCatalog.name(symbol);
        if (lead.contains(symbol)
                || !name.equals(symbol) && com.finsight.localml.ChartCompanyMatcher.reason(symbol,title,lead) != null) return 3;
        String subject = title + " " + lead;
        if (com.finsight.stock.StockCatalog.sectorTerms(symbol).stream().anyMatch(term -> subject.toLowerCase(java.util.Locale.ROOT).contains(term.toLowerCase(java.util.Locale.ROOT)))) return 2;
        String market = com.finsight.stock.StockCatalog.market(symbol);
        String index = "KOSDAQ".equals(market) ? "코스닥" : "KOSPI".equals(market) ? "코스피" : null;
        String other = "코스피".equals(index) ? "코스닥" : "코스피";
        if (index == null || title.contains(other) && title.matches(".*(IPO|ETF|상장|상장심사|분배금).*")) return 0;
        return title.contains(index) || MARKET_CONTEXT.matcher(subject).find() && MACRO_EVENT.matcher(subject).find() ? 1 : 0;
    }

    static List<News> candidateNews(String symbol, List<News> news, Instant from, Instant to) {
        return candidateNews(symbol,news,from,to,0);
    }
    static List<News> candidateNews(String symbol, List<News> news, Instant from, Instant to, double change) {
        return candidateNews(symbol,news,from,to,change,"UNKNOWN");
    }
    static double tradingTimePenalty(Instant from, Instant to) {
        if (!from.isBefore(to)) return 0;
        double minutes=0;
        for (LocalDate day=from.atZone(KST).toLocalDate(); !day.isAfter(to.atZone(KST).toLocalDate()) && minutes<1950; day=day.plusDays(1)) {
            if (!com.finsight.judgement.KoreanTradingCalendar.isTradingDay(day)) continue;
            Instant open=day.atTime(9,0).atZone(KST).toInstant(), close=day.atTime(15,30).atZone(KST).toInstant();
            Instant start=from.isAfter(open)?from:open, end=to.isBefore(close)?to:close;
            if (start.isBefore(end)) minutes+=java.time.Duration.between(start,end).toSeconds()/60.0;
        }
        return Math.min(1.5,minutes/390*.3);
    }
    static List<News> candidateNews(String symbol, List<News> news, Instant from, Instant to, double change, String context) {
        record Scored(News news, int rank, int priority, int content, int score, double time, double adjusted) {}
        return news.stream().filter(item -> item.getPublishedAt()!=null
                && !item.getPublishedAt().isBefore(from) && item.getPublishedAt().isBefore(to))
            .map(item -> {
                String title=java.util.Objects.toString(item.getTitle(),"");
                int rank=relevanceRank(symbol,item), score=representativeScore(symbol,item,change);
                var hint=com.finsight.news.SentimentEvidence.direction(item.getSentimentHint()==null?null:item.getSentimentHint().name(),item.getSentimentConfidence(),title);
                if (hint!=null && change!=0 && (change>0)!=(hint==SentimentHint.POSITIVE)) return null;
                if (rank>1 && LOW_SIGNAL_EVENT.matcher(title).find() && !CORPORATE_TITLE.matcher(title).matches()) return null;
                int priority="FOLLOWING".equals(context)?(rank==1?3:rank==2?2:1):"INDIVIDUAL".equals(context)?rank:0;
                double time=tradingTimePenalty(item.getPublishedAt(),to);
                return new Scored(item,rank,priority,score-(hint==null||change==0?0:3),score,time,score-time);
            })
            .filter(item -> item!=null && item.rank()>0 && item.score()>=(item.rank()==3?4:0))
            .sorted(Comparator.comparingInt(Scored::priority).reversed()
                .thenComparing(Comparator.comparingDouble(Scored::adjusted).reversed())
                .thenComparing(item -> item.news().getPublishedAt(),Comparator.reverseOrder())
                .thenComparing(item -> item.news().getId(),Comparator.nullsLast(Comparator.naturalOrder())))
            .limit(3).peek(item -> log.debug("Chart cause candidate newsId={} rank={} contextPriority={} content={} directionBonus={} timePenalty={}",
                item.news().getId(),item.rank(),item.priority(),item.content(),item.score()-item.content(),item.time()))
            .map(Scored::news).toList();
    }
}
