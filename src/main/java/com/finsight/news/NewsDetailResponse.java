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
        com.finsight.judgement.JudgementTargetResolver.Target judgementTarget
) {
    public static NewsDetailResponse from(News news, NewsCategoryClassifier categoryClassifier,
                                          ArticleContentExtractor contentExtractor) {
        String rawContent = contentExtractor.clean(news.getTitle(), news.getRawContent());
        boolean unavailable = rawContent.isBlank();
        String beginner = unavailable ? "" : replaceStaleRewrite(news.getRewrittenBeginner(), rawContent);
        String normal = unavailable ? "" : replaceStaleRewrite(news.getRewrittenNormal(), rawContent);
        String analyst = unavailable ? "" : replaceStaleRewrite(news.getRewrittenAnalyst(), rawContent);
        return new NewsDetailResponse(
                news.getId(),
                news.getTitle(),
                news.getUrl(),
                news.getSource(),
                news.getPublishedAt(),
                excerpt(rawContent),
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
                unavailable ? List.of() : news.getKeyTerms(),
                com.finsight.judgement.JudgementTargetResolver.resolve(news)
        );
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
