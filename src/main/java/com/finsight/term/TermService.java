package com.finsight.term;

import com.finsight.external.AiServiceClient;
import com.finsight.external.AiTermExplainRequest;
import com.finsight.external.AiTermExplainResponse;
import com.finsight.news.News;
import com.finsight.news.NewsRepository;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class TermService {

    private static final Logger log = LoggerFactory.getLogger(TermService.class);

    private final TermRepository termRepository;
    private final NewsRepository newsRepository;
    private final AiServiceClient aiServiceClient;

    public TermService(TermRepository termRepository, NewsRepository newsRepository, AiServiceClient aiServiceClient) {
        this.termRepository = termRepository;
        this.newsRepository = newsRepository;
        this.aiServiceClient = aiServiceClient;
    }

    public TermExplainResponse explain(TermExplainRequest request) {
        String definition = termRepository.findByTermIgnoreCase(request.term())
                .map(Term::getShortDefinition)
                .orElse("등록된 기본 설명이 없는 용어입니다.");

        if (request.newsId() == null) {
            return new TermExplainResponse(request.term(), definition, null, null);
        }

        Optional<News> news = newsRepository.findById(request.newsId());
        if (news.isEmpty()) {
            return new TermExplainResponse(request.term(), definition, null, null);
        }

        String context = buildContext(news.get());
        Optional<AiTermExplainResponse> aiResponse = aiServiceClient.explainTerm(
                new AiTermExplainRequest(request.term(), context));

        if (aiResponse.isEmpty()) {
            log.warn("AI term explain unavailable for term={}, newsId={}, returning base definition only",
                    request.term(), request.newsId());
            return new TermExplainResponse(request.term(), definition, null, null);
        }

        return new TermExplainResponse(
                request.term(),
                definition,
                aiResponse.get().contextExplanation(),
                aiResponse.get().marketImpact()
        );
    }

    private String buildContext(News news) {
        String body = news.getRawContent();
        if (body != null && body.length() > 2000) {
            body = body.substring(0, 2000);
        }
        return news.getTitle() + (body == null ? "" : "\n" + body);
    }
}
