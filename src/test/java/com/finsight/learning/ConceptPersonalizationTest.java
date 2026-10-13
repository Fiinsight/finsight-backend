package com.finsight.learning;

import com.finsight.auth.*;
import com.finsight.briefing.*;
import com.finsight.external.AiServiceClient;
import com.finsight.news.*;
import com.finsight.news.collect.ArticleContentExtractor;
import com.finsight.term.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConceptPersonalizationTest {
    private final NewsRepository news = mock(NewsRepository.class);
    private final TermRepository terms = mock(TermRepository.class);
    private final TermMasteryService mastery = mock(TermMasteryService.class);
    private final BriefingService briefing = mock(BriefingService.class);
    private final LearningService service = new LearningService(news, mock(UserRepository.class), terms,
            mock(LearningProgressRepository.class), mock(UserProfileService.class), mock(AiServiceClient.class),
            new ArticleContentExtractor(), mastery, briefing);

    private News article(long id, String content) {
        var row = mock(News.class);
        when(row.getId()).thenReturn(id); when(row.getTitle()).thenReturn(content);
        when(row.getRawContent()).thenReturn(content + " 관련 사실을 확인했다.");
        when(news.findById(id)).thenReturn(Optional.of(row));
        return row;
    }
    private TermMasteryService.Mastery known(String term, double p, int attempts, boolean correct) {
        return new TermMasteryService.Mastery(term,p,p,attempts,Instant.now(),"beginner",correct);
    }
    private NewsBriefResponse brief(long id) {
        return new NewsBriefResponse(id,"기사","요약","근거",null,"금리·채권",SentimentHint.NEUTRAL,"https://fixture.test","언론사",Instant.now(),1);
    }
    @Test void topicsRemainInGlossaryButNeverBecomeQuizTargetsAndMarketsAreBeginnerOnly() {
        var topicWords=List.of("반도체","AI","HBM","투자","수출","수입","원화");
        when(terms.findAll()).thenReturn(topicWords.stream().map(word->new Term(word,"일반 설명")).toList());
        var row=article(1,"반도체 AI HBM 투자 수출 수입 원화");
        var lesson=service.lesson(1L,1L,"beginner");
        assertNull(lesson.question()); assertFalse(lesson.glossary().isEmpty());
        for(var word:topicWords) {
            assertEquals(Term.Kind.TOPIC,new Term(word,"설명").getKind());
            assertThrows(org.springframework.web.server.ResponseStatusException.class,()->service.answer(1L,1L,"beginner",word,0));
        }
        verify(mastery,never()).answer(any(),any(),any(),anyBoolean());
        var market=new Term("코스피","시장 지수");
        when(terms.findAll()).thenReturn(List.of(market,new Term("수급","수요 공급"),new Term("변동성","변화 폭")));
        assertNotNull(service.question(row,market,"beginner"));
        assertNull(service.question(row,market,"normal")); assertNull(service.question(row,market,"analyst"));
    }
    @Test void masteryBoundariesDoNotFillEmptyGroupsAndLastWrongTakesPriority() {
        var result=TermMasteryService.overview(List.of(known(".79",.79,3,true),known("2회",.8,2,true),
                known("3회",.8,3,true),known(".5",.5,3,true),known(".49",.499,3,true),known("마지막오답",.9,8,false)));
        assertEquals(List.of("3회"),result.strongest().stream().map(TermMasteryService.Mastery::term).toList());
        assertEquals(List.of(".49","마지막오답"),result.weakest().stream().map(TermMasteryService.Mastery::term).toList());
        assertEquals(3,result.learningCount());
        assertTrue(TermMasteryService.overview(List.of()).strongest().isEmpty());
        assertTrue(TermMasteryService.overview(List.of()).weakest().isEmpty());
    }
    @Test void sameCoreNewsProducesDifferentDailyConceptsAndNeverTopicsOrMarkets() {
        when(terms.findAll()).thenReturn(List.of(new Term("금리","이자 비율"),new Term("환율","교환 비율"),new Term("물가","가격 수준"),new Term("수급","수요 공급"),new Term("반도체","부품"),new Term("코스피","지수")));
        article(1,"금리 환율 물가 수급 반도체 코스피");
        when(briefing.getTodayBriefing()).thenReturn(List.of(brief(1)));
        when(mastery.list(1L)).thenReturn(List.of(known("금리",.25,5,false)));
        when(mastery.list(2L)).thenReturn(List.of(known("금리",.9,5,true),known("환율",.7,2,true)));
        var beginner=service.dailyTerms(1L); var investor=service.dailyTerms(2L);
        assertEquals("금리",beginner.getFirst().term()); assertEquals("지난번에 틀린 개념",beginner.getFirst().reason());
        assertFalse(investor.stream().anyMatch(row->row.term().equals("금리")));
        assertEquals("처음 보는 개념",investor.getFirst().reason());
        assertEquals("익히는 중인 개념",investor.getLast().reason());
        assertNotEquals(beginner.stream().map(LearningService.DailyTerm::term).toList(),investor.stream().map(LearningService.DailyTerm::term).toList());
        assertTrue(java.util.stream.Stream.concat(beginner.stream(),investor.stream()).allMatch(row->Term.classify(row.term())==Term.Kind.CONCEPT && row.newsId()==1L));
        verify(news,never()).findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(any());
    }
    @Test void candidateShortageExpandsOnlyToRecentArticlesWithoutDictionaryFiller() {
        when(terms.findAll()).thenReturn(List.of(new Term("금리","이자"),new Term("환율","비율"),new Term("물가","가격"),new Term("반도체","부품")));
        article(1,"금리"); var recent=article(2,"환율 물가 반도체");
        when(briefing.getTodayBriefing()).thenReturn(List.of(brief(1)));
        when(mastery.list(1L)).thenReturn(List.of(known("금리",.9,3,true)));
        when(news.findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(any())).thenReturn(List.of(recent));
        assertEquals(List.of("물가","환율"),service.dailyTerms(1L).stream().map(LearningService.DailyTerm::term).toList());
        verify(news).findByPublishedAtGreaterThanEqualOrderByPublishedAtDesc(argThat(at->at.isAfter(Instant.now().minusSeconds(86405)) && at.isBefore(Instant.now().minusSeconds(86395))));
    }

    @Test void reviewsUseTheSameHalfProbabilityBoundaryAndExcludeHistoricalTopics() {
        var progress=mock(LearningProgressRepository.class);
        var learning=new LearningService(news,mock(UserRepository.class),terms,progress,mock(UserProfileService.class),
                mock(AiServiceClient.class),new ArticleContentExtractor(),mastery,briefing);
        var article=article(1,"금리 환율 매출 반도체"); var user=mock(User.class);
        var half=new LearningProgress(user,article,"금리");half.answer("beginner",true);
        var low=new LearningProgress(user,article,"환율");low.answer("beginner",true);
        var wrong=new LearningProgress(user,article,"매출");wrong.answer("beginner",false);
        var topic=new LearningProgress(user,article,"반도체");topic.answer("beginner",false);
        when(terms.findAll()).thenReturn(List.of(new Term("금리","이자"),new Term("환율","비율"),new Term("매출","금액"),new Term("반도체","부품")));
        when(progress.findByUser_IdOrderByAnsweredAtDesc(1L)).thenReturn(List.of(half,low,wrong,topic));
        when(mastery.list(1L)).thenReturn(List.of(known("금리",.5,3,true),known("환율",.499,3,true),known("매출",.9,8,false)));
        assertEquals(List.of("환율","매출"),learning.reviews(1L).stream().map(LearningService.Review::term).toList());
    }
}
