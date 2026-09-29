package com.finsight.chart;

import static org.assertj.core.api.Assertions.assertThat;

import com.finsight.news.News;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChartServiceTest {

    @Test
    void removesExactRelatedNewsDuplicates() {
        Instant publishedAt = Instant.parse("2026-09-29T01:00:00Z");
        News first = new News("삼성전자 신규 투자", "https://one.example/news", "매체", publishedAt, "같은 본문");
        News duplicate = new News("삼성전자 신규 투자", "https://two.example/news", "매체", publishedAt, "같은 본문");
        News other = new News("다른 기사", "https://three.example/news", "매체", publishedAt, "다른 본문");

        assertThat(ChartService.deduplicateRelatedNews(List.of(first, duplicate, other)))
                .containsExactly(first, other);
    }

    @Test
    void detectsOnlyMovesThatExceedRecentRelativeVolatility() {
        List<Double> stableChanges = List.of(0.10, -0.10, 0.08, -0.08, 0.10);

        assertThat(ChartService.isSignificantMove(stableChanges, 0.15)).isFalse();
        assertThat(ChartService.isSignificantMove(stableChanges, 0.50)).isTrue();
        assertThat(ChartService.isSignificantMove(new ArrayList<>(stableChanges).subList(0, 4), 0.50))
                .isFalse();
    }

    @Test
    void matchesOnlyTheLatestNewsPublishedBeforeTheMove() {
        Instant moveAt = Instant.parse("2026-09-29T02:00:00Z");
        News before = new News("이전 기사", "https://before.example", "매체", moveAt.minusSeconds(3600), "본문");
        News after = new News("이후 기사", "https://after.example", "매체", moveAt.plusSeconds(60), "본문");

        assertThat(ChartService.latestNewsBefore(List.of(before, after), moveAt))
                .isSameAs(before);
    }
}
