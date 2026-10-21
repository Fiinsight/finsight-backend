package com.finsight.news.collect;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WatchlistSymbolMatcherTest {
    private final NewsSymbolMatcher matcher=new NewsSymbolMatcher();
    @Test void catalogAliasesAndParticlesMatch() {
        assertEquals("005930",matcher.match("삼전 실적 개선"));
        assertEquals("000660",matcher.match("하이닉스 공급 확대"));
        assertEquals("047050",matcher.match("포스코인터 계약"));
        assertEquals("005930",matcher.match("기업 계약 발표","삼성전자가 계약했다."));
        assertEquals("005935",matcher.match("삼성전자우 배당"));
        assertEquals("000660",matcher.match("SK 하이닉스 실적"));
    }
    @Test void titleWinsThenBodyFrequencyAndTiesAreDeterministic() {
        assertEquals("005930",matcher.match("삼전 실적","하이닉스 하이닉스 하이닉스"));
        assertEquals("000660",matcher.match("삼성전자·SK하이닉스 실적","삼성전자, SK하이닉스와 SK하이닉스"));
        assertEquals("005930",matcher.match("삼성전자·SK하이닉스 실적",""));
        assertEquals("047050",matcher.match("기업 공급 계약","삼성전자[005930] 비교. 포스코인터 계약. 포스코인터 공급."));
        assertEquals("KOSPI",matcher.match("코스피, 삼전닉스 하락","삼성전자 실적"));
        assertNull(matcher.match("기업 동향","삼성전자, SK하이닉스"));
    }
    @Test void ordinaryWordsSubsidiariesAndUnknownCodesAreNotStocks() {
        for(String text:java.util.List.of("삼전기계 실적","삼바 춤 축제","세계 기아 감소","카카오 분말 공급","카카오톡 서비스","카카오페이 서비스","현대차증권 보고서","기업[999999] 실적")) assertNull(matcher.match(text),text);
        assertEquals("000270",matcher.match("기아 실적 개선"));
        assertEquals("000270",matcher.match("신한증권 기아, 원가부담에 실적 기대치 하락"));
        assertEquals("323410",matcher.match("카카오뱅크 실적 개선"));
    }
}
