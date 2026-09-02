package com.finsight.news.collect;

import java.time.Duration;
import java.util.Optional;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Best-effort article body extraction with Jsoup: prefer the largest
 * {@code <article>} block, otherwise concatenate all {@code <p>} tags.
 * This doesn't need to be perfect, just good enough for the AI rewrite step.
 */
@Component
public class ArticleContentExtractor {

    private static final Logger log = LoggerFactory.getLogger(ArticleContentExtractor.class);

    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; FinsightBot/1.0)";

    public Optional<String> extract(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout((int) FETCH_TIMEOUT.toMillis())
                    .get();

            String text = extractFromArticleTag(doc);
            if (!StringUtils.hasText(text)) {
                text = extractFromParagraphs(doc);
            }
            return StringUtils.hasText(text) ? Optional.of(text) : Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to extract article body from {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    private String extractFromArticleTag(Document doc) {
        Elements articleTags = doc.select("article");
        return articleTags.isEmpty() ? null : articleTags.text();
    }

    private String extractFromParagraphs(Document doc) {
        Elements paragraphs = doc.select("p");
        StringBuilder sb = new StringBuilder();
        for (Element p : paragraphs) {
            sb.append(p.text()).append("\n");
        }
        return sb.toString().trim();
    }
}
