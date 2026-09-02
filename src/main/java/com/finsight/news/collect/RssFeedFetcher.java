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
            "https://www.mk.co.kr/rss/50100032/"
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
                Instant publishedAt = entry.getPublishedDate() != null
                        ? entry.getPublishedDate().toInstant()
                        : Instant.now();
                candidates.add(new NewsCandidate(entry.getTitle().trim(), entry.getLink().trim(), source, publishedAt, 0));
            }
        }
        return candidates;
    }
}
