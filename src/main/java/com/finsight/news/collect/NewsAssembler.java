package com.finsight.news.collect;

import com.finsight.briefing.SentimentHint;
import com.finsight.external.AiRewriteRequest;
import com.finsight.external.AiRewriteResponse;
import com.finsight.external.AiServiceClient;
import com.finsight.news.News;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Builds a persistable {@link News} entity from a collected candidate and
 * its extracted article body: categorizes it and asks the AI service to
 * rewrite it for the 3 reading levels, falling back to the raw body
 * verbatim (for all 3 levels) if the AI service call fails.
 */
@Component
public class NewsAssembler {

    private static final Logger log = LoggerFactory.getLogger(NewsAssembler.class);

    private static final String FALLBACK_IMPORTANCE_REASON = "경제 지표 및 시장 동향과 관련된 뉴스입니다.";

    private final AiServiceClient aiServiceClient;
    private final NewsCategoryClassifier newsCategoryClassifier;
    private final NewsSymbolMatcher newsSymbolMatcher;

    public NewsAssembler(AiServiceClient aiServiceClient, NewsCategoryClassifier newsCategoryClassifier,
                          NewsSymbolMatcher newsSymbolMatcher) {
        this.aiServiceClient = aiServiceClient;
        this.newsCategoryClassifier = newsCategoryClassifier;
        this.newsSymbolMatcher = newsSymbolMatcher;
    }

    public News assemble(NewsCandidate candidate, String rawContent) {
        News news = new News(candidate.title(), candidate.url(), candidate.source(), candidate.publishedAt(), rawContent);
        news.setCategory(newsCategoryClassifier.classify(candidate.title()));
        news.setRelatedSymbol(newsSymbolMatcher.match(candidate.title()));
        news.setSentimentHint(SentimentHint.NEUTRAL);
        applyRewrite(news, candidate, rawContent);
        return news;
    }

    // finsight-ai rewrites one reading level per call, so we call it 3 times
    // (once per level) and assemble the results rather than expecting one
    // combined response.
    private void applyRewrite(News news, NewsCandidate candidate, String rawContent) {
        RewriteLevelResult beginner = rewriteLevel(candidate, rawContent, "beginner");
        RewriteLevelResult normal = rewriteLevel(candidate, rawContent, "normal");
        RewriteLevelResult analyst = rewriteLevel(candidate, rawContent, "analyst");

        news.setRewrittenBeginner(beginner.summary());
        news.setRewrittenNormal(normal.summary());
        news.setRewrittenAnalyst(analyst.summary());
        news.setImportanceReason(firstNonBlank(normal.importanceReason(), beginner.importanceReason(), analyst.importanceReason())
                .orElse(FALLBACK_IMPORTANCE_REASON));
    }

    private RewriteLevelResult rewriteLevel(NewsCandidate candidate, String rawContent, String level) {
        Optional<AiRewriteResponse> response =
                aiServiceClient.rewrite(new AiRewriteRequest(candidate.title(), rawContent, level));
        if (response.isPresent()) {
            return new RewriteLevelResult(response.get().summary(), response.get().importanceReason());
        }
        log.warn("AI rewrite unavailable for level={} url={}, falling back to raw article text for this level",
                level, candidate.url());
        return new RewriteLevelResult(rawContent, null);
    }

    private Optional<String> firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    private record RewriteLevelResult(String summary, String importanceReason) {
    }
}
