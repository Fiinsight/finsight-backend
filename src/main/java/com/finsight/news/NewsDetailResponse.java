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
        List<String> keyTerms
) {
    public static NewsDetailResponse from(News news, NewsCategoryClassifier categoryClassifier,
                                          ArticleContentExtractor contentExtractor) {
        String rawContent = contentExtractor.clean(news.getTitle(), news.getRawContent());
        String beginner = replaceStaleRewrite(news.getRewrittenBeginner(), rawContent);
        String normal = replaceStaleRewrite(news.getRewrittenNormal(), rawContent);
        String analyst = replaceStaleRewrite(news.getRewrittenAnalyst(), rawContent);
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
                categoryClassifier.importanceReason(news.getTitle(), rawContent, news.getImportanceReason(),
                        news.getRelatedSymbol()),
                news.getImportanceReasonBeginner(),
                news.getImportanceReasonNormal(),
                news.getImportanceReasonAnalyst(),
                news.getRelatedSymbol(),
                news.getSentimentHint(),
                categoryClassifier.classify(news.getTitle()),
                news.getKeyTerms()
        );
    }

    public static String excerpt(String text) {
        if (text == null) return null;
        return text.length() <= 600 ? text : text.substring(0, 600).strip() + "…\n전체 원문은 출처 링크에서 확인하세요.";
    }

    private static String replaceStaleRewrite(String rewrite, String cleanedContent) {
        if (rewrite == null) {
            return null;
        }
        return rewrite.contains("Google 검색") || rewrite.contains("더 자주 볼 수 있습니다")
                ? cleanedContent : rewrite;
    }
}
