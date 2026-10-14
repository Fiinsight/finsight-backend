package com.finsight.judgement;
import com.finsight.auth.User;
import com.finsight.external.*;
import com.finsight.news.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StructuredFeedbackTest {
    @Test void aiResponseMapsStructuredFieldsAndLegacySummary() throws Exception {
        var mapper = new ObjectMapper();
        var value = mapper.readValue("{\"verdict\":\"CORRECT\",\"headline\":\"방향 일치\",\"summary\":\"결과\",\"reasons\":[{\"title\":\"기사 근거\",\"detail\":\"기사 사실이에요.\"},{\"title\":\"실제 시장\",\"detail\":\"1.76% 움직였어요.\"}],\"nextTip\":\"기준을 확인해요.\"}", AiFeedbackResponse.class);
        assertThat(value.verdict()).isEqualTo("CORRECT");
        assertThat(value.reasons()).hasSize(2);
        assertThat(value.feedbackText()).isEqualTo("결과");
        assertThat(value.nextTip()).isEqualTo("기준을 확인해요.");
        assertThat(mapper.readValue("{\"feedback\":\"기존 문단\"}", AiFeedbackResponse.class).summary()).isEqualTo("기존 문단");
        var content = new Judgement.FeedbackContent(value.verdict(), value.reasons(), value.nextTip());
        assertThat(mapper.readValue(mapper.writeValueAsString(content), Judgement.FeedbackContent.class)).isEqualTo(content);
    }
    @Test void historyDetailMapsReasonsAndLeavesLegacyNullWithoutWrites() {
        var repo = mock(JudgementRepository.class);
        var owner = mock(User.class);
        var article = mock(News.class);
        when(article.getId()).thenReturn(1L);
        var record = new Judgement(owner, article, JudgementChoice.UP, "내 근거");
        record.setFeedbackText("기존 문단");
        when(repo.findByIdAndUser(10L, owner)).thenReturn(Optional.of(record));
        var service = new JudgementService(repo, mock(NewsRepository.class));
        var legacy = service.getDetail(owner, 10L);
        assertThat(legacy.reasons()).isNull();
        assertThat(legacy.verdict()).isNull();
        assertThat(legacy.feedbackText()).isEqualTo("기존 문단");
        var reasons = List.of(new AiFeedbackResponse.Reason("기사", "기사 사실."), new AiFeedbackResponse.Reason("시장", "시장 자료."));
        record.setStructuredFeedback("결과", new Judgement.FeedbackContent("PARTIAL", reasons, "팁."));
        assertThat(service.getDetail(owner, 10L).reasons()).isEqualTo(reasons);
        assertThat(service.getDetail(owner, 10L).verdict()).isEqualTo("PARTIAL");
        verify(repo, never()).save(any());
        var other = mock(User.class);
        when(repo.findByIdAndUser(10L, other)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getDetail(other, 10L)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void migrationAddsOnlyNullableColumnsAndAtomicUpdateIncludesMetadata() throws Exception {
        String sql = new String(java.util.Objects.requireNonNull(getClass().getResourceAsStream("/db/migration/V9__add_structured_judgement_feedback.sql")).readAllBytes());
        assertThat(sql).contains("feedback_reasons JSONB", "feedback_headline VARCHAR(255)").doesNotContain("UPDATE judgement", "NOT NULL");
        var method = Arrays.stream(JudgementRepository.class.getMethods()).filter(item -> item.getName().equals("finalizeOutcome")).findFirst().orElseThrow();
        for (String name : List.of("findPendingByOwner", "findByFeedbackGeneratedAtIsNullAndCreatedAtBefore")) {
            var pending = Arrays.stream(JudgementRepository.class.getMethods()).filter(item -> item.getName().equals(name)).findFirst().orElseThrow();
            assertThat(pending.getAnnotation(org.springframework.data.jpa.repository.Query.class).value()).contains("LEFT JOIN FETCH j.user");
        }
        assertThat(method.getAnnotation(org.springframework.data.jpa.repository.Query.class).value()).contains("j.feedbackHeadline=:headline", "j.feedbackContent=:content", "j.createdAt=:judgedAt", "j.actualDirection IS NULL", "j.feedbackGeneratedAt IS NULL");
    }
    @Test void mismatchedLegacyTargetNeverReturnsOldPraiseOrChangesTheRow() {
        var repo=mock(JudgementRepository.class);var owner=mock(User.class);
        var article=new News("고용노동부 고객센터, AI 상담 24시간 제공","https://example.test","fixture",java.time.Instant.now(),"카카오톡에서 고용노동부 채널을 검색하면 AI 상담을 이용할 수 있다.");article.setRelatedSymbol("035720");
        var row=new Judgement(owner,article,JudgementChoice.DOWN,null);row.setActualDirection("DOWN");row.setActualChangePercent(-1.76);row.setFeedbackText("뉴스와 시장 반응을 잘 연결한 판단입니다.");
        when(repo.findByIdAndUser(14L,owner)).thenReturn(Optional.of(row));
        var view=new JudgementService(repo,mock(NewsRepository.class)).getDetail(owner,14L);
        assertThat(view.gradingIssue()).isEqualTo("TARGET_MISMATCH");assertThat(view.feedbackText()).contains("카카오", "연결을 다시 확인").doesNotContain("잘 연결한");assertThat(view.verdict()).isNull();
        assertThat(row.getFeedbackText()).isEqualTo("뉴스와 시장 반응을 잘 연결한 판단입니다.");verify(repo,never()).save(any());
    }
    @Test void sameDayLegacyComparisonHasAnExplanationInsteadOfPraise() {
        var repo=mock(JudgementRepository.class);var owner=mock(User.class);
        var article=new News("삼성전자 매출 증가","https://example.test","fixture",java.time.Instant.now(),"삼성전자 매출이 증가했다. 영업이익도 늘었다.");article.setRelatedSymbol("005930");
        var row=new Judgement(owner,article,JudgementChoice.UP,null);row.setActualDirection("UP");row.setFeedbackText("정확한 판단입니다.");
        when(repo.findByIdAndUser(1L,owner)).thenReturn(Optional.of(row));
        var view=new JudgementService(repo,mock(NewsRepository.class)).getDetail(owner,1L);
        assertThat(view.gradingIssue()).isEqualTo("LEGACY_COMPARISON");assertThat(view.feedbackText()).contains("판단한 날의 가격").doesNotContain("정확한 판단");verify(repo,never()).save(any());
    }
    @Test void oldRuleExplanationIsEnrichedOnlyInTheResponse() {
        var repo=mock(JudgementRepository.class);var owner=mock(User.class);
        var article=new News("삼성전자 실적 개선","https://example.test","fixture",java.time.Instant.now(),"삼성전자 매출이 증가했다. 영업이익도 늘었다.");article.setRelatedSymbol("005930");
        var row=new Judgement(owner,article,JudgementChoice.UP,"실적 개선");row.setActualDirection("UP");row.setClosingComparison(java.time.LocalDate.parse("2026-10-14"),100,102);
        var old=new AiFeedbackResponse.Reason("기사 신호","기사 핵심 “매출이 증가했다.”에 호재 신호가 있고 실제 상승 방향도 같았어요.");
        row.setStructuredFeedback("결과",new Judgement.FeedbackContent("CORRECT",List.of(old),"팁."));
        when(repo.findByIdAndUser(1L,owner)).thenReturn(Optional.of(row));
        var view=new JudgementService(repo,mock(NewsRepository.class)).getDetail(owner,1L);
        assertThat(view.reasons().getFirst().detail()).contains("이익에 대한 기대를 높여");assertThat(row.getFeedbackContent().reasons().getFirst()).isEqualTo(old);verify(repo,never()).save(any());
    }
}
