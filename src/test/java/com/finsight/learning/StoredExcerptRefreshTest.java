package com.finsight.learning;

import com.finsight.auth.UserProfileService;
import com.finsight.auth.UserRepository;
import com.finsight.briefing.BriefingService;
import com.finsight.external.AiServiceClient;
import com.finsight.news.News;
import com.finsight.news.NewsRepository;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.term.TermRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoredExcerptRefreshTest {
    @ParameterizedTest
    @ValueSource(strings = {"beginner", "normal", "analyst"})
    void storedExcerptUsesFreshFreeReadingWithoutChangingTheStoredArticle(String level) {
        var repository = mock(NewsRepository.class);
        var ai = mock(AiServiceClient.class);
        var article = new News("반도체 수출", "https://fixture.test", "fixture", Instant.EPOCH,
                "반도체 수출이 증가했다. HBM 수요 증가가 원인이라는 분석이다.");
        article.setRewriteMode("EXCERPT");
        article.setRewrittenBeginner("이전 초보 발췌");
        article.setRewrittenNormal("이전 일반 발췌");
        article.setRewrittenAnalyst("이전 분석 발췌");
        when(repository.findById(1L)).thenReturn(Optional.of(article));
        when(ai.learning(any())).thenReturn(Optional.of(new AiServiceClient.ReadingAid(level,
                "새 원문 근거가 보존된 발췌", "비교 기준을 확인하세요.", "EXCERPT")));
        var service = new LearningService(repository, mock(UserRepository.class), mock(TermRepository.class),
                mock(LearningProgressRepository.class), mock(UserProfileService.class), ai,
                new ArticleContentExtractor(), mock(TermMasteryService.class), mock(BriefingService.class));

        var fresh = service.lesson(7L, 1L, level);
        assertEquals("새 원문 근거가 보존된 발췌", fresh.summary());
        assertEquals("EXCERPT", fresh.mode());
        verify(ai).learning(argThat(request -> level.equals(request.level()) && request.body().contains("HBM") && !request.allowLlm()));
        assertEquals("이전 초보 발췌", article.getRewrittenBeginner());
        assertEquals("이전 일반 발췌", article.getRewrittenNormal());
        assertEquals("이전 분석 발췌", article.getRewrittenAnalyst());
        verify(repository, never()).save(any());

        String stored = switch (level) {
            case "beginner" -> article.getRewrittenBeginner();
            case "normal" -> article.getRewrittenNormal();
            default -> article.getRewrittenAnalyst();
        };
        when(ai.learning(any())).thenReturn(Optional.empty());
        assertEquals(stored, service.lesson(7L, 1L, level).summary());
        when(ai.learning(any())).thenReturn(Optional.of(new AiServiceClient.ReadingAid(level, "", "", "EXCERPT")));
        assertEquals(stored, service.lesson(7L, 1L, level).summary());

        article.setRewriteMode("REWRITTEN");
        clearInvocations(ai);
        var verified = service.lesson(7L, 1L, level);
        assertEquals(stored, verified.summary());
        assertEquals("REWRITTEN", verified.mode());
        verifyNoInteractions(ai);

        article.setRewriteMode("EXCERPT");
        article.setRawContent("");
        assertEquals(stored, service.lesson(7L, 1L, level).summary());
        verifyNoInteractions(ai);
        verify(repository, never()).save(any());
    }
}
