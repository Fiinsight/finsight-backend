package com.finsight.news.collect;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.List;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; FinsightBot/1.0)";

    public Optional<String> extract(String url) {
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout((int) FETCH_TIMEOUT.toMillis())
                    .get();

            return extractDocument(doc, url);
        } catch (Exception e) {
            log.warn("Failed to extract article body from {}: {}", url, e.getMessage());
            return Optional.empty();
        }
    }

    /** Parse an already fetched page without issuing another publisher request. */
    public Optional<String> extractHtml(String url, String html) {
        return extractDocument(Jsoup.parse(html, url), url);
    }

    private Optional<String> extractDocument(Document doc, String url) {
            String text = extractFromJsonLd(doc);
            removePageChrome(doc);
            if (!StringUtils.hasText(text)) {
                text = extractFromKnownPublisherBody(doc, url);
            }
            if (!StringUtils.hasText(text)) {
                text = extractFromArticleBody(doc);
            }
            if (!StringUtils.hasText(text)) {
                text = extractFromArticleTag(doc);
            }
            if (!StringUtils.hasText(text)) {
                text = extractFromParagraphs(doc);
            }
            String cleaned = clean("", text);
            return StringUtils.hasText(cleaned) ? Optional.of(cleaned) : Optional.empty();
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
        body = stripPhotoNoise(body).replaceAll("(?s)\\[[^\\]]{0,500}(?:자료사진|사진 제공|재판매|DB 금지)[^\\]]{0,500}\\]", " ")
                .replaceFirst("(?s)^.{0,700}?(?:사진|딜링룸|촬영).{0,500}?20\\d{2}\\.\\d{1,2}\\.\\d{1,2}\\s+[\\w.+-]+@[\\w.-]+\\s*", "")
                .replaceAll("\\([^()\\n]{1,40}=연합뉴스\\)\\s*[가-힣·]{2,12}\\s*기자\\s*=\\s*", "")
                .replaceAll("사진\\s*=\\s*(?:AFP|AP|EPA|로이터|연합뉴스)(?:/[^\\s]+)?\\s*", "")
                .replaceAll("[\\w.+-]+@[\\w.-]+", "");
        String prefix = "^(?:(?:AFP|AP|EPA|로이터|UPI|신화|연합뉴스)(?:\\s*=\\s*)?(?:연합뉴스)?\\s+|\\d{4}\\.\\d{1,2}\\.\\d{1,2}\\s+|\\[공동취재\\]\\s*|\\([^()]{1,20}=연합뉴스\\)\\s*(?:[가-힣]{2,4}\\s*)+기자\\s*=\\s*)";
        while (java.util.regex.Pattern.compile(prefix).matcher(body.strip()).find()) body = body.strip().replaceFirst(prefix, "");
        body = cutKnownPageChrome(body)
                .replace("ⓒ 한경닷컴, 무단전재 및 재배포 금지", "")
                .trim();
        String titleKey = compact(title);
        String[] parts = body.replace('\r', '\n').split("(?<=[.!?。！？])\\s+|\\n+");
        List<String> cleaned = new ArrayList<>();
        for (String part : parts) {
            String item = part.trim().replaceAll("\\s+", " ");
            while (java.util.regex.Pattern.compile(prefix).matcher(item).find()) item = item.replaceFirst(prefix, "").strip();
            if (item.length() < 10 || PAGE_CHROME.stream().anyMatch(item::contains)) {
                continue;
            }
            if (!titleKey.isBlank() && compact(item).equals(titleKey)) {
                continue;
            }
            cleaned.add(item);
        }
        return String.join("\n\n", cleaned).trim();
    }

    public static String stripPhotoNoise(String text) {
        if (text == null || text.isBlank()) return text;
        String notice = "^(?:사진은\\s*기사(?:\\s*내용)?(?:와|과)\\s*(?:관련\\s*없|무관)|이미지는\\s*기사(?:\\s*내용)?(?:와|과)\\s*관련\\s*없|자료사진|일러스트\\s*=|그래픽\\s*=)";
        String credit = "^사진\\s*=\\s*(?:.{1,60}?(?:생성\\s*이미지|제공)|게티이미지뱅크|[^\\s]+)\\s*";
        List<String> kept = new ArrayList<>();
        boolean changed = false;
        for (String part : text.split("(?<=[.!?。！？])(?!\\d)\\s*|\\n+")) {
            String item = part.strip();
            if (java.util.regex.Pattern.compile(notice).matcher(item).find()) { changed = true; continue; }
            String plain = item.replaceFirst(credit, "");
            changed |= !plain.equals(item);
            if (!plain.isBlank()) kept.add(plain);
        }
        return changed ? String.join("\n\n", kept).strip() : text;
    }

    public String eventLead(String title, String body, java.time.LocalDate day, double change) {
        List<String> safe = new ArrayList<>();
        for (String sentence : clean(title, body).split("(?<=[.!?。！？])(?!\\d)\\s*|\\n+")) {
            if (sentence.matches("(?s).*(사진\\s*=|\\(사진\\)|제공).*")) continue;
            if (sentence.matches("(?s).*(지난해|작년|\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일).*열린.*")
                    && sentence.matches("(?s).*(되고|하고)\\s*있(?:다|어요).*")) continue;
            var dates = java.util.regex.Pattern.compile("(20\\d{2})[./년-]\\s*(\\d{1,2})[./월-]\\s*(\\d{1,2})").matcher(sentence);
            boolean old = false;
            while (dates.find()) {
                try {
                    var date = java.time.LocalDate.of(Integer.parseInt(dates.group(1)), Integer.parseInt(dates.group(2)), Integer.parseInt(dates.group(3)));
                    old |= Math.abs(java.time.temporal.ChronoUnit.DAYS.between(day, date)) >= 365;
                } catch (java.time.DateTimeException ignored) { }
            }
            if (!old && !sentence.isBlank()) safe.add(sentence);
        }
        String direction = change < 0 ? "급락|하락|약세|낙폭|내려|떨어|팔자|매도|하회|부진|우려" : "급등|상승|강세|반등|올라|사자|매수|상회|호실적|수혜";
        for (String sentence : safe) if (java.util.regex.Pattern.compile(direction).matcher(sentence).find()) return sentence;
        for (String sentence : safe) if (sentence.matches("(?s).*\\d.*")) return sentence;
        return safe.isEmpty() ? title : safe.getFirst();
    }

    private String cutKnownPageChrome(String body) {
        int cut = body.length();
        for (String marker : List.of(
                "주소 :", "주소:", "한경 프리미엄9의 모든 콘텐츠",
                "일간신문등록번호", "개인정보처리방침", "서비스 이용 제한",
                "ⓒ 한경닷컴, 무단전재 및 재배포 금지", "제보는 카카오톡", "<저작권자", "1000원의 힘",
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

    private String extractFromJsonLd(Document doc) {
        for (Element script : doc.select("script[type=application/ld+json]")) {
            try {
                JsonNode json = JSON.readTree(script.data());
                String body = json.path("articleBody").asText("");
                if (StringUtils.hasText(body)) {
                    return body;
                }
                JsonNode graph = json.path("@graph");
                if (graph.isArray()) {
                    for (JsonNode item : graph) {
                        String graphBody = item.path("articleBody").asText("");
                        if (StringUtils.hasText(graphBody)) {
                            return graphBody;
                        }
                    }
                }
            } catch (Exception ignored) {
                // Publishers often emit an array or malformed JSON-LD; use the DOM fallbacks.
            }
        }
        return null;
    }

    private String extractFromKnownPublisherBody(Document doc, String url) {
        String host = url.toLowerCase();
        List<String> selectors = new ArrayList<>();
        if (host.contains("mk.co.kr")) {
            selectors.addAll(List.of("#article_body", ".news_cnt_detail_wrap", ".news_cnt_detail", ".art_txt"));
        } else if (host.contains("hankyung.com")) {
            selectors.addAll(List.of("#articletxt", ".article-body", ".article-body__content", ".article-content"));
        } else if (host.contains("yna.co.kr")) {
            selectors.addAll(List.of(".story-news", ".story-news__content", "#articleWrap", ".article-txt"));
        }
        for (String selector : selectors) {
            Element body = doc.selectFirst(selector);
            if (body != null) {
                String text = joinParagraphs(body.select("p"));
                if (StringUtils.hasText(text)) {
                    return text;
                }
                if (StringUtils.hasText(body.text())) {
                    return body.text();
                }
            }
        }
        return null;
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
