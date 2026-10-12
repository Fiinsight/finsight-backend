package com.finsight.news.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.finsight.external.AiServiceClient;
import com.finsight.news.News;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NewsAssemblerTest {
    @Test void collectsAllLevelsInOneCallAndNeverRewritesStoredArticle() {
        var ai = mock(AiServiceClient.class); var extractor = mock(ArticleContentExtractor.class);
        var assembler = new NewsAssembler(ai, new NewsCategoryClassifier(), new NewsSymbolMatcher(), new NewsSentimentClassifier(), extractor);
        when(ai.rewrite(any())).thenReturn(Optional.of(new com.finsight.external.AiRewriteResponse("기사", "일반", null, java.util.Map.of(), List.of(), "초보예요.", "일반 사실.", "분석 근거.", "REWRITTEN")));
        var article = assembler.assemble(new NewsCandidate("수출 증가", "https://fixture.test/news", "fixture", Instant.EPOCH, 1), "수출은 12.5% 증가했다.");
        assertThat(article.getRewrittenBeginner()).isEqualTo("초보예요."); assertThat(article.getRewriteMode()).isEqualTo("REWRITTEN");
        verify(ai, times(1)).rewrite(new com.finsight.external.AiRewriteRequest("수출 증가", "수출은 12.5% 증가했다.", null, "https://fixture.test/news"));
        assembler.reprocess(article); verifyNoMoreInteractions(ai); verifyNoInteractions(extractor);
    }
    @Test
    void failedEmptyOrUnusableRefreshPreservesStoredBodyAndAllReadingLevels() {
        var extractor = mock(ArticleContentExtractor.class);
        var ai = mock(AiServiceClient.class);
        var assembler = new NewsAssembler(ai, new NewsCategoryClassifier(), new NewsSymbolMatcher(),
                new NewsSentimentClassifier(), extractor);
        var article = new News("기준금리 전망", "https://publisher.test/article", "publisher", Instant.EPOCH, "기존 원문");
        article.setRewrittenBeginner("초급 요약");
        article.setRewrittenNormal("일반 요약");
        article.setRewrittenAnalyst("분석 요약");
        article.setImportanceReason("투자 포인트");
        article.setKeyTerms(List.of("기준금리"));
        article.setRelatedSymbol("KOSPI");
        for (var result : List.of(Optional.<String>empty(), Optional.of("   "), Optional.of("차단 안내"))) {
            when(extractor.extract(article.getUrl())).thenReturn(result);
            assembler.reprocess(article);
            assertThat(article.getRawContent()).isEqualTo("기존 원문");
            assertThat(article.getRewrittenBeginner()).isEqualTo("초급 요약");
            assertThat(article.getRewrittenNormal()).isEqualTo("일반 요약");
            assertThat(article.getRewrittenAnalyst()).isEqualTo("분석 요약");
            assertThat(article.getImportanceReason()).isEqualTo("투자 포인트");
            assertThat(article.getKeyTerms()).containsExactly("기준금리");
            assertThat(article.getRelatedSymbol()).isEqualTo("KOSPI");
        }
        verifyNoInteractions(ai);
    }
}
