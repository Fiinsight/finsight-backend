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
}
