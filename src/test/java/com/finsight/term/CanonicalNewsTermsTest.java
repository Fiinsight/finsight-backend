package com.finsight.term;
import java.util.*;import org.junit.jupiter.api.Test;import static org.assertj.core.api.Assertions.*;
import com.finsight.news.collect.*;import com.finsight.external.*;
import static org.mockito.Mockito.*;
class CanonicalNewsTermsTest {
    @Test void retryingAnExistingArticleNeverBackfillsItsStoredKeyTerms() {
        var ai=mock(AiServiceClient.class);
        var assembler=new NewsAssembler(ai,new NewsCategoryClassifier(),new NewsSymbolMatcher(),new NewsSentimentClassifier(),mock(ArticleContentExtractor.class));
        var article=new com.finsight.news.News("EPS 분석","https://fixture.test","TEST",java.time.Instant.now(),"EPS는 100원이었다.");
        article.setKeyTerms(List.of("AI","원화"));article.setRewriteRetryPending(true);
        assembler.retryRewrite(article);
        assertThat(article.getKeyTerms()).containsExactly("AI","원화");
    }

    @Test void canonicalMetadataIncludesDictionaryConceptsBeyondLegacyAiListAndKeepsBoundaries() {
        var terms=List.of(new Term("기회비용","포기한 대안의 가치"));
        var result=KeyTermSelector.canonical("기회비용과 EPS 분석","EPS는 100원이다. CHAIR와 저금리도 등장한다.",terms);
        assertThat(result).contains("기회비용","EPS").doesNotContain("AI","금리","반도체");
        var topics=List.of(new Term("AI","인공지능"),new Term("반도체","부품"));
        assertThat(KeyTermSelector.canonical("반도체 AI 뉴스","반도체 AI 수출 매출",topics)).contains("AI","반도체").doesNotContain("투자");
        assertThat(KeyTermSelector.recommendationWeight("AI")).isLessThan(KeyTermSelector.recommendationWeight("영업이익"));
    }
    @Test void collectionUsesCanonicalTermsEvenWhenAiIsUnavailable() {
        var ai=mock(AiServiceClient.class);var terms=mock(TermRepository.class);
        when(terms.findAll()).thenReturn(List.of(new Term("기회비용","대안의 가치")));
        var assembler=new NewsAssembler(ai,new NewsCategoryClassifier(),new NewsSymbolMatcher(),new NewsSentimentClassifier(),mock(ArticleContentExtractor.class),terms);
        var article=assembler.assemble(new NewsCandidate("기회비용 분석","https://fixture.test","TEST",java.time.Instant.now(),0),"기회비용을 비교했다. EPS는 100원이었다.",false);
        assertThat(article.getKeyTerms()).contains("기회비용","EPS");
    }
}
