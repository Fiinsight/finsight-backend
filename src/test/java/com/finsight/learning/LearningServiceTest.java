package com.finsight.learning;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.finsight.auth.*;
import com.finsight.news.*;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.external.AiServiceClient;
import com.finsight.term.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class LearningServiceTest {
    @Test void answersAreServerGradedAndProgressIsOwnerScoped() {
        var news = mock(NewsRepository.class); var users = mock(UserRepository.class);
        var terms = mock(TermRepository.class); var progress = mock(LearningProgressRepository.class);
        var ai = mock(AiServiceClient.class); var profile = mock(UserProfileService.class);
        var extractor = mock(ArticleContentExtractor.class); var article = mock(News.class);
        var target = new Term("환율", "통화를 교환하는 비율");
        when(news.findById(115L)).thenReturn(Optional.of(article));
        when(article.getId()).thenReturn(115L); when(article.getTitle()).thenReturn("환율 변화");
        when(article.getRawContent()).thenReturn("AI와 매출 변화도 있지만 환율은 변했다.");
        when(terms.findAll()).thenReturn(List.of(target, new Term("금리", "이자 비율"), new Term("기준금리", "정책 이자 비율"), new Term("AI", "인공지능"), new Term("매출", "판매 금액")));
        when(users.findById(1L)).thenReturn(Optional.of(new User("test", "", "", AuthProvider.LOCAL)));
        when(progress.findByUser_IdAndNews_IdAndTerm(1L, 115L, "환율")).thenReturn(Optional.empty());
        when(extractor.clean(any(), any())).thenReturn("환율은 변했다.");
        when(ai.learning(any())).thenReturn(Optional.empty());
        when(profile.getOnboarding(1L)).thenReturn(new UserProfileDtos.OnboardingResponse(List.of(), "analyst", "deep", "news", "", null));
        var service = new LearningService(news, users, terms, progress, profile, ai, extractor);
        var lesson = service.lesson(1L,115L,null);
        when(article.getRewriteMode()).thenReturn("REWRITTEN"); when(article.getRewrittenBeginner()).thenReturn("저장된 실제 리라이팅이에요.");
        clearInvocations(ai);
        var storedLesson = service.lesson(1L,115L,"beginner");
        assertEquals("REWRITTEN", storedLesson.mode()); assertEquals("저장된 실제 리라이팅이에요.", storedLesson.summary()); verifyNoInteractions(ai);
        when(article.getRewriteMode()).thenReturn("EXCERPT");
        assertEquals("EXCERPT", service.lesson(1L,115L,"beginner").mode());
        when(article.getRewriteMode()).thenReturn(null);
        assertEquals("analyst", lesson.level()); assertEquals("UNAVAILABLE", lesson.mode());
        assertEquals("환율", lesson.question().term()); // Title beats alphabetical AI/body terms.
        assertEquals(lesson.question().options(), service.lesson(1L,115L,"beginner").question().options());
        assertEquals(3, lesson.question().options().size());
        assertFalse(lesson.question().options().contains("인공지능"));
        assertFalse(lesson.question().options().contains("판매 금액"));
        int correct = lesson.question().options().indexOf(target.getShortDefinition());
        var right = service.answer(1L,115L,"beginner","환율",correct);
        var wrong = service.answer(1L,115L,"beginner","환율",(correct+1)%3);
        assertTrue(right.correct()); assertFalse(wrong.correct());
        assertEquals(target.getShortDefinition(), right.definition()); assertEquals(target.getShortDefinition(), wrong.definition());
        assertFalse(java.util.Arrays.stream(LearningService.AnswerResult.class.getRecordComponents()).anyMatch(field -> field.getName().equals("correctIndex")));
        verify(progress,times(2)).findByUser_IdAndNews_IdAndTerm(1L,115L,"환율");
        service.reviews(2L); verify(progress).findTop50ByUser_IdAndCorrectFalseOrderByAnsweredAtDesc(2L);
        assertThrows(ResponseStatusException.class, () -> service.answer(1L,115L,"normal","다른용어",0));
        assertThrows(ResponseStatusException.class, () -> service.lesson(1L,115L,"invalid"));
    }

    @Test void differentLevelsUseSourceFactsAndFallbackIsSavedAsBeginner() {
        var news=mock(NewsRepository.class); var users=mock(UserRepository.class); var terms=mock(TermRepository.class);
        var progress=mock(LearningProgressRepository.class); var profiles=mock(UserProfileService.class);
        var ai=mock(AiServiceClient.class); var extractor=mock(ArticleContentExtractor.class); var article=mock(News.class);
        when(news.findById(1L)).thenReturn(Optional.of(article)); when(article.getId()).thenReturn(1L);
        when(article.getTitle()).thenReturn("환율 상승"); when(article.getRawContent()).thenReturn("환율은 2% 상승했다. 수출은 3% 증가했다.");
        when(extractor.clean(any(),any())).thenAnswer(call -> article.getRawContent());
        when(terms.findAll()).thenReturn(List.of(new Term("환율","통화 교환 비율"),new Term("금리","이자 비율"),new Term("물가","평균 가격")));
        when(ai.learning(any())).thenReturn(Optional.empty()); when(users.findById(1L)).thenReturn(Optional.of(new User("x","","",AuthProvider.LOCAL)));
        var service=new LearningService(news,users,terms,progress,profiles,ai,extractor);
        assertTrue(service.lesson(1L,1L,"beginner").question().prompt().contains("기본 의미"));
        var normal=service.lesson(1L,1L,"normal").question(); assertTrue(normal.prompt().contains("어떻게 변했나요"));
        assertEquals(normal,service.lesson(1L,1L,"normal").question());
        assertTrue(service.answer(1L,1L,"normal","환율",normal.options().indexOf("증가")).correct());
        var analyst=service.lesson(1L,1L,"analyst").question(); assertTrue(analyst.prompt().contains("사실이 아닌"));
        assertEquals(analyst,service.lesson(1L,1L,"analyst").question());
        int outside=-1; for(int i=0;i<3;i++) if(!article.getRawContent().contains(analyst.options().get(i))) outside=i;
        assertTrue(outside>=0); assertTrue(service.answer(1L,1L,"analyst","환율",outside).correct());
        for (String ambiguous : List.of("환율은 2% 상승할 전망이다.","환율은 2% 올랐다가 1% 하락했다.","환율 변동과 함께 금리가 2% 상승했다.","환율은 상승했다.")) {
            when(article.getRawContent()).thenReturn(ambiguous);
            assertTrue(service.lesson(1L,1L,"normal").question().prompt().contains("기본 의미"));
        }
        when(article.getRawContent()).thenReturn("환율은 변할 전망이다.");
        var fallback=service.lesson(1L,1L,"normal").question(); assertTrue(fallback.prompt().contains("기본 의미"));
        service.answer(1L,1L,"normal","환율",fallback.options().indexOf("통화 교환 비율"));
        var saved=org.mockito.ArgumentCaptor.forClass(LearningProgress.class); verify(progress,times(3)).save(saved.capture());
        assertEquals(List.of("normal","analyst","beginner"),saved.getAllValues().stream().map(LearningProgress::getLevel).toList());
        verify(ai,never()).generateFeedback(any());
    }
}
