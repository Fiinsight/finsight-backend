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
    @Test void preservesGroundedReasonsAndSourceAcrossAllLevels() {
        var ai = mock(AiServiceClient.class);
        var assembler = new NewsAssembler(ai, new NewsCategoryClassifier(), new NewsSymbolMatcher(),
                new NewsSentimentClassifier(), mock(ArticleContentExtractor.class));
        var reasons = java.util.Map.of("beginner", "매출 12.5% 증가를 확인해요", "normal", "매출 12.5% 증가, 비교 기준 확인", "analyst", "매출 12.5% 증가, 일회성 요인 확인");
        when(ai.rewrite(any())).thenReturn(Optional.of(new com.finsight.external.AiRewriteResponse(
                "기사", "일반", reasons.get("normal"), reasons, List.of(), "초보", "일반", "분석", "EXCERPT")));
        var date = Instant.parse("2026-10-01T00:00:00Z");
        var article = assembler.assemble(new NewsCandidate("매출 증가", "https://publisher.test/article", "publisher", date, 1), "매출 12.5% 증가", false);
        assertThat(article.getImportanceReasonBeginner()).isEqualTo(reasons.get("beginner"));
        assertThat(article.getImportanceReasonNormal()).isEqualTo(reasons.get("normal"));
        assertThat(article.getImportanceReasonAnalyst()).isEqualTo(reasons.get("analyst"));
        assertThat(article.getUrl()).isEqualTo("https://publisher.test/article");
        assertThat(article.getPublishedAt()).isEqualTo(date);
    }

    @Test void existingNewsMetadataAndRefreshNeverApplyTheNewSymbolMatcher() {
        var ai=mock(AiServiceClient.class);var extractor=mock(ArticleContentExtractor.class);
        var assembler=new NewsAssembler(ai,new NewsCategoryClassifier(),new NewsSymbolMatcher(),new NewsSentimentClassifier(),extractor);
        for(String previous:java.util.Arrays.asList(null,"005930")) {
            var article=new News("포스코인터 신규 계약","https://fixture.test/existing","fixture",Instant.EPOCH,"기존 원문");
            article.setRelatedSymbol(previous);
            when(extractor.extract(article.getUrl())).thenReturn(Optional.of("포스코인터가 신규 계약을 발표했다."));
            when(extractor.isUsable(any(),any())).thenReturn(true);
            assembler.refreshMetadata(article);assembler.reprocess(article);
            assertThat(article.getRelatedSymbol()).isEqualTo(previous);
        }
        var fresh=assembler.assemble(new NewsCandidate("포스코인터 신규 계약","https://fixture.test/new","fixture",Instant.EPOCH,1),"포스코인터가 신규 계약을 발표했다.",false);
        assertThat(fresh.getRelatedSymbol()).isEqualTo("047050");
    }
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

    @Test void tabularUnavailableResponseIsNotPersistedAsARewriteOrRetried() {
        var ai=mock(AiServiceClient.class);
        when(ai.rewrite(any())).thenReturn(Optional.of(new com.finsight.external.AiRewriteResponse("외국환시세","",null,java.util.Map.of(),List.of(),"","","","UNAVAILABLE",false)));
        var assembler=new NewsAssembler(ai,new NewsCategoryClassifier(),new NewsSymbolMatcher(),new NewsSentimentClassifier(),mock(ArticleContentExtractor.class));
        var news=assembler.assemble(new NewsCandidate("외국환시세","https://fixture.test/fx","보도",Instant.now(),1),"USD 1400 1410 EUR 1500 1510",false);
        assertThat(news.getRewriteMode()).isEqualTo("UNAVAILABLE");
        assertThat(news.isRewriteRetryPending()).isFalse();
        assertThat(news.getRewrittenBeginner()).isNull();
    }
}
