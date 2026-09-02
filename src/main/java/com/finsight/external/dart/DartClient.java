package com.finsight.external.dart;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Client for 금융감독원 OpenDART's recent disclosure list. Corp-code
 * resolution (a distinct concern) is delegated to {@link DartCorpCodeResolver}.
 *
 * Best-effort implementation: any failure (missing key, network error,
 * unexpected shape, corp code not found) falls back to an empty disclosure
 * list with a warn log, never an exception bubbling up.
 */
@Component
public class DartClient {

    private static final Logger log = LoggerFactory.getLogger(DartClient.class);

    private static final String LIST_PATH = "/list.json";
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);
    private static final DateTimeFormatter YYYYMMDD = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final WebClient webClient;
    private final DartCorpCodeResolver corpCodeResolver;
    private final String apiKey;

    public DartClient(WebClient.Builder webClientBuilder,
                       DartCorpCodeResolver corpCodeResolver,
                       @Value("${finsight.dart.base-url}") String baseUrl,
                       @Value("${finsight.dart.api-key}") String apiKey) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.corpCodeResolver = corpCodeResolver;
        this.apiKey = apiKey;
    }

    public List<DartDisclosure> getRecentDisclosures(String stockCode) {
        if (!StringUtils.hasText(apiKey) || !StringUtils.hasText(stockCode)) {
            log.warn("DART API key not configured or stock code missing, returning empty disclosure list");
            return List.of();
        }
        try {
            Optional<String> corpCode = corpCodeResolver.resolve(stockCode);
            if (corpCode.isEmpty()) {
                log.warn("DART corp code not found for stock {}, returning empty disclosure list", stockCode);
                return List.of();
            }
            return fetchList(corpCode.get());
        } catch (Exception e) {
            log.warn("DART disclosure lookup failed for {}, using fallback: {}", stockCode, e.getMessage());
            return List.of();
        }
    }

    private List<DartDisclosure> fetchList(String corpCode) {
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(30);
        JsonNode response = webClient.get()
                .uri(uriBuilder -> uriBuilder.path(LIST_PATH)
                        .queryParam("crtfc_key", apiKey)
                        .queryParam("corp_code", corpCode)
                        .queryParam("bgn_de", start.format(YYYYMMDD))
                        .queryParam("end_de", end.format(YYYYMMDD))
                        .queryParam("page_count", "10")
                        .build())
                .retrieve()
                .bodyToMono(JsonNode.class)
                .timeout(CALL_TIMEOUT)
                .block();
        if (response == null || !response.path("list").isArray()) {
            return List.of();
        }
        List<DartDisclosure> disclosures = new ArrayList<>();
        for (JsonNode item : response.path("list")) {
            disclosures.add(new DartDisclosure(
                    item.path("report_nm").asText(""),
                    item.path("flr_nm").asText(""),
                    item.path("rcept_dt").asText("")
            ));
        }
        return disclosures;
    }
}
