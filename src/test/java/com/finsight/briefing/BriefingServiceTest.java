package com.finsight.briefing;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.finsight.news.*;
import com.finsight.news.collect.*;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.web.server.ResponseStatusException;

class BriefingServiceTest {
    @Test void scoreBeatsRecencyAndPaginationContinuesTheSameRanking() {
        var repository = mock(NewsRepository.class);
        var extractor = mock(ArticleContentExtractor.class);
        when(extractor.isUsable(any(), any())).thenReturn(true);
        Instant today = LocalDate.now(ZoneId.of("Asia/Seoul")).atStartOfDay(ZoneId.of("Asia/Seoul")).toInstant();
        News newest = news(1L, "투자", today.plusSeconds(400));
        News high = news(2L, "금리 환율 실적 증시", today.plusSeconds(100));
        News middle = news(3L, "반도체 수출 주가", today.plusSeconds(200));
        News tied = news(4L, "금리 실적", today.plusSeconds(300));
        News old = news(5L, "금리 환율 실적 증시 물가 수출", today.minusSeconds(1));
        when(repository.findAllByOrderByPublishedAtDesc(any(Pageable.class))).thenReturn(List.of(newest, tied, middle, high, old));
        var service = new BriefingService(repository, extractor, new NewsCategoryClassifier(), new NewsRelevanceScorer());
        assertEquals(List.of(2L, 3L, 4L), service.getTodayBriefing().stream().map(NewsBriefResponse::id).toList());
        assertEquals(List.of(1L, 5L), service.getMoreBriefing(0, 10).stream().map(NewsBriefResponse::id).toList());
        assertTrue(service.getMoreBriefing(Integer.MAX_VALUE, 100).isEmpty());
        assertThrows(ResponseStatusException.class, () -> service.getMoreBriefing(-1, 10));
        assertThrows(ResponseStatusException.class, () -> service.getMoreBriefing(0, 101));
        assertTrue(service.getTodayBriefing().get(0).summary().contains("fallback"));
        assertTrue(NewsDetailResponse.excerpt("본문".repeat(1000)).length() < 650);
    }
    private News news(Long id, String title, Instant published) {
        var news = mock(News.class);
        when(news.getId()).thenReturn(id);
        when(news.getTitle()).thenReturn(title);
        when(news.getPublishedAt()).thenReturn(published);
        when(news.getRawContent()).thenReturn("기사 본문");
        return news;
    }
}
