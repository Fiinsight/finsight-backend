package com.finsight.external.dart;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Resolves a stock ticker (e.g. "005930") to DART's own 8-digit corp_code,
 * which the disclosure list endpoint requires. The mapping comes from a
 * zipped XML master file (corpCode.xml); a successful lookup is cached in
 * Redis so the (multi-MB) zip isn't re-downloaded on every call.
 */
@Component
public class DartCorpCodeResolver {

    private static final Logger log = LoggerFactory.getLogger(DartCorpCodeResolver.class);

    private static final String CORP_CODE_PATH = "/corpCode.xml";
    private static final String REDIS_KEY_PREFIX = "finsight:dart:corpcode:";
    private static final Duration CACHE_TTL = Duration.ofDays(1);
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);

    private final WebClient webClient;
    private final StringRedisTemplate redisTemplate;
    private final String apiKey;

    public DartCorpCodeResolver(WebClient.Builder webClientBuilder,
                                 StringRedisTemplate redisTemplate,
                                 @Value("${finsight.dart.base-url}") String baseUrl,
                                 @Value("${finsight.dart.api-key}") String apiKey) {
        this.webClient = webClientBuilder.baseUrl(baseUrl).build();
        this.redisTemplate = redisTemplate;
        this.apiKey = apiKey;
    }

    public Optional<String> resolve(String stockCode) {
        String cacheKey = REDIS_KEY_PREFIX + stockCode;
        Optional<String> cached = readCache(cacheKey);
        if (cached.isPresent()) {
            return cached;
        }

        try {
            byte[] zipBytes = webClient.get()
                    .uri(uriBuilder -> uriBuilder.path(CORP_CODE_PATH).queryParam("crtfc_key", apiKey).build())
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(CALL_TIMEOUT)
                    .block();
            if (zipBytes == null) {
                return Optional.empty();
            }
            Optional<String> found = extractCorpCode(zipBytes, stockCode);
            found.ifPresent(code -> writeCache(cacheKey, code));
            return found;
        } catch (Exception e) {
            log.warn("DART corp code master download/parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> readCache(String cacheKey) {
        try {
            String cached = redisTemplate.opsForValue().get(cacheKey);
            return StringUtils.hasText(cached) ? Optional.of(cached) : Optional.empty();
        } catch (Exception e) {
            log.warn("Redis lookup for DART corp code cache failed, continuing without cache: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private void writeCache(String cacheKey, String corpCode) {
        try {
            redisTemplate.opsForValue().set(cacheKey, corpCode, CACHE_TTL);
        } catch (Exception e) {
            log.warn("Failed to cache DART corp code for key {}: {}", cacheKey, e.getMessage());
        }
    }

    private Optional<String> extractCorpCode(byte[] zipBytes, String stockCode) throws Exception {
        try (ZipInputStream zipIn = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zipIn.getNextEntry()) != null) {
                if (!entry.getName().toUpperCase().endsWith(".XML")) {
                    continue;
                }
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                DocumentBuilder builder = factory.newDocumentBuilder();
                var document = builder.parse(zipIn);
                NodeList listNodes = document.getElementsByTagName("list");
                for (int i = 0; i < listNodes.getLength(); i++) {
                    Element listElement = (Element) listNodes.item(i);
                    String candidateStockCode = textOf(listElement, "stock_code");
                    if (stockCode.equals(candidateStockCode)) {
                        return Optional.ofNullable(textOf(listElement, "corp_code"));
                    }
                }
            }
        }
        return Optional.empty();
    }

    private String textOf(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String text = nodes.item(0).getTextContent();
        return text == null ? null : text.trim();
    }
}
