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
        var owner=mock(User.class); var news=mock(News.class); when(news.getId()).thenReturn(211L);
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
}
