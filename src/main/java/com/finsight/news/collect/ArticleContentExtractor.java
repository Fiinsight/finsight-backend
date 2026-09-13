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

    // A paragraph shorter than this is almost certainly page chrome (share
    // buttons, font-size controls, "번역" widgets) rather than actual article
    // text, so it's dropped instead of getting mixed into the body.
    private static final int MIN_PARAGRAPH_LENGTH = 20;

    private String extractFromArticleTag(Document doc) {
        Elements articleTags = doc.select("article");
        if (articleTags.isEmpty()) {
            return null;
        }
        // Elements#text() flattens all descendants into one space-joined line
        // with no paragraph breaks at all — join the <p> children explicitly
        // instead so the frontend's blank-line paragraph splitter has
        // something to split on. Falls back to the flattened text only if
        // the <article> tag has no <p> children to work with.
        String joined = joinParagraphs(articleTags.select("p"));
        return joined.isBlank() ? articleTags.text() : joined;
    }

    private String extractFromParagraphs(Document doc) {
        return joinParagraphs(doc.select("p"));
    }

    private String joinParagraphs(Elements paragraphs) {
        StringBuilder sb = new StringBuilder();
        for (Element p : paragraphs) {
            String text = p.text().trim();
            if (text.length() < MIN_PARAGRAPH_LENGTH) {
                continue;
            }
            sb.append(text).append("\n\n");
        }
        return sb.toString().trim();
    }
}
