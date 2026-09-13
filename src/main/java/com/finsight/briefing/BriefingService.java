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
        if (latest.size() >= MIN_REQUIRED_ITEMS) {
            return latest.stream().map(this::toBriefResponse).toList();
        }
        // Fresh checkout / scheduler hasn't collected anything yet: never return
        // an empty or partial briefing, fall back to the original sample data.
        return sampleBriefing();
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

    private List<NewsBriefResponse> sampleBriefing() {
        return List.of(
                new NewsBriefResponse(
                        1L,
                        "반도체 수출 회복세, 대형주 실적 기대감 확대",
                        "반도체 업황 회복 신호가 이어지며 국내 대형 기술주의 실적 기대가 커지고 있습니다.",
                        "수출과 실적 전망은 주가 방향을 판단하는 핵심 근거입니다.",
                        "005930",
                        SentimentHint.POSITIVE
                ),
                new NewsBriefResponse(
                        2L,
                        "원달러 환율 변동성 확대, 외국인 수급 주목",
                        "환율이 단기적으로 흔들리면서 외국인 매수세와 수입 비용 부담이 함께 관찰됩니다.",
                        "환율은 기업 이익과 외국인 자금 흐름에 동시에 영향을 줍니다.",
                        "KOSPI",
                        SentimentHint.NEUTRAL
                ),
                new NewsBriefResponse(
                        3L,
                        "금리 동결 전망 우세, 성장주 밸류에이션 부담 완화",
                        "기준금리 동결 가능성이 커지며 성장주의 할인율 부담이 일부 낮아질 수 있습니다.",
                        "금리 변화는 미래 이익의 현재 가치 평가에 직접 연결됩니다.",
                        "KQ150",
                        SentimentHint.POSITIVE
                )
        );
    }
}

