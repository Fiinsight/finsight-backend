package com.finsight.chart;

import com.finsight.external.AiDocentRequest;
import com.finsight.news.SentimentEvidence;
import com.finsight.briefing.SentimentHint;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SentimentEvidenceTest {
    @Test void confidenceControlsDirectionButNeverArticleRelation() {
        assertNull(SentimentEvidence.direction("POSITIVE",.59,"호실적 급등"));
        assertEquals(SentimentHint.POSITIVE,SentimentEvidence.direction("POSITIVE",.6,"주요 소식"));
        assertNull(SentimentEvidence.direction("NEUTRAL",.9,"급등"));
        assertEquals(SentimentHint.NEGATIVE,SentimentEvidence.direction(null,null,"부진"));
        assertNull(SentimentEvidence.direction("POSITIVE",Double.NaN,"급등"));
        for (String relation : List.of("DIRECT","MARKET")) {
            var article=new AiDocentRequest.Article(1L,"호실적 소식","매출이 늘었다.",Instant.now(),relation,"fixture","POSITIVE",.59);
            var result=ChartDocentService.rule(new AiDocentRequest("005930","삼성전자","2026-10-12",2,"D",List.of(article),"normal",null,null));
            assertEquals(relation,result.relation());assertFalse(result.possibleReason().contains("호재로"));
        }
        assertTrue(SentimentEvidence.note(SentimentHint.POSITIVE,.8).contains("단정할 수는 없어요"));
        assertTrue(SentimentEvidence.note(SentimentHint.POSITIVE,.59).contains("판단하기 어려워요"));
    }

    @Test void sharedTitleVocabularyUsesTheFinalDirectionInMixedHeadlines() {
        for(String word:List.of("상한가","신고가","폭등","급반등","치솟","껑충","뛰었","3%↑","3% 올라","52주 최고")) {
            assertEquals(SentimentHint.POSITIVE,SentimentEvidence.titleDirection("삼성전자 "+word));
            assertEquals(SentimentEvidence.titleDirection(word),ChartDocentService.titleSentiment(word));
        }
        for(String word:List.of("하한가","신저가","폭락","추락","곤두박질","주저앉","3%↓","3% 내려","52주 최저")) assertEquals(SentimentHint.NEGATIVE,SentimentEvidence.titleDirection(word));
        assertEquals(SentimentHint.POSITIVE,SentimentEvidence.titleDirection("하락 딛고 반등"));
        assertEquals(SentimentHint.NEGATIVE,SentimentEvidence.titleDirection("반등 뒤 다시 추락"));
        assertNull(SentimentEvidence.titleDirection("52주 거래 자료"));
    }
}
