package com.finsight.news.collect;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.List;
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

            removePageChrome(doc);

            String text = extractFromArticleBody(doc);
            if (!StringUtils.hasText(text)) {
                text = extractFromArticleTag(doc);
            }
            if (!StringUtils.hasText(text)) {
                text = extractFromParagraphs(doc);
            }
            String cleaned = clean("", text);
            return StringUtils.hasText(cleaned) ? Optional.of(cleaned) : Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to extract article body from {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    // A paragraph shorter than this is almost certainly page chrome (share
    // buttons, font-size controls, "번역" widgets) rather than actual article
    // text, so it's dropped instead of getting mixed into the body.
    private static final int MIN_PARAGRAPH_LENGTH = 20;

    private static final List<String> ECONOMIC_TERMS = List.of(
            "금리", "환율", "증시", "주가", "수출", "무역", "반도체", "기업", "투자", "물가", "채권", "고용", "실적");
    private static final List<String> PAGE_CHROME = List.of(
            "Google 검색", "검색어를 입력", "개인정보처리방침", "로그인 후", "쿠키 설정",
            "주소 :", "전화 :", "일간신문등록번호", "저작권", "무단전재", "무제한으로 만나보세요",
            "AI가 제공", "서비스 이용 제한", "회원가입", "로그인", "구독", "댓글", "공유");

    public boolean isUsable(String title, String body) {
        String cleaned = clean(title, body);
        if (!StringUtils.hasText(cleaned) || cleaned.length() < 120) return false;
        String combined = (title + " " + cleaned).toLowerCase();
        return ECONOMIC_TERMS.stream().anyMatch(combined::contains);
    }

    /** Remove publisher search/SEO chrome and a duplicated headline before rewriting. */
    public String clean(String title, String body) {
        if (!StringUtils.hasText(body)) {
            return "";
        }
        body = cutKnownPageChrome(body)
                .replace("ⓒ 한경닷컴, 무단전재 및 재배포 금지", "")
                .trim();
        String titleKey = compact(title);
        String[] parts = body.replace('\r', '\n').split("(?<=[.!?。！？])\\s+|\\n+");
        List<String> cleaned = new ArrayList<>();
        for (String part : parts) {
            String item = part.trim().replaceAll("\\s+", " ");
            if (item.isBlank() || PAGE_CHROME.stream().anyMatch(item::contains)) {
                continue;
            }
            if (!titleKey.isBlank() && compact(item).equals(titleKey)) {
                continue;
            }
            cleaned.add(item);
        }
        return String.join("\n\n", cleaned).trim();
    }

    private String cutKnownPageChrome(String body) {
        int cut = body.length();
        for (String marker : List.of(
                "주소 :", "주소:", "한경 프리미엄9의 모든 콘텐츠",
                "일간신문등록번호", "개인정보처리방침", "서비스 이용 제한",
                "ⓒ 한경닷컴, 무단전재 및 재배포 금지", "1000원의 힘",
                "삼전닉스 괜히 팔았나")) {
            int index = body.indexOf(marker);
            if (index > 0) {
                cut = Math.min(cut, index);
            }
        }
        return body.substring(0, cut);
    }

    private void removePageChrome(Document doc) {
        doc.select("script, style, nav, header, footer, aside, form, "
                + "[class*=ad], [class*=advert], [class*=share], [class*=comment], "
                + "[class*=related], [class*=recommend], [class*=copyright], [class*=footer], "
                + "[id*=footer], [id*=comment], [id*=related], [id*=recommend], "
                + "[class*=subscribe], [class*=recirculation], [class*=promotion], [class*=article-list]")
                .remove();
    }

    private String compact(String value) {
        return value == null ? "" : value.replaceAll("[^0-9A-Za-z가-힣]", "").toLowerCase();
    }

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

    private String extractFromArticleBody(Document doc) {
        Element articleBody = doc.selectFirst("[itemprop=articleBody]");
        return articleBody == null ? null : articleBody.text();
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
