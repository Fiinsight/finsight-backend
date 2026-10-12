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
            return localExplanation(request, localDefinition);
        }

        Optional<News> news = newsRepository.findById(request.newsId());
        if (news.isEmpty()) {
            return localExplanation(request, localDefinition);
        }

        String context = buildContext(news.get(), request.term());
        Optional<AiTermExplainResponse> aiResponse = aiServiceClient.explainTerm(
                new AiTermExplainRequest(request.term(), context, localDefinition.orElse(null), request.level() == null ? "normal" : request.level()));

        if (aiResponse.isEmpty()) {
            log.warn("AI term explain unavailable for term={}, newsId={}, returning base definition only",
                    request.term(), request.newsId());
            return localExplanation(request, localDefinition);
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

    private String analogy(String term) {
        if (term.contains("금리")) return "금리는 물건을 빌릴 때 내는 대여료처럼 돈을 빌리는 비용이에요.";
        if (term.contains("환율")) return "환율은 서로 다른 나라 돈을 바꾸는 가격표와 같아요.";
        if (term.contains("물가")) return "물가는 장바구니 물건들의 평균 가격표와 같아요.";
        if (term.contains("매출")) return "매출은 가게가 판매하고 받은 돈을 모두 더한 것과 같아요.";
        if (term.contains("코스피") || term.contains("코스닥")) return "지수는 여러 종목의 움직임을 보여주는 시장 온도계와 같아요.";
        return "용어 설명은 낯선 길의 지도처럼 기사 내용을 이해하는 데 도움을 줘요.";
    }
    private TermExplainResponse localExplanation(TermExplainRequest request, Optional<String> definition) {
        String context = "beginner".equals(request.level())
                ? analogy(request.term()) : null;
        String impact = "analyst".equals(request.level())
                ? "관련 기사 수치가 없어 시점·단위·비교 기준에 따른 정량적 시장 영향을 확인할 수 없습니다." : null;
        return new TermExplainResponse(request.term(), definition.orElse(NO_DEFINITION_FALLBACK), context, impact);
    }
    private String buildContext(News news, String term) {
        String body = extractor.clean(news.getTitle(), news.getRawContent());
        int at = body.indexOf(term);
        int start = at > 1800 ? Math.max(0, at - 200) : 0;
        return news.getTitle() + "\n" + body.substring(start, Math.min(body.length(), start + 2000));
    }
}
