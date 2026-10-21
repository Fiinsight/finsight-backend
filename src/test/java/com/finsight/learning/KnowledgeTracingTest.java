package com.finsight.learning;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeTracingTest {
    @Test void deterministicCorrectWrongAndMixedSequences() {
        assertEquals(0.6080838323353294, KnowledgeTracing.update(.3,true,"beginner"), 1e-12);
        assertEquals(0.20110220440881762, KnowledgeTracing.update(.3,false,"beginner"), 1e-12);
        assertEquals(0.943553210468873, sequence(true,true,true), 1e-12);
        assertEquals(0.17710298120755572, sequence(false,false,false), 1e-12);
        assertEquals(0.6178592476463163, sequence(true,false,true), 1e-12);
        assertTrue(KnowledgeTracing.update(.3,true,"analyst") > KnowledgeTracing.update(.3,true,"beginner"));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeTracing.update(Double.NaN,true,"beginner"));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeTracing.update(.3,true,"invalid"));
    }
    private double sequence(boolean... answers) {
        double p = KnowledgeTracing.P_INIT;
        for (boolean correct : answers) p = KnowledgeTracing.update(p,correct,"beginner");
        return p;
    }
    @Test void decayAndSelectionBoundariesPreserveArticleOrderOnTies() {
        assertEquals(.3 + .3 * Math.pow(.97,10), KnowledgeTracing.decay(.6,10), 1e-12);
        assertEquals(.6, KnowledgeTracing.decay(.6,-1));
        var terms = List.of("금리","환율","물가");
        assertEquals("환율", KnowledgeTracing.select(terms,Map.of("금리",.1,"환율",.4,"물가",.7)));
        assertEquals("물가", KnowledgeTracing.select(terms,Map.of("금리",.9,"환율",.8,"물가",.7)));
        assertEquals("금리", KnowledgeTracing.select(terms,Map.of("금리",.2,"환율",.8,"물가",.9)));
        assertEquals("금리", KnowledgeTracing.select(terms,Map.of()));
        assertNull(KnowledgeTracing.select(List.of(),Map.of()));
    }
    @Test void masterySuggestionUsesEffectiveConceptEvidenceAndRequiresEnoughAttempts() {
        var rows = new ArrayList<TermMasteryService.Mastery>();
        for(int i=0;i<5;i++) rows.add(new TermMasteryService.Mastery("용어"+i,.9,.85,3,Instant.EPOCH,"normal",true));
        assertEquals("analyst", TermMasteryService.suggestion(rows,"normal"));
        assertNull(TermMasteryService.suggestion(rows.subList(0,4),"normal"));
        assertNull(TermMasteryService.suggestion(rows,"beginner"));
        assertNull(TermMasteryService.suggestion(rows,"analyst"));
        assertNull(TermMasteryService.suggestion(Collections.nCopies(5,rows.getFirst()),"normal"));
        rows.set(0,new TermMasteryService.Mastery("용어0",.99,.4,10,Instant.EPOCH,"normal",true));
        assertNull(TermMasteryService.suggestion(rows,"normal"));
        rows.set(0,new TermMasteryService.Mastery("용어0",.99,.99,2,Instant.EPOCH,"normal",true));
        assertNull(TermMasteryService.suggestion(rows,"normal"));
        rows.set(0,new TermMasteryService.Mastery("용어0",.99,.99,5,Instant.EPOCH,"normal",false));
        assertNull(TermMasteryService.suggestion(rows,"normal"));
    }

    @Test void forgettingConvergesToTheInitialFloorWithKstDayBoundaries() {
        assertEquals(.3+.65*Math.pow(.97,60),KnowledgeTracing.decay(.95,60),1e-12);
        assertTrue(KnowledgeTracing.decay(.95,60) > KnowledgeTracing.P_INIT);
        assertEquals(.3,KnowledgeTracing.decay(.95,100000),1e-12);
        assertEquals(.3,KnowledgeTracing.decay(.3,60),1e-12);
        assertEquals(.2,KnowledgeTracing.decay(.2,0),1e-12);
        var before=Instant.parse("2026-10-20T14:59:00Z");
        var after=Instant.parse("2026-10-20T15:00:00Z");
        assertEquals(KnowledgeTracing.decay(.9,1),TermMasteryService.effective(.9,before,after,java.time.ZoneId.of("Asia/Seoul")),1e-12);
    }
}
