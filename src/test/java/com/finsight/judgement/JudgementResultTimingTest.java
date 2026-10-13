package com.finsight.judgement;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
class JudgementResultTimingTest {
    private Instant kst(String day) { return ZonedDateTime.parse(day+"T14:00:00+09:00").toInstant(); }
    @Test void fridayAndHolidaysReturnVerifiedNextTradingDayAnd1540Kst() {
        assertThat(JudgementService.expectedResultDate(kst("2026-10-16"))).isEqualTo(LocalDate.parse("2026-10-19"));
        assertThat(JudgementService.expectedResultDate(kst("2026-09-23"))).isEqualTo(LocalDate.parse("2026-09-28"));
        assertThat(JudgementService.expectedResultDate(kst("2026-10-02"))).isEqualTo(LocalDate.parse("2026-10-06"));
        assertThat(JudgementService.expectedResultAt(kst("2026-10-08"))).isEqualTo(Instant.parse("2026-10-12T06:40:00Z"));
        assertThat(JudgementService.expectedResultAt(kst("2027-01-01"))).isNull(); // Unverified calendar never invents a holiday result.
    }
    @Test void acknowledgementIncludesTimingForBothCreateAndRevision() {
        var owner=mock(com.finsight.auth.User.class);var news=mock(com.finsight.news.News.class);when(news.getId()).thenReturn(1L);
        var articles=mock(com.finsight.news.NewsRepository.class);when(articles.findById(1L)).thenReturn(java.util.Optional.of(news));
        var rows=mock(JudgementRepository.class);when(rows.findFirstByUserAndNewsIdOrderByCreatedAtDescIdDesc(owner,1L)).thenReturn(java.util.Optional.empty());
        when(rows.save(any())).thenAnswer(call->{var row=call.<Judgement>getArgument(0);org.springframework.test.util.ReflectionTestUtils.setField(row,"id",10L);org.springframework.test.util.ReflectionTestUtils.setField(row,"createdAt",kst("2026-10-16"));return row;});
        var service=new JudgementService(rows,articles);
        var response=service.recordJudgement(owner,new JudgementRequest(1L,JudgementChoice.UP,"근거"));
        assertThat(response.judgementId()).isEqualTo(10L);assertThat(response.expectedResultDate()).isEqualTo(LocalDate.parse("2026-10-19"));assertThat(response.expectedResultAt()).isNotNull();
        var existing=new Judgement(owner,news,JudgementChoice.DOWN,"이전 근거");when(rows.findFirstByUserAndNewsIdOrderByCreatedAtDescIdDesc(owner,1L)).thenReturn(java.util.Optional.of(existing));
        var revised=service.recordJudgement(owner,new JudgementRequest(1L,JudgementChoice.UP,"새 근거"));
        assertThat(revised.feedback()).isEqualTo("판단을 수정했어요");assertThat(revised.expectedResultDate()).isEqualTo(JudgementService.expectedResultDate(existing.getCreatedAt()));
    }
}
