package com.finsight.term;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.finsight.news.*;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.external.*;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TermServiceTest {
    @Test void dictionarySeedCoversDetectedTermsWithoutOverwritingExistingDefinitions() {
        var repository = mock(TermRepository.class);
        when(repository.findByTermIgnoreCase("환율")).thenReturn(Optional.of(new Term("환율", "기존 정의")));
        new TermSeeder(repository).run();
        var inserted = ArgumentCaptor.forClass(Term.class);
        verify(repository, atLeastOnce()).save(inserted.capture());
        var names = inserted.getAllValues().stream().map(Term::getTerm).toList();
        assertFalse(names.contains("환율"));
        assertTrue(names.containsAll(java.util.List.of("금리", "수급", "국채금리", "PF", "HBM", "원화", "수입", "물가", "연체율", "공모주", "설비투자", "재고 조정")));
    }
    @Test void termContextUsesCleanBodyNearTermAndKeepsDictionaryDefinition() {
        var terms = mock(TermRepository.class); var news = mock(NewsRepository.class); var ai = mock(AiServiceClient.class);
        var article = mock(News.class);
        when(terms.findByTermIgnoreCase("금리")).thenReturn(Optional.of(new Term("금리", "이자의 비율")));
        when(news.findById(212L)).thenReturn(Optional.of(article));
        when(article.getTitle()).thenReturn("코스피 전망");
        when(article.getRawContent()).thenReturn("[연합뉴스 자료사진. DB 금지] (서울=연합뉴스) 박동주 기자 = " + "본문 문장입니다. ".repeat(220) + "금리가 0.25%포인트 올랐다.");
        when(ai.explainTerm(any())).thenReturn(Optional.empty());
        var service = new TermService(terms, news, ai, new ArticleContentExtractor());
        assertEquals("이자의 비율", service.explain(new TermExplainRequest("금리",212L)).definition());
        var request = ArgumentCaptor.forClass(AiTermExplainRequest.class);
        verify(ai).explainTerm(request.capture());
        assertTrue(request.getValue().context().contains("0.25%"));
        assertFalse(request.getValue().context().contains("자료사진"));
        assertFalse(request.getValue().context().contains("기자"));
        assertTrue(request.getValue().context().length() <= 2300);
    }
}
