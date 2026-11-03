package com.finsight.news;

import com.finsight.briefing.SentimentHint;
import com.finsight.news.collect.NewsCategoryClassifier;
import com.finsight.news.collect.ArticleContentExtractor;
import java.time.Instant;
import java.util.List;

public record NewsDetailResponse(
        Long id,
        String title,
        String url,
        String source,
        Instant publishedAt,
        String rawContent,
        String rewrittenBeginner,
        String rewrittenNormal,
        String rewrittenAnalyst,
        String importanceReason,
        String importanceReasonBeginner,
        String importanceReasonNormal,
        String importanceReasonAnalyst,
        String relatedSymbol,
        SentimentHint sentimentHint,
        String category,
        List<String> keyTerms,
        java.util.Map<String,List<String>> keyTermsByLevel,
        com.finsight.judgement.JudgementTargetResolver.Target judgementTarget,
        java.time.LocalDate judgementBaselineDate, java.time.LocalDate judgementComparisonDate, boolean judgementReviewOnly, String imageUrl, boolean fullContentEnabled
) {
    public static NewsDetailResponse from(News news, NewsCategoryClassifier categoryClassifier,
                                          ArticleContentExtractor contentExtractor) {
        return from(news,categoryClassifier,contentExtractor,false);
    }
    public static NewsDetailResponse from(News news, NewsCategoryClassifier categoryClassifier,
                                          ArticleContentExtractor contentExtractor, boolean fullContentEnabled) {
        String rawContent = contentExtractor.clean(news.getTitle(), news.getRawContent());
        boolean unavailable = rawContent.isBlank();
        String beginner = unavailable ? "" : replaceStaleRewrite(news.getRewrittenBeginner(), rawContent);
        String normal = unavailable ? "" : replaceStaleRewrite(news.getRewrittenNormal(), rawContent);
        String analyst = unavailable ? "" : replaceStaleRewrite(news.getRewrittenAnalyst(), rawContent);
        var termLists = com.finsight.term.KeyTermSelector.byLevel(unavailable ? "" : news.getTitle(), rawContent);
        return new NewsDetailResponse(
                news.getId(),
                news.getTitle(),
                news.getUrl(),
                news.getSource(),
                news.getPublishedAt(),
                fullContentEnabled ? rawContent : sourceExcerpt(rawContent),
                excerpt(beginner),
                excerpt(normal),
                excerpt(analyst),
                unavailable ? "" : categoryClassifier.importanceReason(news.getTitle(), rawContent, news.getImportanceReason(),
                        news.getRelatedSymbol()),
                unavailable ? "" : news.getImportanceReasonBeginner(),
                unavailable ? "" : news.getImportanceReasonNormal(),
                unavailable ? "" : news.getImportanceReasonAnalyst(),
                news.getRelatedSymbol(),
                news.getSentimentHint(),
                categoryClassifier.classify(news.getTitle(), rawContent),
                termLists.get("normal"), termLists,
                com.finsight.judgement.JudgementTargetResolver.resolve(news),
                com.finsight.judgement.KoreanTradingCalendar.onOrBefore(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))),
                com.finsight.judgement.KoreanTradingCalendar.next(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"))),
                com.finsight.judgement.ArticleJudgementPolicy.reviewOnly(news.getPublishedAt(),Instant.now()),
                com.finsight.news.collect.NewsImageUrl.sanitize(news.getImageUrl(),news.getUrl()), fullContentEnabled
        );
    }

    public static String cardText(String text) {
        return text == null ? "" : text.replaceAll("\\[(사실|전망|분석)\\]", "")
                .replaceAll("\\[\\s*(?=[A-Za-z0-9]*\\d)[A-Za-z0-9]{6}\\s*\\]|\\(\\s*(?=[A-Za-z0-9]*\\d)[A-Za-z0-9]{6}\\s*\\)", "").replaceAll("\\s+", " ").strip();
    }
    public static String cardPoint(String text) {
        String[] lines = java.util.Objects.toString(text, "").strip().split("\\n+");
        return cardText(lines.length > 1 && lines[0].startsWith("“") ? lines[lines.length - 1] : text);
    }
    public static String cardSummary(String text) {
        String first = java.util.Arrays.stream(cardText(text).split("(?<=[.!?。！？])(?![0-9])\\s+"))
                .limit(2).collect(java.util.stream.Collectors.joining(" "));
        return first.codePointCount(0,first.length()) <= 150 ? first
                : first.substring(0,first.offsetByCodePoints(0,149)).stripTrailing() + "…";
    }

    public static String sourceExcerpt(String text) {
        if (text == null || text.isBlank()) return "";
        var paragraphs = new java.util.ArrayList<String>();
        int count = 0, size = 0;
        for (String paragraph : text.strip().split("\\n+")) {
            var kept = new java.util.ArrayList<String>();
            for (String sentence : paragraph.split("(?<=[.!?。！？])(?![0-9])\\s+")) {
                if (sentence.isBlank()) continue;
                int length = sentence.codePointCount(0,sentence.length());
                if (count >= 6 || size + length + 2 > 1200) break;
                kept.add(sentence); count++; size += length + 2;
            }
            if (!kept.isEmpty()) paragraphs.add(String.join(" ", kept));
            if (count >= 6) break;
        }
        return String.join("\n\n", paragraphs);
    }

    public static String excerpt(String text) {
        if (text == null) return null;
        if (text.length() <= 600) return text;
        String prefix = text.substring(0, 600);
        var endings = java.util.regex.Pattern.compile("[.!?。！？](?![0-9])(?:\\s|$)").matcher(prefix);
        int end = 0;
        while (endings.find()) end = endings.start() + 1;
        if (end == 0) end = Math.max(0, prefix.lastIndexOf(' '));
        return prefix.substring(0, end).strip() + "…";
    }

    private static String replaceStaleRewrite(String rewrite, String cleanedContent) {
        if (rewrite == null) {
            return null;
        }
        return rewrite.contains("Google 검색") || rewrite.contains("더 자주 볼 수 있습니다")
                ? cleanedContent : ArticleContentExtractor.stripPhotoNoise(rewrite);
    }
}
