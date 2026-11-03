package com.finsight.news.collect;

import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Fetches Korean economic RSS feeds via ROME and turns each entry into a
 * {@link NewsCandidate}. One dead/unreachable feed never aborts the others.
 */
@Component
public class RssFeedFetcher {

    private static final Logger log = LoggerFactory.getLogger(RssFeedFetcher.class);

    private static final List<String> RSS_FEED_URLS = List.of(
            "https://www.yna.co.kr/rss/economy.xml",
            "https://www.hankyung.com/feed/economy",
            "https://www.mk.co.kr/rss/50100032/",
            "https://www.mk.co.kr/rss/50200011/"
    );

    public List<NewsCandidate> fetchAll() {
        List<NewsCandidate> all = new ArrayList<>();
        for (String feedUrl : RSS_FEED_URLS) {
            try {
                all.addAll(fetchOne(feedUrl));
            } catch (Exception e) {
                log.warn("Failed to fetch/parse RSS feed {}, skipping: {}", feedUrl, e.getMessage());
            }
        }
        return all;
    }

    static String imageUrl(SyndEntry entry) {
        for (var element : entry.getForeignMarkup()) {
            var elements = new java.util.ArrayList<org.jdom2.Element>();
            elements.add(element);
            element.getDescendants(org.jdom2.filter.Filters.element()).forEach(elements::add);
            for (var media : elements) {
                if ("http://search.yahoo.com/mrss/".equals(media.getNamespaceURI()) && "content".equals(media.getName())
                        && ("image".equals(media.getAttributeValue("medium")) || java.util.Objects.toString(media.getAttributeValue("type"), "").startsWith("image/")
                        || java.util.Objects.toString(media.getAttributeValue("url"), "").matches("(?i).*\\.(jpg|jpeg|png|webp)(?:[?].*)?"))) {
                    String url = NewsImageUrl.sanitize(media.getAttributeValue("url"), entry.getLink(),media.getAttributeValue("width"),media.getAttributeValue("height"));
                    if (url != null) return url;
                }
            }
        }
        for (var enclosure : entry.getEnclosures()) {
            if (java.util.Objects.toString(enclosure.getType(), "").startsWith("image/")) {
                String url = NewsImageUrl.sanitize(enclosure.getUrl(), entry.getLink());
                if (url != null) return url;
            }
        }
        return null;
    }

    private List<NewsCandidate> fetchOne(String feedUrl) throws Exception {
        List<NewsCandidate> candidates = new ArrayList<>();
        URL url = URI.create(feedUrl).toURL();
        try (XmlReader reader = new XmlReader(url)) {
            SyndFeedInput input = new SyndFeedInput();
            SyndFeed feed = input.build(reader);
            String source = StringUtils.hasText(feed.getTitle()) ? feed.getTitle() : feedUrl;
            for (SyndEntry entry : feed.getEntries()) {
                if (entry.getLink() == null || entry.getTitle() == null) {
                    continue;
                }
                if (entry.getPublishedDate() == null && entry.getUpdatedDate() == null) {
                    log.warn("Skipping RSS entry without published/updated time: {}", entry.getTitle());
                    continue;
                }
                Instant publishedAt = entry.getPublishedDate() != null
                        ? entry.getPublishedDate().toInstant()
                        : entry.getUpdatedDate().toInstant();
                candidates.add(new NewsCandidate(entry.getTitle().trim(), entry.getLink().trim(), source, publishedAt, 0, imageUrl(entry)));
            }
        }
        return candidates;
    }
}
