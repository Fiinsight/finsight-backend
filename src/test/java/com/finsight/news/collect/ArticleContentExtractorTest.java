package com.finsight.news.collect;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ArticleContentExtractorTest {
    private final ArticleContentExtractor extractor = new ArticleContentExtractor();
    @Test void removesMultilinePhotoCreditsAndBylineWithoutRemovingArticleFacts() {
        String body = "[연합뉴스 자료사진.\n현대로템 제공.\n재판매 및 DB 금지]\n(서울=연합뉴스) 황철환 기자 = 목표주가를 22만6천원으로 조정했다. pdj6635@yna. 사진=AFP\n수주 계약 시점은 내년 1분기로 예상했다.";
        String cleaned = extractor.clean("현대로템 전망", body);
        assertTrue(cleaned.contains("22만6천원"));
        assertTrue(cleaned.contains("내년 1분기"));
        for (String noise : new String[]{"자료사진", "재판매", "기자", "@yna", "AFP"}) assertFalse(cleaned.contains(noise));
    }
    @Test void dropsDatedDealingRoomCaptionAndKeepsFacts() {
        String cleaned = extractor.clean("코스피 전망", "서울 딜링룸에서 직원이 일하고 있다. 사진 촬영. 2026.10.6 pdj6635@yna.co.kr\n코스피 예상 범위는 6400∼7600이다.");
        assertFalse(cleaned.contains("딜링룸"));
        assertTrue(cleaned.contains("6400∼7600"));
    }
}
