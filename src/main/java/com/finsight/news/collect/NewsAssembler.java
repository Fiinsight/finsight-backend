package com.finsight.news.collect;

import com.finsight.external.AiRewriteRequest;
import com.finsight.external.AiRewriteResponse;
import com.finsight.external.AiServiceClient;
import com.finsight.news.News;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Collects all three reading levels in one AI request and preserves stored summaries.
 */
@Component
public class NewsAssembler {

    private static final Logger log = LoggerFactory.getLogger(NewsAssembler.class);

    private final AiServiceClient aiServiceClient;
    private final NewsCategoryClassifier newsCategoryClassifier;
    private final NewsSymbolMatcher newsSymbolMatcher;
    private final NewsSentimentClassifier newsSentimentClassifier;
    private final ArticleContentExtractor articleContentExtractor;

    public NewsAssembler(AiServiceClient aiServiceClient, NewsCategoryClassifier newsCategoryClassifier,
                          NewsSymbolMatcher newsSymbolMatcher, NewsSentimentClassifier newsSentimentClassifier,
                          ArticleContentExtractor articleContentExtractor) {
        this.aiServiceClient = aiServiceClient;
        this.newsCategoryClassifier = newsCategoryClassifier;
        this.newsSymbolMatcher = newsSymbolMatcher;
        this.newsSentimentClassifier = newsSentimentClassifier;
        this.articleContentExtractor = articleContentExtractor;
    }

    public News assemble(NewsCandidate candidate, String rawContent) {
        return assemble(candidate, rawContent, true);
    }

    public News assemble(NewsCandidate candidate, String rawContent, boolean allowLlm) {
        News news = new News(candidate.title(), candidate.url(), candidate.source(), candidate.publishedAt(), rawContent);
        news.setCategory(newsCategoryClassifier.classify(candidate.title(), rawContent));
        news.setRelatedSymbol(newsSymbolMatcher.match(candidate.title(), rawContent));
        news.setSentimentHint(newsSentimentClassifier.classify(candidate.title()));
        applyRewrite(news, candidate, rawContent, allowLlm);
        return news;
    }

    public void reprocess(News news) {
        if (hasStoredSummaries(news)) return;
        NewsCandidate candidate = new NewsCandidate(news.getTitle(), news.getUrl(), news.getSource(), news.getPublishedAt(), 0);
        Optional<String> extracted = articleContentExtractor.extract(news.getUrl());
        if (extracted.isEmpty() || extracted.get().isBlank()
                || !articleContentExtractor.isUsable(news.getTitle(), extracted.get())) {
            log.warn("Article refresh unavailable; keeping stored content for newsId={}", news.getId());
            return;
        }
        String cleanedContent = extracted.get();
        news.setRawContent(cleanedContent);
        news.setRelatedSymbol(newsSymbolMatcher.match(news.getTitle(), cleanedContent));
        applyRewrite(news, candidate, cleanedContent, true);
    }

    /** Refresh cheap, deterministic metadata without making any AI calls. */
    public void refreshMetadata(News news) {
        news.setCategory(newsCategoryClassifier.classify(news.getTitle(), news.getRawContent()));
        news.setRelatedSymbol(newsSymbolMatcher.match(news.getTitle(), news.getRawContent()));
        news.setSentimentHint(newsSentimentClassifier.classify(news.getTitle()));
    }

    private boolean hasStoredSummaries(News news) {
        return java.util.stream.Stream.of(news.getRewrittenBeginner(), news.getRewrittenNormal(), news.getRewrittenAnalyst())
                .anyMatch(value -> value != null && !value.isBlank());
    }

    public void retryRewrite(News news) {
        if (!news.isRewriteRetryPending() || news.getRawContent() == null || news.getRawContent().isBlank()) return;
        applyRewrite(news, new NewsCandidate(news.getTitle(), news.getUrl(), news.getSource(), news.getPublishedAt(), 0), news.getRawContent(), true);
    }

    private void applyRewrite(News news, NewsCandidate candidate, String rawContent, boolean allowLlm) {
        if (hasStoredSummaries(news) && !news.isRewriteRetryPending()) return;
        var response = aiServiceClient.rewrite(new AiRewriteRequest(candidate.title(), rawContent, null, candidate.url(), allowLlm));
        if (response.isEmpty()) {
            news.setRewriteMode("EXCERPT");
            // The free learning endpoint supplies the excerpt on demand when AI collection is unavailable.
            return;
        }
        var result = response.get();
        news.setRewriteRetryPending(result.retryable());
        news.setRewrittenBeginner(result.beginner());
        news.setRewrittenNormal(result.normal());
        news.setRewrittenAnalyst(result.analyst());
        news.setRewriteMode("REWRITTEN".equals(result.mode()) ? "REWRITTEN" : "EXCERPT");
        var reasons = result.importanceReasons() == null ? java.util.Map.<String,String>of() : result.importanceReasons();
        news.setImportanceReasonBeginner(reasons.get("beginner"));
        news.setImportanceReasonNormal(reasons.get("normal"));
        news.setImportanceReasonAnalyst(reasons.get("analyst"));
        news.setImportanceReason(result.importanceReason());
        news.setKeyTerms(result.detectedTerms() == null ? List.of() : result.detectedTerms());
    }
}
