package com.finsight.localml;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ChartCompanyMatcherTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "005930,삼성 전자,삼전", "000660,SK 하이닉스,하닉", "035420,네이버,naver",
        "035720,카카오,카카오", "373220,LG에너지 솔루션,LG엔솔", "005380,현대 자동차,현대차",
        "000270,기아자동차,기아", "207940,삼성 바이오로직스,삼바", "051910,LG 화학,엘지화학",
        "068270,셀트리온,셀트리온", "005490,POSCO 홀딩스,포스코홀딩스"
    })
    void allCatalogCompaniesShareSearchAndNewsAliases(String symbol,String name,String alias) {
        assertTrue(ChartCompanyMatcher.searchMatches(symbol,name));
        assertTrue(ChartCompanyMatcher.searchMatches(symbol,alias));
        assertNotNull(ChartCompanyMatcher.reason(symbol,name+" 신규 사업 발표",""));
        assertNotNull(ChartCompanyMatcher.reason(symbol,alias+" 신규 사업 발표",""));
        assertNotNull(ChartCompanyMatcher.reason(symbol,"산업 소식",name+" 신규 사업 발표"));
    }
    @Test void excerptIncludesCompanyMentionBeyondFirstFourThousandCharacters() {
        String body="서론 내용. ".repeat(1000)+"LG에너지 솔루션이 신규 배터리를 공개했다.";
        assertTrue(ChartCompanyMatcher.excerpt("373220",body,4000).contains("LG에너지 솔루션"));
    }
    @Test void oneArticleCanMatchBothMemoryCompanies() {
        assertNotNull(ChartCompanyMatcher.reason("005930", "삼전닉스 주가 동향", ""));
        assertNotNull(ChartCompanyMatcher.reason("000660", "삼전닉스 주가 동향", ""));
        assertNotNull(ChartCompanyMatcher.reason("000660", "증시 동향", "SK 하이닉스가 상승했다."));
    }
    @Test void removesPublisherContactBeforeMatching() {
        assertNull(ChartCompanyMatcher.reason("035720", "국채 금리 상승", "금리가 상승했다. 제보는 카카오톡 okjebo"));
        assertNotNull(ChartCompanyMatcher.reason("035720", "카카오, AI 거점 설립", ""));
        assertNotNull(ChartCompanyMatcher.reason("035720", "플랫폼 업계", "카카오톡의 신규 서비스가 공개됐다. 제보는 카카오톡 okjebo"));
    }
    @Test void doesNotConflateListedAffiliates() {
        assertNull(ChartCompanyMatcher.reason("005930", "삼성전기 기판 투자", "삼성전기의 신규 공장"));
        assertNull(ChartCompanyMatcher.reason("035720", "카카오페이 출신 임원 선임", "카카오게임즈 신작 출시"));
        assertNull(ChartCompanyMatcher.reason("005380", "현대차증권 목표가", ""));
    }
    @Test void handlesAliasesAndUnscopedQueries() {
        assertNotNull(ChartCompanyMatcher.reason("035420", "NAVER 실적", ""));
        assertNotNull(ChartCompanyMatcher.reason("373220", "LG 엔솔 실적", ""));
        assertNull(ChartCompanyMatcher.get(null));
        assertNull(ChartCompanyMatcher.reason("999999", "종목", "본문"));
    }
}
