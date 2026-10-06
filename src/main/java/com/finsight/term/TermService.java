package com.finsight.term;

import com.finsight.external.AiServiceClient;
import com.finsight.external.AiTermExplainRequest;
import com.finsight.external.AiTermExplainResponse;
import com.finsight.news.News;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.news.NewsRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TermService {

    private static final Logger log = LoggerFactory.getLogger(TermService.class);

    private final ArticleContentExtractor extractor;
    private final TermRepository termRepository;
    private final NewsRepository newsRepository;
    private final AiServiceClient aiServiceClient;

    public TermService(TermRepository termRepository, NewsRepository newsRepository, AiServiceClient aiServiceClient, ArticleContentExtractor extractor) {
        this.extractor = extractor;
        this.termRepository = termRepository;
        this.newsRepository = newsRepository;
        this.aiServiceClient = aiServiceClient;
    }

    private static final String NO_DEFINITION_FALLBACK = "등록된 기본 설명이 없는 용어입니다.";

    public TermExplainResponse explain(TermExplainRequest request) {
        Optional<String> localDefinition = termRepository.findByTermIgnoreCase(request.term())
                .map(Term::getShortDefinition);

        if (request.newsId() == null) {
            return new TermExplainResponse(request.term(), localDefinition.orElse(NO_DEFINITION_FALLBACK), null, null);
        }

        Optional<News> news = newsRepository.findById(request.newsId());
        if (news.isEmpty()) {
            return new TermExplainResponse(request.term(), localDefinition.orElse(NO_DEFINITION_FALLBACK), null, null);
        }

        String context = buildContext(news.get(), request.term());
        Optional<AiTermExplainResponse> aiResponse = aiServiceClient.explainTerm(
                new AiTermExplainRequest(request.term(), context));

        if (aiResponse.isEmpty()) {
            log.warn("AI term explain unavailable for term={}, newsId={}, returning base definition only",
                    request.term(), request.newsId());
            return new TermExplainResponse(request.term(), localDefinition.orElse(NO_DEFINITION_FALLBACK), null, null);
        }

        // Only ~20 terms are seeded locally, but keyTerms now come from real
        // AI-detected terms per article, which go far beyond that fixed list.
        // Prefer the curated local definition when we have one (trusted,
        // reviewed); otherwise use the AI's own definition instead of a
        // dead-end "no definition" message.
        String plainDefinition = aiResponse.get().plainDefinition();
        String definition = localDefinition.orElseGet(() ->
                (plainDefinition != null && !plainDefinition.isBlank()) ? plainDefinition : NO_DEFINITION_FALLBACK);

        return new TermExplainResponse(
                request.term(),
                definition,
                aiResponse.get().contextExplanation(),
                aiResponse.get().marketImpact()
        );
    }

    private String buildContext(News news, String term) {
        String body = extractor.clean(news.getTitle(), news.getRawContent());
        int at = body.indexOf(term);
        int start = at > 1800 ? Math.max(0, at - 200) : 0;
        return news.getTitle() + "\n" + body.substring(start, Math.min(body.length(), start + 2000));
    }
}
