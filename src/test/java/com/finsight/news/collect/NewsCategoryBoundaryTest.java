package com.finsight.news.collect;
import java.util.*;import org.junit.jupiter.api.Test;import static org.assertj.core.api.Assertions.*;
class NewsCategoryBoundaryTest {
    @Test void latinSubstringsAndEquipmentOrPensionManagementAreNotTechOrFundCompanies() {
        var classifier=new NewsCategoryClassifier();
        for(String title:List.of("CHAIR 판매 동향 발표","RETAIL 소비 지표 발표","AIBANK 서비스 운영 안내","국민연금 기금운용 계획","장비 운용 일정 변경"))
            assertThat(classifier.classify(title,"")).as(title).isEqualTo("정책·경제");
        assertThat(classifier.classify("신규 펀드 출시","")).isEqualTo("기업·종목");
        assertThat(classifier.classify("키움운용 코스닥150 분배금","")).isEqualTo("기업·종목");
    }
    @Test void industrySpecificExportsAndMacroExportsStayDistinct() {
        var classifier=new NewsCategoryClassifier();
        for(String title:List.of("반도체 수출 증가","전기차 수출 확대","조선 수출 호조","AI 반도체 투자"))
            assertThat(classifier.classify(title,"")).as(title).isEqualTo("산업·기술");
        assertThat(classifier.classify("한국 수출 10% 증가","")).isEqualTo("정책·경제");
        assertThat(classifier.classify("기준금리 동결 발표","")).isEqualTo("금리·통화정책");
    }
}
