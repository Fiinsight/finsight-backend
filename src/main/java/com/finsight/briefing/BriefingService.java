package com.finsight.briefing;

import com.finsight.news.News;
import com.finsight.news.NewsRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class BriefingService {

    private static final int MIN_REQUIRED_ITEMS = 3;
    private static final ZoneId KOREA_ZONE = ZoneId.of("Asia/Seoul");

    private final NewsRepository newsRepository;

    public BriefingService(NewsRepository newsRepository) {
        this.newsRepository = newsRepository;
    }

    public List<NewsBriefResponse> getTodayBriefing() {
        // "Today" is scoped to KST midnight so the briefing actually reflects
        // articles collected today, not just whatever 3 rows are newest overall
        // (which could be several days stale if the scheduler has been failing).
        Instant startOfToday = LocalDate.now(KOREA_ZONE).atStartOfDay(KOREA_ZONE).toInstant();
        List<News> today = newsRepository.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(startOfToday);
        if (today.size() >= MIN_REQUIRED_ITEMS) {
            return today.stream().limit(MIN_REQUIRED_ITEMS).map(this::toBriefResponse).toList();
        }
        List<News> latest = newsRepository.findTop3ByOrderByPublishedAtDesc();
        // Never label hard-coded demo copy as today's live market news. During
        // the first collection run this may be empty or partial; the client
        // can show a truthful loading/empty state instead.
        return latest.stream().map(this::toBriefResponse).toList();
    }

    /**
     * "홈에 더 많은 뉴스 더보기" — 오늘의 핵심 3건 다음으로 이어지는 페이지.
     * page=0이 4번째~ 항목(상위 3건 다음)이 되도록 offset을 3만큼 민다.
     */
    public List<NewsBriefResponse> getMoreBriefing(int page, int size) {
        // page=0 is the batch right after the top-3 shown on the home screen.
        // PageRequest only understands page-aligned windows (page*size ..
        // page*size+size), not an arbitrary offset like "skip 3" — so fetch
        // everything up to the end of the requested window in one page-0
        // query, then slice out just the target range in Java.
        int offset = MIN_REQUIRED_ITEMS + page * size;
        var request = PageRequest.of(0, offset + size, Sort.by(Sort.Direction.DESC, "publishedAt"));
        List<News> rows = newsRepository.findAllByOrderByPublishedAtDesc(request);
        if (offset >= rows.size()) {
            return List.of();
        }
        return rows.subList(offset, Math.min(offset + size, rows.size())).stream()
                .map(this::toBriefResponse)
                .toList();
    }

    private NewsBriefResponse toBriefResponse(News news) {
        String summary = news.getRewrittenNormal() != null ? news.getRewrittenNormal() : news.getRawContent();
        return new NewsBriefResponse(
                news.getId(),
                news.getTitle(),
                summary,
                news.getImportanceReason(),
                news.getRelatedSymbol(),
                news.getSentimentHint() != null ? news.getSentimentHint() : SentimentHint.NEUTRAL
        );
    }

}
