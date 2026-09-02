package com.finsight.external;

import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Client for the sibling FastAPI "finsight-ai" service (news rewriting, term
 * explanation, judgement feedback generation).
 *
 * The AI service always returns a valid JSON response, whether it is backed
 * by a real LLM call or a mock. Even so, network failures / timeouts / the
 * service simply not being started yet are all handled defensively here so
 * that a missing AI service never crashes or blocks the backend.
 */
@Component
public class AiServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AiServiceClient.class);

    private static final String REWRITE_PATH = "/ai/news/rewrite";
    private static final String TERM_EXPLAIN_PATH = "/ai/terms/explain";
    private static final String FEEDBACK_PATH = "/ai/feedback/judgement";

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;

    public AiServiceClient(WebClient.Builder webClientBuilder, @Value("${finsight.ai.base-url}") String baseUrl) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
    }

    public Optional<AiRewriteResponse> rewrite(AiRewriteRequest request) {
        try {
            AiRewriteResponse response = webClient.post()
                    .uri(REWRITE_PATH)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(AiRewriteResponse.class)
                    .timeout(CALL_TIMEOUT)
                    .block();
            return Optional.ofNullable(response);
        } catch (Exception e) {
            log.warn("AI service rewrite call failed, skipping: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<AiTermExplainResponse> explainTerm(AiTermExplainRequest request) {
        try {
            AiTermExplainResponse response = webClient.post()
                    .uri(TERM_EXPLAIN_PATH)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(AiTermExplainResponse.class)
                    .timeout(CALL_TIMEOUT)
                    .block();
            return Optional.ofNullable(response);
        } catch (Exception e) {
            log.warn("AI service term explain call failed, skipping: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public Optional<AiFeedbackResponse> generateFeedback(AiFeedbackRequest request) {
        try {
            AiFeedbackResponse response = webClient.post()
                    .uri(FEEDBACK_PATH)
                    .bodyValue(request)
                    .retrieve()
                    .bodyToMono(AiFeedbackResponse.class)
                    .timeout(CALL_TIMEOUT)
                    .block();
            return Optional.ofNullable(response);
        } catch (Exception e) {
            log.warn("AI service feedback call failed, skipping: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
