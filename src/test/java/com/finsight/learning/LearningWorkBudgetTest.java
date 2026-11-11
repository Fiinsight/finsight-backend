package com.finsight.learning;
import com.finsight.news.*;import com.finsight.term.*;import com.finsight.auth.*;import com.finsight.external.*;import com.finsight.news.collect.*;
import java.util.*;import java.time.*;import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;import static org.assertj.core.api.Assertions.*;
class LearningWorkBudgetTest {
    @Test void lessonCleansSourceOnceWhileCheckingSeveralDeterministicQuestions() {
        var news=mock(NewsRepository.class);var terms=mock(TermRepository.class);var extractor=spy(new ArticleContentExtractor());
        var article=spy(new News("금리 환율 발표","https://fixture.test","TEST",Instant.now(),"금리는 2.5% 상승했다. 환율은 1400원으로 상승했다."));when(article.getId()).thenReturn(15L);
        when(news.findById(15L)).thenReturn(Optional.of(article));when(terms.findAll()).thenReturn(List.of(new Term("금리","이자"),new Term("환율","비율"),new Term("기준금리","정책 이자")));
        var service=new LearningService(news,mock(UserRepository.class),terms,mock(LearningProgressRepository.class),mock(UserProfileService.class),mock(AiServiceClient.class),extractor,mock(TermMasteryService.class),mock(com.finsight.briefing.BriefingService.class));
        service.lesson(1L,15L,"normal");verify(extractor,times(1)).clean(article.getTitle(),article.getRawContent());
    }
    @Test void termBoundariesDoNotReadLowRatesOrEnglishSubstringsAsStandaloneTerms() {
        assertThat(KeyTermSelector.containsTerm("저금리와 CHAIR", "금리")).isFalse();
        assertThat(KeyTermSelector.containsTerm("저금리와 CHAIR", "AI")).isFalse();
        assertThat(KeyTermSelector.containsTerm("금리는 동결, AI가 변화", "금리")).isTrue();
        assertThat(KeyTermSelector.containsTerm("금리는 동결, AI가 변화", "AI")).isTrue();
    }
}
