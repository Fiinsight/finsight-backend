package com.finsight.judgement;
import com.finsight.auth.User;
import com.finsight.news.NewsRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.assertj.core.api.Assertions.*;
class JudgementSingleReadTest {
    @Test void onlyAuthenticatedOwnerAndRequestedNewsAreUsedWithOneRowLimit() {
        var records=mock(JudgementRepository.class);var owner=mock(User.class);
        var service=new JudgementService(records,mock(NewsRepository.class));
        when(records.findLatestForNews(eq(owner),eq(42L),any())).thenReturn(List.of());
        assertThat(service.getForNews(owner,42L)).isNull();
        verify(records).findLatestForNews(eq(owner),eq(42L),argThat(p -> p.getPageSize()==1));
        verify(records,never()).findAllByUserOrderByCreatedAtDesc(any());
    }
}
