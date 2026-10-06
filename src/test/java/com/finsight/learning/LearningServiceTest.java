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
        assertEquals(correct, right.correctIndex()); assertEquals(correct, wrong.correctIndex());
        assertEquals(target.getShortDefinition(), lesson.question().options().get(wrong.correctIndex()));
        verify(progress,times(2)).findByUser_IdAndNews_IdAndTerm(1L,115L,"환율");
        service.reviews(2L); verify(progress).findTop50ByUser_IdAndCorrectFalseOrderByAnsweredAtDesc(2L);
        assertThrows(ResponseStatusException.class, () -> service.answer(1L,115L,"normal","다른용어",0));
        assertThrows(ResponseStatusException.class, () -> service.lesson(1L,115L,"invalid"));
    }
}
