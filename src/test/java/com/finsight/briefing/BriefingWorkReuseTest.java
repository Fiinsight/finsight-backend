package com.finsight.briefing;
import com.finsight.news.*;import com.finsight.news.collect.*;import org.junit.jupiter.api.Test;import java.time.*;import java.util.*;
import static org.mockito.Mockito.*;
class BriefingWorkReuseTest {
    @Test void cachedCardsReuseRankingScoreAndCategory() {
        var rows=mock(NewsRepository.class);var extractor=mock(ArticleContentExtractor.class);var categories=mock(NewsCategoryClassifier.class);var scorer=mock(NewsRelevanceScorer.class);
        var article=spy(new News("금리 발표","https://fixture.test","TEST",Instant.now().minusSeconds(1),"금리가 동결됐다."));when(article.getId()).thenReturn(7L);
        when(rows.findByPublishedAtBetweenOrderByPublishedAtDesc(any(),any(),any())).thenReturn(List.of(article));when(extractor.isUsable(any(),any())).thenReturn(true);when(scorer.scoreArticle(any(),any())).thenReturn(2);
        var service=new BriefingService(rows,extractor,categories,scorer);
        service.getTodayBriefing();service.getTodayBriefing();
        verify(scorer,times(1)).scoreArticle(article.getTitle(),article.getRawContent());
        verify(categories,times(1)).classify(article.getTitle(),article.getRawContent());
    }
}
