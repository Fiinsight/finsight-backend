package com.finsight.judgement;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.finsight.auth.User;
import com.finsight.news.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
class JudgementRevisionTest {
    @Test void pendingRevisionPreservesIdAndConfirmedResultReturns409() {
        var records=mock(JudgementRepository.class); var newsRepo=mock(NewsRepository.class);
        var owner=mock(User.class); var news=mock(News.class); when(news.getTitle()).thenReturn("삼성전자 실적 발표"); when(news.getRawContent()).thenReturn("삼성전자 매출이 증가했다. 영업이익이 늘었다."); when(news.getId()).thenReturn(211L);
        when(newsRepo.findById(211L)).thenReturn(Optional.of(news));
        var existing=new Judgement(owner,news,JudgementChoice.UP,"最初");
        when(records.findFirstByUserAndNewsIdOrderByCreatedAtDescIdDesc(owner,211L)).thenReturn(Optional.of(existing));
        var service=new JudgementService(records,newsRepo);
        var response=service.recordJudgement(owner,new JudgementRequest(211L,JudgementChoice.DOWN,"수정 근거"));
        assertThat(response.feedback()).isEqualTo("판단을 수정했어요");
        assertThat(existing.getChoice()).isEqualTo(JudgementChoice.DOWN); assertThat(existing.getReasonText()).isEqualTo("수정 근거");
        verify(records).save(existing);
        existing.setActualDirection("UNKNOWN");
        assertThatThrownBy(()->service.recordJudgement(owner,new JudgementRequest(211L,JudgementChoice.UP,"추가")))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("409");
        verify(records,times(1)).save(any());
    }
    @Test void unsupportedArticleIsRejectedBeforeSaving() {
        var records=mock(JudgementRepository.class);var newsRepo=mock(NewsRepository.class);var article=new News("[부고] 기업 임원 별세","https://example.test","fixture",java.time.Instant.now(),"부고 안내입니다.");
        when(newsRepo.findById(1L)).thenReturn(Optional.of(article));
        assertThatThrownBy(()->new JudgementService(records,newsRepo).recordJudgement(mock(User.class),new JudgementRequest(1L,JudgementChoice.UP,null))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("400");verifyNoInteractions(records);
    }
}
